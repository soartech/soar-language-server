package com.soartech.soarls;

import static java.util.stream.Collectors.toList;

import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.tcl.TclParser;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.jsoar.kernel.Agent;
import org.jsoar.kernel.SoarException;

/**
 * This class keeps track of the contents of a Soar source file along with a (currently primitive)
 * parse tree and some utilities for converting between offsets and line/column positions.
 *
 * <p>Note that this class should be treated as immutable after construction. To apply edits to the
 * file, use the withChanges() method, which returns a new SoarFile.
 */
public class SoarFile {
  public final String uri;

  public final String contents;

  public final List<Diagnostic> diagnostics;

  /**
   * The Tcl syntax tree from parsing this file. Even if the file is not valid syntax, this will at
   * least contain a root node. Note that instances of the SoarFile class should be treated as
   * immutable, even though we can't enforce that you won't modify the AST after it is constructed.
   * Please don't do that.
   */
  public final TclAstNode ast;

  public SoarFile(String uri, String contents) {
    this.uri = uri;
    this.contents = fixLineEndings(contents);

    TclParser parser = new TclParser();
    parser.setInput(this.contents.toCharArray(), 0, this.contents.length());
    this.ast = parser.parse();

    this.diagnostics =
        parser
            .getErrors()
            .stream()
            .map(
                e -> {
                  int start = position(e.getStart()).getLine();
                  return new Diagnostic(
                      new Range(new Position(start, 0), getEndOfLinePosition(start)),
                      e.getMessage(),
                      DiagnosticSeverity.Error,
                      "soar");
                })
            .collect(toList());
  }

  /** Apply the changes from a textDocument/didChange notification and returns a new file. */
  SoarFile withChanges(List<TextDocumentContentChangeEvent> changes) {
    BiFunction<StringBuffer, TextDocumentContentChangeEvent, StringBuffer> applyChange =
        (buffer, change) -> {
          // The parameters which are set depends on whether we are
          // using full or incremental updates.
          if (change.getRange() == null) {
            // We are using full document updates.
            return new StringBuffer(change.getText());
          } else {
            // We are using incremental updates.
            int start = offset(change.getRange().getStart());
            int end = offset(change.getRange().getEnd());
            return buffer.replace(start, end, change.getText());
          }
        };

    String newContents =
        changes
            .stream()
            .reduce(new StringBuffer(this.contents), applyChange, (u, v) -> v)
            .toString();

    return new SoarFile(this.uri, newContents);
  }

  /** A special case of the withChanges function, where there is only a single change to apply. */
  SoarFile withChange(TextDocumentContentChangeEvent change) {
    return this.withChanges(Arrays.asList(change));
  }

  public void traverseAstTree(Consumer<TclAstNode> implementation) {
    traverseAstTreeHelper(implementation, this.ast);
  }

  private void traverseAstTreeHelper(Consumer<TclAstNode> implementation, TclAstNode currentNode) {
    implementation.accept(currentNode);
    for (TclAstNode child : currentNode.getChildren()) {
      traverseAstTreeHelper(implementation, child);
    }
  }

  public String getNodeInternalText(TclAstNode node) {
    return node.getInternalText(this.contents.toCharArray());
  }

  /** Get the Tcl AST node at the given position. */
  TclAstNode tclNode(Position position) {
    return tclNode(offset(position));
  }

  /** Get the Tcl AST node at the given offset. */
  TclAstNode tclNode(int offset) {
    // Start at the root, which contains the entire file.
    TclAstNode node = this.ast;

    // If there is a child that contains this position, then
    // recurse downwards; otherwise, return the currently focused
    // node.
    while (true) {
      TclAstNode child =
          node.getChildren()
              .stream()
              .filter(c -> c.getStart() <= offset)
              .filter(c -> offset < c.getEnd())
              .findFirst()
              .orElse(null);
      if (child != null) {
        node = child;
      } else {
        return node;
      }
    }
  }

  /** Get a single line as a string. */
  String line(int lineNumber) {
    int start = offset(new Position(lineNumber, 0));
    int end = offset(new Position(lineNumber + 1, 0));
    // This avoids choking on the last line of the file.
    if (end < 0) {
      return contents.substring(start);
    } else {
      return contents.substring(start, end);
    }
  }

  /** Get the 0-based offset at the given position. */
  public int offset(Position position) {
    int offset = 0;
    int lines = position.getLine();
    for (char ch : contents.toCharArray()) {
      if (lines == 0) {
        return offset + position.getCharacter();
      }
      if (ch == '\n') {
        lines -= 1;
      }
      offset += 1;
    }
    return contents.length();
  }

  /** Get the line/column of the given 0-based offset. */
  public Position position(int offset) {
    int line = 0;
    int character = 0;
    for (int i = 0; i != offset; ++i) {
      if (contents.charAt(i) == '\n') {
        line += 1;
        character = 0;
      } else {
        character += 1;
      }
    }
    return new Position(line, character);
  }

  private String fixLineEndings(String contents) {
    contents = contents.replace("\r\n", "\n");
    contents = contents.replace("\r", "\n");
    return contents;
  }

  public String getExpandedCommand(Agent agent, TclAstNode node) {
    // compare children of node to ast root
    // if they are the same then assume that done on production "name" so expand the whole thing
    // otherwise return the unexpanded text

    // if not on quoted production return null
    if (node.getType() != TclAstNode.QUOTED_WORD) return null;

    // find production node
    TclAstNode parent = findRootBranchNode(node);

    if (parent == null) return node.getInternalText(contents.toCharArray());

    String parent_command = parent.getInternalText(contents.toCharArray());
    // strip beginning sp from command (up till first ")
    int first_quote_index = parent_command.indexOf('"');
    String beginning = parent_command.substring(0, first_quote_index + 1);
    parent_command = parent_command.substring(first_quote_index);

    try {
      return beginning + agent.getInterpreter().eval("return " + parent_command) + '"';
    } catch (SoarException e) {
      return parent_command;
    }
  }

  TclAstNode findRootBranchNode(TclAstNode node) {
    for (TclAstNode child : this.ast.getChildren()) {
      if (branchContainsNode(child, node)) {
        return child;
      }
    }
    return null;
  }

  private boolean branchContainsNode(TclAstNode treeNode, TclAstNode searchNode) {
    if (treeNode == searchNode) return true;

    for (TclAstNode child : treeNode.getChildren()) {
      if (branchContainsNode(child, searchNode)) return true;
    }

    return false;
  }

  // returns a Position of the last character on a given line
  private Position getEndOfLinePosition(int line) {
    String[] lines = contents.split("\n");

    return new Position(line, lines[line].length());
  }

  List<Diagnostic> getDiagnostics() {
    return diagnostics;
  }

  /** Get the range that encompasses the given Tcl AST node. */
  public Range rangeForNode(TclAstNode node) {
    return new Range(position(node.getStart()), position(node.getEnd()));
  }
}

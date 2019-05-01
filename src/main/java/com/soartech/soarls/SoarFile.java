package com.soartech.soarls;

import static java.util.stream.Collectors.toList;

import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.tcl.TclParser;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

/**
 * This class keeps track of the contents of a Soar source file along with a (currently primitive)
 * parse tree and some utilities for converting between offsets and line/column positions.
 *
 * <p>Note that this class should be treated as immutable after construction. To apply edits to the
 * file, use the withChanges() method, which returns a new SoarFile.
 */
public class SoarFile {
  public final URI uri;

  public final String contents;

  public final List<Diagnostic> diagnostics;

  /**
   * The Tcl syntax tree from parsing this file. Even if the file is not valid syntax, this will at
   * least contain a root node. Note that instances of the SoarFile class should be treated as
   * immutable, even though we can't enforce that you won't modify the AST after it is constructed.
   * Please don't do that.
   */
  public final TclAstNode ast;

  public SoarFile(URI uri, String contents) {
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

  public void traverseAst(Consumer<TclAstNode> implementation) {
    traverseAstHelper(implementation, this.ast);
  }

  private void traverseAstHelper(Consumer<TclAstNode> implementation, TclAstNode currentNode) {
    implementation.accept(currentNode);
    for (TclAstNode child : currentNode.getChildren()) {
      traverseAstHelper(implementation, child);
    }
  }

  public String getNodeInternalText(TclAstNode node) {
    return node.getInternalText(this.contents.toCharArray());
  }

  /** Get the Tcl AST node at the given position. */
  public TclAstNode tclNode(Position position) {
    return tclNode(offset(position));
  }

  /** Get the Tcl AST node at the given offset. */
  public TclAstNode tclNode(int offset) {
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
    return contents.substring(start, end);
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

  public boolean isInRange(Position position, Range range) {
    return isInRange(offset(position), range);
  }

  public boolean isInRange(int offset, Range range) {
    int start_offset = offset(range.getStart());
    int end_offset = offset(range.getEnd());

    if (offset < start_offset) return false;
    if (offset > end_offset) return false;

    return true;
  }

  private String fixLineEndings(String contents) {
    contents = contents.replace("\r\n", "\n");
    contents = contents.replace("\r", "\n");
    return contents;
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

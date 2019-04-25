/** This Tcl parser originally comes from the SoarIDE. */
package com.soartech.soarls.tcl;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple Tcl parser that breaks Tcl input up into words as specified in the Tcl manual page.
 *
 * <p>Returns a simple hierarchical parser tree of words. The contents of braces will be verified
 * for validity (nested, unescaped braces match), but only nested braced words will be decomposed
 * further. For braced words, such as a proc body, a second pass with this parser will be necessary
 * on only that section to discover comments, or embedded commands.
 *
 * <p><a href="http://www.tcl.tk/man/tcl8.4/TclCmd/Tcl.htm">This page</a>, describes the Tcl
 * "grammar" as such. Grammatically, Tcl consists of "commands", "words" and command terminators.
 *
 * <p>A "word" takes 4 forms:
 *
 * <ul>
 *   <li>A sequence of non-whitespace characters, possibly with nested bracket commands. Whitespace
 *       characters may be escaped.
 *   <li>A sequence of properly escaped characters enclosed in quotes
 *   <li>A sequence of characters enclosed in braces. Non-escaped nested braces must be paired
 *       correctly
 *   <li>A nested command enclosed in square brackets
 * </ul>
 *
 * <p>Here is the grammar rule for a command:
 *
 * <pre>
 * command := word (word)* terminator
 * </pre>
 *
 * <p>A terminator is either a semi-colon or an unescaped end of line.
 *
 * @author ray
 */
public class TclParser {
  private static final char EOF = 0;

  private List<TclParserError> errors = new ArrayList<TclParserError>();

  /** The current input buffer */
  private char input[];

  /**
   * The starting offset that all node positions are adjusted by. This is used when parsing only a
   * portion of a larger document while maintaining error and node positions relative to the larger
   * document.
   */
  private int start;

  private int end;

  /** Current position within the input buffer */
  private int cursor = 0;

  /** Cursor position to rewind to if an error is encountered */
  private int retryPosition = -1;

  public void setInput(char input[], int offset, int length) {
    this.input = input;
    this.start = offset;
    this.end = start + length;
  }

  public void setInput(File file) throws IOException {
    FileReader reader = new FileReader(file);
    try {
      setInput(reader);
    } finally {
      reader.close();
    }
  }

  public void setInput(Reader reader) throws IOException {
    StringBuilder builder = new StringBuilder();

    char buffer[] = new char[4096];
    int r = reader.read(buffer);
    while (r >= 0) {
      builder.append(buffer, 0, r);
      r = reader.read(buffer);
    }

    char chars[] = builder.toString().toCharArray();
    setInput(chars, 0, chars.length);
  }

  /** @return The current input to the parser */
  public char[] getInput() {
    return input;
  }

  /**
   * Parse the current input and return a root node result. A root node is <b>always</b> returned,
   * but the caller must check {@link #getErrors()} to see if any errors occurred.
   *
   * @return Root of parse tree
   */
  public TclAstNode parse() {
    errors.clear();
    TclAstNode root = new TclAstNode(TclAstNode.ROOT, 0);

    // Skip leading whitespace
    consumeWhitespace();

    while (!isEof()) {
      if (lookAhead(0) == '#') {
        TclAstNode comment = consumeComment();
        if (comment != null) {
          root.addChild(comment);
        }
      } else {
        TclAstNode command = consumeCommand();
        if (command != null) {
          root.addChild(command);

          // If there's an error and a retry position
          if (command.getError() != null && retryPosition != -1) {
            cursor = retryPosition; // rewind
            retryPosition = -1; // forget current retry position
          }
        }
      }
      consumeWhitespace();
    }

    root.setEnd(getOffset());
    return root;
  }

  public List<TclParserError> getErrors() {
    return errors;
  }

  private TclAstNode consumeCommand() {
    retryPosition = -1;
    TclAstNode commandNode = new TclAstNode(TclAstNode.COMMAND, getOffset());

    List<TclAstNode> kids = commandNode.getChildren();
    while (!consumeTerminator() && commandNode.getError() == null) {
      TclAstNode node = consumeWord(EOF);
      if (node.getError() != null) {
        commandNode.setError(node.getError());
        commandNode.setEnd(node.getStart() + node.getLength());
      }
      commandNode.addChild(node);
    }
    if (commandNode.getError() == null) {
      if (!kids.isEmpty()) {
        TclAstNode lastChild = kids.get(kids.size() - 1);
        commandNode.setEnd(lastChild.getStart() + lastChild.getLength());
      } else {
        commandNode.setEnd(getOffset());
      }
    }
    return commandNode.getChildren().isEmpty() ? null : commandNode;
  }

  private boolean consumeTerminator() {
    char c = lookAhead(0);
    while (c != EOF) {
      if (c == ';') {
        consume();
        return true;
      } else if (c == '\r' || c == '\n') {
        if (lookAhead(-1) == '\\') {
          // Escaped new-line
          consumeWhitespace();
          return false;
        } else if (c == '\r' && lookAhead(1) == '\n') {
          // Windows-style new-line
          consume();
          consume();
          return true;
        } else {
          // Unix or mac new-line
          consume();
          return true;
        }
      } else if (c == '\\') {
        // Skip this so we can hande escaped newline in next pass
      } else if (!Character.isWhitespace(c)) {
        return false;
      }
      consume();
      c = lookAhead(0);
    }
    return true;
  }

  private TclAstNode consumeWord(char terminator) {
    char c = lookAhead(0);
    if (c == '"') {
      return consumeQuotedWord();
    } else if (c == '{') {
      return consumeBracedWord();
    } else if (c == '[') {
      return consumeCommandWord();
    } else {
      return consumeNormalWord(terminator);
    }
  }

  private TclAstNode consumeNormalWord(char terminator) {
    TclAstNode node = new TclAstNode(TclAstNode.NORMAL_WORD, getOffset());
    char c = lookAhead(0);
    while (c != EOF) {
      // Stop at first whitespace or terminator
      if (Character.isWhitespace(c) || c == terminator) {
        node.setEnd(getOffset());
        return node;
      } else if (c == ';') {
        node.setEnd(getOffset());
        consume();
        return node;
      } else if (c == '\\') {
        consumeEscapedCharacter();
      } else if (c == '[') {
        node.addChild(consumeCommandWord());
      } else if (c == '$') {
        node.addChild(consumeVariable());
      } else {
        consume();
      }
      c = lookAhead(0);
    }
    node.setEnd(getOffset());
    return node;
  }

  private TclAstNode consumeQuotedWord() {
    assert lookAhead(0) == '"';
    TclAstNode node = new TclAstNode(TclAstNode.QUOTED_WORD, getOffset());
    consume();

    char c = lookAhead(0);
    while (c != EOF) {
      // Stop at close quote
      if (c == '"') {
        consume();
        node.setEnd(getOffset());
        return node;
      } else if (c == '\\') {
        consumeEscapedCharacter();
      } else if (c == '[') {
        node.addChild(consumeCommandWord());
      } else if (c == '$') {
        node.addChild(consumeVariable());
      } else if (Character.isWhitespace(c)) {
        consume();
      } else {
        node.addChild(consumeNormalWord('"'));
      }
      c = lookAhead(0);
    }
    node.setEnd(getOffset());
    if (c == EOF) {
      TclParserError error =
          new TclParserError(
              node.getStart(), getEndOfError() - node.getStart(), "Missing closing quote");
      errors.add(error);
      node.setError(error);
    }
    return node;
  }

  private TclAstNode consumeBracedWord() {
    assert lookAhead(0) == '{';
    TclAstNode node = new TclAstNode(TclAstNode.BRACED_WORD, getOffset());
    consume();

    char c = lookAhead(0);
    while (c != EOF) {
      // Stop at close brace
      if (c == '}') {
        consume();
        node.setEnd(getOffset());
        return node;
      } else if (c == '\\') {
        consumeEscapedCharacter();
      } else if (c == '{') {
        TclAstNode child = consumeBracedWord();
        node.addChild(child);
        if (child.getError() != null) {
          node.setError(child.getError());
          node.setEnd(getEndOfError());
          return node;
        }
      } else if (Character.isWhitespace(c)) {
        consume();
      } else {
        TclAstNode child = new TclAstNode(TclAstNode.NORMAL_WORD, getOffset());
        while (c != EOF && c != '}' && !Character.isWhitespace(c)) {
          if (c == '\\') {
            consumeEscapedCharacter();
          } else if (c == '{') {
            consumeBracedWord();
          } else {
            consume();
          }
          c = lookAhead(0);
        }
        child.setEnd(getOffset());
        node.addChild(child);
      }
      c = lookAhead(0);
    }
    node.setEnd(getOffset());
    if (c == EOF) {
      TclParserError error =
          new TclParserError(
              node.getStart(), getEndOfError() - node.getStart(), "Missing closing brace");
      errors.add(error);
      node.setError(error);
    }
    return node;
  }

  private TclAstNode consumeCommandWord() {
    assert lookAhead(0) == '[';
    TclAstNode node = new TclAstNode(TclAstNode.COMMAND_WORD, getOffset());
    consume();

    char c = lookAhead(0);
    while (c != EOF) {
      // Stop at close quote
      TclAstNode child = null;
      if (c == ']') {
        consume();
        node.setEnd(getOffset());
        return node;
      } else if (c == '\\') {
        consumeEscapedCharacter();
      } else if (c == '[') {
        child = consumeCommandWord();
        node.addChild(child);
      } else if (c == '{') {
        child = consumeBracedWord();
        node.addChild(child);
      } else if (c == '$') {
        child = consumeVariable();
        node.addChild(child);
      } else if (Character.isWhitespace(c)) {
        consume();
      } else {
        child = consumeWord(']');
        node.addChild(child);
      }
      if (child != null && child.getError() != null) {
        node.setError(child.getError());
        node.setEnd(getEndOfError());
        return node;
      }
      c = lookAhead(0);
    }

    node.setEnd(getOffset());
    if (c == EOF) {
      TclParserError error =
          new TclParserError(
              node.getStart(), getEndOfError() - node.getStart(), "Missing closing bracket");
      node.setError(error);
      errors.add(error);
    }
    return node;
  }

  private void consumeEscapedCharacter() {
    assert '\\' == lookAhead(0);

    consume();
    char c = lookAhead(0);
    if (c == '\n') {
      consumeWhitespace();
    } else {
      consume();
    }
  }

  private TclAstNode consumeVariable() {
    assert lookAhead(0) == '$';
    TclAstNode node = new TclAstNode(TclAstNode.VARIABLE, getOffset());
    consume();

    char c = lookAhead(0);
    if (c == '{') {
      TclAstNode nameNode = new TclAstNode(TclAstNode.VARIABLE_NAME, getOffset() + 1);
      consumeBracedWord();
      nameNode.setEnd(getOffset() - 1);
      node.addChild(nameNode);
      node.setEnd(getOffset());
      return node;
    } else {
      TclAstNode nameNode = new TclAstNode(TclAstNode.VARIABLE_NAME, getOffset());
      while (Character.isLetterOrDigit(c) || c == '_') {
        consume();
        c = lookAhead(0);
      }
      nameNode.setEnd(getOffset());
      node.addChild(nameNode);
      node.setEnd(getOffset());
      return node;
    }
  }

  private TclAstNode consumeComment() {
    if (isEof() || '#' != lookAhead(0)) {
      return null;
    }

    TclAstNode node = new TclAstNode(TclAstNode.COMMENT, getOffset());

    consumeLine(); // avoid re-test
    while (!isEof() && '#' == lookAhead(0)) {
      consumeLine();
    }

    node.setEnd(getOffset());
    return node;
  }

  private boolean isEof() {
    return cursor == end;
  }

  private int getOffset() {
    return start + cursor;
  }

  private void consume() {
    if (cursor < end) {
      ++cursor;
      updateRetryPosition();
    }
  }

  private int getEndOfError() {
    return retryPosition != -1 ? retryPosition : getOffset();
  }

  private void updateRetryPosition() {
    char fwd2 = lookAhead(2);
    char fwd1 = lookAhead(1);
    char current = lookAhead(0);
    char back1 = lookAhead(-1);
    char back2 = lookAhead(-2);
    char back3 = lookAhead(-3);

    // If we already have a retry position, do nothing.
    if (retryPosition != -1) {
      return;
    }

    // Assume that commands start with letters...
    if (Character.isLetter(current) || (fwd1 == '#' && fwd2 == '#')) {
      // Preceded by non-escaped Windows EOL
      if (back1 == '\n' && back2 == '\r' && back3 != '\\') {
        retryPosition = cursor;
      }
      // Preceded by non-escaped EOL
      else if ((back1 == '\n' || back1 == '\r') && back2 != '\\') {
        retryPosition = cursor;
      }
    }
  }

  private void consumeWhitespace() {
    while (!isEof()) {
      if (Character.isWhitespace(lookAhead(0))) {
        consume();
      } else if (lookAhead(0) == '\\' && Character.isWhitespace(lookAhead(1))) {
        consume();
        consume();
      } else {
        break;
      }
    }
  }

  private void consumeLine() {
    // unix -->    \n
    // windows --> \r\n
    // mac -->     \r

    char c = lookAhead(0);
    while (!isEof()) {
      // New line preceded by \ is line continuation. Keep going.
      if ((c == '\n' || c == '\r') && lookAhead(-1) != '\\') {
        consume();
        break;
      }
      consume();
      c = lookAhead(0);
    }
    // \r followed by \n. Skip the \n as well
    if (c == '\r' && lookAhead(0) == '\n') {
      consume();
    }
  }

  private char lookAhead(int amount) {
    int newCursor = cursor + amount;
    if (newCursor < 0 || newCursor >= end) {
      return EOF;
    }
    return input[newCursor];
  }

  public static void main(String[] args) throws IOException {
    String input = "" + "set x 99\n" + "sp {test\n" + "set p hello\n" + "";

    TclParser parser = new TclParser();
    parser.setInput(input.toCharArray(), 0, input.length());
    TclAstNode root = parser.parse();
    for (TclParserError e : parser.getErrors()) {
      System.err.println(e);
    }
    root.printTree(System.err, parser.getInput(), 0);
  }
}

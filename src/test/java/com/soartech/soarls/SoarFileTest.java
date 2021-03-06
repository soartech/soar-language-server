package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import com.soartech.soarls.tcl.TclAstNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.Test;

public class SoarFileTest extends LanguageServerTestFixture {
  final SoarFile file;

  static Position endPosition = new Position(24, 0);

  public SoarFileTest() throws Exception {
    super("file");

    URI uri = workspaceRoot.resolve("test.soar");
    String content = new String(Files.readAllBytes(Paths.get(uri)));
    this.file = new SoarFile(uri, content);

    this.file.ast.printTree(System.out, this.file.contents.toCharArray(), 0);
  }

  @Test
  public void applyChangeRemoveLine() {
    int lineLength = file.line(0).length();
    SoarFile newFile =
        this.file.withChange(
            new TextDocumentContentChangeEvent(
                new Range(new Position(0, 0), new Position(1, 0)), lineLength, ""));

    assertEquals(newFile.line(0), "\n");
  }

  /** When we remove the first two lines, then the first tcl node should no longer be a comment. */
  @Test
  public void applyChangeRemoveComment() {
    int lineLength = file.line(0).length() + file.line(1).length();
    SoarFile newFile =
        this.file.withChange(
            new TextDocumentContentChangeEvent(
                new Range(new Position(0, 0), new Position(2, 0)), lineLength, ""));

    // This is the 'sp' token of the first production.
    TclAstNode node = newFile.tclNode(new Position(0, 0));
    assertEquals(node.getType(), TclAstNode.NORMAL_WORD);
  }

  @Test
  public void applyChangeAddCharacter() {
    // Insert another '#' at the beginning of the line.
    SoarFile newFile =
        this.file.withChange(
            new TextDocumentContentChangeEvent(
                new Range(new Position(0, 0), new Position(0, 0)), 0, "#"));

    assertEquals(newFile.line(0), "## comment\n");
  }

  @Test
  public void beginningOffset() {
    int offset = file.offset(new Position(0, 0));
    assertEquals(offset, 0);
  }

  @Test
  public void beginningPosition() {
    Position position = file.position(0);
    assertEquals(position.getLine(), 0);
    assertEquals(position.getCharacter(), 0);
  }

  @Test
  public void offsetsRoundtrip() {
    for (int offset = 0; offset != file.contents.length(); ++offset) {
      int offsetRoundtrip = file.offset(file.position(offset));
      assertEquals(offset, offsetRoundtrip);
    }
  }

  @Test
  public void lines() {
    assertEquals(file.line(0), "# comment\n");
    assertEquals(file.line(1), "\n");
    assertEquals(file.line(2), "sp \"propose*init\n");
    // ...
    assertEquals(file.line(7), "\"\n");
  }

  @Test
  public void lastLine() {
    int offset = file.offset(endPosition);
    System.out.println("endPosition: " + endPosition);
    System.out.println("offset: " + offset);
    assert (offset > 0);
  }

  @Test
  public void lastPosition() {
    Position position = file.position(file.contents.length());
    assertEquals(position.getLine(), endPosition.getLine());
    assertEquals(position.getCharacter(), endPosition.getCharacter());
  }

  @Test
  public void tclParseSucceeded() {
    assertNotNull(file.ast);
    assertTrue(file.diagnostics.isEmpty());
  }

  @Test
  public void tclNodeStart() {
    TclAstNode node = file.tclNode(0);
    assertEquals(node.getType(), TclAstNode.COMMENT);
  }

  @Test
  public void tclNodeComment() {
    TclAstNode node = file.tclNode(new Position(0, 4));
    assertEquals(node.getType(), TclAstNode.COMMENT);
  }

  @Test
  public void tclNodeBetweenCommands() {
    // This is the blank line between comment and production.
    TclAstNode node = file.tclNode(new Position(1, 0));
    assertEquals(node.getType(), TclAstNode.ROOT);
  }

  @Test
  public void tclNodeCommand() {
    // This is the space between the "sp" and its argument.
    TclAstNode node = file.tclNode(new Position(2, 2));
    assertEquals(node.getType(), TclAstNode.COMMAND);
  }

  @Test
  public void tclNodeSp() {
    // This is on the sp token.
    TclAstNode node = file.tclNode(new Position(2, 0));
    assertEquals(node.getType(), TclAstNode.NORMAL_WORD);
  }

  @Test
  public void tclNodeProductionBody() {
    // This is on the body of the produciton.
    TclAstNode node = file.tclNode(new Position(2, 3));
    assertEquals(node.getType(), TclAstNode.QUOTED_WORD);
  }

  @Test
  public void tclNodeCommandWord() {
    // This is on the opening bracket of ngs-match-top-state.
    TclAstNode node = file.tclNode(new Position(10, 4));
    assertEquals(node.getType(), TclAstNode.COMMAND_WORD);
  }

  @Test
  public void tclNodeNormalWord() {
    // This is on the ngs-match-top-state macro.
    TclAstNode node = file.tclNode(new Position(10, 8));
    assertEquals(node.getType(), TclAstNode.NORMAL_WORD);
  }

  @Test
  public void tclNodeNestedCommand() {
    // This is on the opening bracket of the nested ngs-eq macro.
    TclAstNode node = file.tclNode(new Position(11, 13));
    assertEquals(node.getType(), TclAstNode.COMMAND_WORD);
  }

  @Test
  public void tclNodeNestedCommandName() {
    // This is on the command part of the nested ngs-eq macro.
    TclAstNode node = file.tclNode(new Position(11, 16));
    assertEquals(node.getType(), TclAstNode.NORMAL_WORD);
  }

  @Test
  public void tclNodeArrow() {
    // This is on the arrow between the LHS and RHS.
    TclAstNode node = file.tclNode(new Position(12, 2));
    assertEquals(node.getType(), TclAstNode.NORMAL_WORD);
  }

  @Test
  public void tclNodeVariable() {
    // The '$' character of $NGS_YES
    TclAstNode node = file.tclNode(new Position(13, 58));
    assertEquals(node.getType(), TclAstNode.VARIABLE);
  }

  @Test
  public void tclNodeVariableName() {
    // The 'G' character in $NGS_YES
    TclAstNode node = file.tclNode(new Position(13, 60));
    assertEquals(node.getType(), TclAstNode.VARIABLE_NAME);
  }

  @Test
  public void tclCommentInString() {
    // Comment characters inside a string do not mean a real comment.
    TclAstNode node = file.tclNode(new Position(19, 40));
    assertNotEquals(node.getType(), TclAstNode.COMMENT);
  }
}

package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.lsp4j.Position;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoarFileTest extends LanguageServerTestFixture {
    final SoarFile file;

    public SoarFileTest() throws Exception {
        super("file");

        Path path = workspaceRoot.resolve("test.soar");
        String content = new String(Files.readAllBytes(path));
        this.file = new SoarFile(path.toUri().toString(), content);
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
    public void tclParseSucceeded() {
        assertNotNull(file.ast);
        assertNull(file.ast.getError());
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
    public void tclNodeNormalWord() {
        // This is on the ngs-match macro.
        TclAstNode node = file.tclNode(new Position(10, 8));
        assertEquals(node.getType(), TclAstNode.COMMAND_WORD);
    }

    @Test
    public void tclNodeNestedCommand() {
        // This is on the nested ngs-eq macro.
        TclAstNode node = file.tclNode(new Position(11, 16));
        assertEquals(node.getType(), TclAstNode.COMMAND_WORD);
    }

    @Test
    public void tclNodeArrow() {
        // This is on the arrow between the LHS and RHS.
        TclAstNode node = file.tclNode(new Position(12, 2));
        assertEquals(node.getType(), TclAstNode.QUOTED_WORD);
    }
}

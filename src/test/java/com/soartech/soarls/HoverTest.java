package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for textDocument/hover request.
 *
 * Hovering over Tcl variables should show their value, while hovering
 * over a proc should show its doc comment.
 */
public class HoverTest extends SingleFileTestFixture {
    public HoverTest() throws Exception {
        super("hover", "test.soar");
    }

    @Test
    public void hoverVariableValue() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 17, 45);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        MarkupContent contents = hover.getContents().getRight();
        assertEquals(contents.getKind(), MarkupKind.PLAINTEXT);
        assertEquals(contents.getValue(), "*YES*");
    }

    @Test
    public void hoverVariableRange() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 17, 45);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        assertRange(hover, 16, 43, 16, 50);
    }

    @org.junit.Ignore
    @Test
    public void hoverProcDocs() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 15, 10);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        MarkupContent contents = hover.getContents().getRight();
        assertEquals(contents.getKind(), MarkupKind.PLAINTEXT);
        assertEquals(contents.getValue(), "*YES*");

        fail("unimplemented");
    }

    @org.junit.Ignore
    @Test
    public void hoverProcRange() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 15, 10);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        assertRange(hover, 14, 4, 14, 32);
    }

    /** Test that the range matches the given parameters. */
    void assertRange(Hover hover, int startLine, int startCharacter, int endLine, int endCharacter) {
        Range range = hover.getRange();
        assertEquals(range.getStart().getLine(), startLine);
        assertEquals(range.getStart().getCharacter(), startCharacter);
        assertEquals(range.getEnd().getLine(), endLine);
        assertEquals(range.getEnd().getCharacter(), endCharacter);
    }
}

package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
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
    public void checkCapabilities() {
        assertEquals(capabilities.getHoverProvider(), true);
    }

    @Test
    public void hoverVariableValue() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 16, 44);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        MarkupContent contents = hover.getContents().getRight();
        assertEquals(contents.getKind(), MarkupKind.PLAINTEXT);
        assertEquals(contents.getValue(), "*YES*");
    }

    @Test
    public void hoverVariableRange() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 16, 44);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        assertRange(hover, 16, 43, 16, 51);
    }

    @Test
    public void hoverProcWithArguments() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 14, 9);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();

        MarkupContent contents = hover.getContents().getRight();
        assertEquals(contents.getKind(), MarkupKind.PLAINTEXT);
        assertEquals(contents.getValue(), "ngs-bind id args");
    }

    @org.junit.Ignore
    @Test
    public void hoverProcDocs() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 14, 9);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        MarkupContent contents = hover.getContents().getRight();
        assertEquals(contents.getKind(), MarkupKind.PLAINTEXT);
        assertEquals(contents.getValue(), "*YES*");

        fail("unimplemented");
    }

    /** The hover range covers the entire invocation of the procedure,
     * including its arguments. */
    @org.junit.Ignore
    @Test
    public void hoverProcRange() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 14, 9);
        Hover hover = languageServer.getTextDocumentService().hover(params).get();
        assertRange(hover, 14, 4, 14, 32);
    }

    /** Test that the range matches the given parameters. */
    void assertRange(Hover hover, int startLine, int startCharacter, int endLine, int endCharacter) {
        Range range = hover.getRange();
        Range expected = new Range(
            new Position(startLine, startCharacter),
            new Position(endLine, endCharacter));
        assertEquals(range, expected);
    }
}

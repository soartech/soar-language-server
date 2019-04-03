package com.soartech.soarls;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Tcl expansions are implementad by creating and modifying the
 * contents of a special file. We (ab)use the go to definition feature
 * as a mechanism to get the client to navigate to this file. This is
 * because such behaviour should always be initiated by a user action.
 *
 * It is possible that in the future the LSP will add a mechanism by
 * which the server can request that the client navigate to a location
 * in a document. See this issue for details:
 *
 * https://github.com/Microsoft/language-server-protocol/issues/612
 */
public class TclExpansionTest extends LanguageServerTestFixture {
    public TclExpansionTest() throws Exception {
        super("project");

        // Opening any file in the project should trigger diagnostics
        // for the entire project.
        open("load.soar");
    }

    @Test
    public void expandProduction() throws Exception {

        TextDocumentPositionParams params = textDocumentPosition("productions.soar", 8, 14);
        List<? extends Location> contents = languageServer.getTextDocumentService().definition(params).get().getLeft();

        Location location = contents.get(0);
        assertNotNull(location);

        String uri = location.getUri();
        int index = uri.lastIndexOf("/");
        assertEquals("~productions.soar", uri.substring(index + 1));

        Position start = location.getRange().getStart();
        assertEquals(0, start.getLine());
        assertEquals(0, start.getCharacter());

        Position end = location.getRange().getEnd();
        assertEquals(0, end.getLine());
        assertEquals(0, end.getCharacter());

        TextEdit edit = edits.get(uri).get(0);
        assertNotNull(edit);

        // Check that edit contains correct text at correct Range (start & end)
        Position edit_start = edit.getRange().getStart();
        assertEquals(0, edit_start.getLine());
        assertEquals(0, edit_start.getCharacter());

        Position edit_end = location.getRange().getEnd();
        assertEquals(0, edit_end.getLine());
        assertEquals(0, edit_end.getCharacter());

        // check that edit contains correct expanded tcl/ngs
        assertEquals("sp \"proc-not-defined\n" +
                "    (state <s> ^superstate nil)\n" +
                "    \n" +
                "-->\n" +
                "    (<s> ^object-exists *YES*)\n" +
                "\"", edit.getNewText().trim());
    }
}

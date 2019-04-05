package com.soartech.soarls;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.junit.Test;

import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.*;

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
public class GoToDefinitionTest extends LanguageServerTestFixture {
    public GoToDefinitionTest() throws Exception {
        super("definition");

        // Opening any file in the project should trigger diagnostics
        // for the entire project.
        open("load.soar");
    }

    List<Location> definitionsForPosition(String relativePath, int line, int character) throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(relativePath, line, character);
        return languageServer.getTextDocumentService().definition(params).get().getLeft().stream().collect(toList());
    }

    @Test
    public void tclExpansion() throws Exception {
        List<Location> contents = definitionsForPosition("productions.soar", 11, 13);

        Location location = contents.get(0);
        assertNotNull(location);

        assertEquals(resolve("~productions.soar"), location.getUri());

        Position start = location.getRange().getStart();
        assertEquals(0, start.getLine());
        assertEquals(0, start.getCharacter());

        Position end = location.getRange().getEnd();
        assertEquals(0, end.getLine());
        assertEquals(0, end.getCharacter());

        TextEdit edit = edits.get(location.getUri()).get(0);
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

    @Test
    public void sameFileDefinition() throws Exception {
        List<Location> contents = definitionsForPosition("productions.soar", 19, 13);

        Location location = contents.get(0);
        assertNotNull(location);

        assertEquals(resolve("productions.soar"), location.getUri());

        Position start = location.getRange().getStart();
        assertNotNull(start);
        assertEquals(0, start.getLine());
        assertEquals(0, start.getCharacter());

        Position end = location.getRange().getEnd();
        assertNotNull(end);
        assertEquals(2, end.getLine());
        assertEquals(1, end.getCharacter());
    }

    @Test
    public void otherFileDefinition() throws Exception {
        List<Location> contents = definitionsForPosition("productions.soar", 12, 13);

        Location location = contents.get(0);
        assertNotNull(location);

        assertEquals(resolve("micro-ngs.tcl"), location.getUri());

        Position start = location.getRange().getStart();
        assertNotNull(start);
        assertEquals(6, start.getLine());
        assertEquals(0, start.getCharacter());

        Position end = location.getRange().getEnd();
        assertNotNull(end);
        assertEquals(8, end.getLine());
        assertEquals(1, end.getCharacter());
    }

    Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
    }

    String resolve(String relativePath) {
        return workspaceRoot.resolve(relativePath).toUri().toString();
    }
}

package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.junit.Test;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.*;

/** Tests for finding references to tcl procedures and variables.
 */
public class ReferencesTest extends LanguageServerTestFixture {
    public ReferencesTest() throws Exception {
        super("project");

        // Opening any file in the project should trigger diagnostics
        // for the entire project.
        open("load.soar");
    }

    @Test
    public void checkCapabilities() {
        assertEquals(capabilities.getReferencesProvider(), true);
    }

    String resolve(String relativePath) {
        return workspaceRoot.resolve(relativePath).toUri().toString();
    }

    List<Location> referencesForPoint(String relativePath, int line, int character) throws Exception {
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(fileId(relativePath));
        params.setPosition(new Position(line, character));
        params.setContext(new ReferenceContext(false));

        List<Location> references = languageServer.getTextDocumentService().references(params).get().stream().collect(toList());
        System.out.println(references);
        return references;
    }

    @Test
    @org.junit.Ignore
    public void referencesToVariable() throws Exception {
        // set NGS_YES
        List<Location> references = referencesForPoint("micro-ngs.tcl", 2, 4);
        assertReference(references, "productions.soar", range(3, 40, 3, 47));
        assertReference(references, "productions.soar", range(11, 44, 11, 51));
    }

    @Test
    public void referencesToProcedure() throws Exception {
        // proc ngs-match-top-state
        List<Location> references = referencesForPoint("micro-ngs.tcl", 6, 5);
        assertReference(references, "productions.soar", range(1, 4, 1, 29));
        assertReference(references, "productions.soar", range(8, 4, 8, 29));
    }

    Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
    }

    /** Assert that the list of locations includes the given URI and range. */
    void assertReference(List<Location> locations, String relativePath, Range range) {
        String uri = resolve(relativePath);
        boolean found = locations
            .stream()
            .filter(l -> l.getUri().equals(uri))
            .filter(l -> l.getRange().equals(range))
            .findAny()
            .isPresent();
        assertTrue(found);
    }
}
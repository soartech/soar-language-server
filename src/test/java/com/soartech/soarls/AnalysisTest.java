package com.soartech.soarls;

import com.soartech.soarls.FileAnalysis;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.tcl.TclAstNode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.Test;
import static org.junit.Assert.*;

/** These tests are for checking the internal state of the language
 * server as it analyses a workspace.
 *
 * Black box integration tests, which test only the language server
 * API, are in the ProjectTest class.
 */
public class AnalysisTest extends LanguageServerTestFixture {
    public AnalysisTest() throws Exception {
        super("project");
        open("load.soar");
    }

    String resolve(String relativePath) {
        return workspaceRoot.resolve(relativePath).toUri().toString();
    }

    /** Retrieve the analysis for the file with the given relative
     * path. We implement this here instead of inside the language
     * server test fixture because we generally don't want to expose
     * these implementation details in other tests.
     */
    FileAnalysis analysis(String relativePath) {
        SoarDocumentService docs = (SoarDocumentService) languageServer.getTextDocumentService();
        return docs.getAnalysis(resolve("load.soar")).files.get(resolve(relativePath));
    }

    SoarFile file(String uri) {
        SoarDocumentService docs = (SoarDocumentService) languageServer.getTextDocumentService();
        return docs.getFile(uri);
    }

    @Test
    public void performsAnalysis() {
        FileAnalysis analysis = analysis("load.soar");
        assertNotNull(analysis);
    }

    @Test
    public void analysesSourcedFiles() {
        assertNotNull(analysis("micro-ngs.tcl"));
        assertNotNull(analysis("productions.soar"));
        // I'm not sure whether we should create analysis objects for files that don't exist.
        // assertNotNull(analysis("missing-file.soar"));
    }

    @Test
    public void detectSourcedFiles() {
        FileAnalysis analysis = analysis("load.soar");

        assertEquals(analysis.filesSourced.get(0), resolve("micro-ngs.tcl"));
        assertEquals(analysis.filesSourced.get(1), resolve("productions.soar"));
        assertEquals(analysis.filesSourced.get(2), resolve("missing-file.soar"));
    }

    @Test
    public void leafFileSourcesNothing() {
        FileAnalysis analysis = analysis("micro-ngs.tcl");

        assertNotNull(analysis);
        assert(analysis.filesSourced.isEmpty());
    }

    @Test
    public void loadFileHasNoProductions() {
        FileAnalysis analysis = analysis("load.soar");

        assertNotNull(analysis.productions);
        assert(analysis.productions.isEmpty());
    }

    @Test
    public void detectsProductions() {
        FileAnalysis analysis = analysis("productions.soar");

        System.out.println(analysis.productions);

        assertNotNull(analysis.productions);
        assertProduction(analysis, "elaborate*top-state", range(0, 0, 4, 1));
        assertProduction(analysis, "proc-not-defined", range(7, 0, 12, 1));
    }

    @Test
    public void detectsProcedures() {
        FileAnalysis analysis = analysis("micro-ngs.tcl");

        assertNotNull(analysis.procedureDefinitions);
        assertProcedure(analysis, "ngs-match-top-state", range(5, 0, 7, 1));
        assertProcedure(analysis, "ngs-create-attribute", range(9, 0, 11, 1));
        assertProcedure(analysis, "ngs-bind", range(13, 0, 15, 1));
    }

    @org.junit.Ignore
    @Test
    public void detectsProcedureCalls() {
        FileAnalysis analysis = analysis("productions.soar");

        assertNotNull(analysis.procedureCalls);
        assertCall(analysis, 1, 5, "ngs-match-top-state");
        assertCall(analysis, 3, 5, "ngs-create-attribute");
    }

    /** Assert that a file contains the given production. */
    void assertProduction(FileAnalysis file, String name, Range range) {
        Production production = file.productions
            .stream()
            .filter(p -> p.name.equals(name))
            .findAny()
            .orElse(null);

        assertNotNull(production);
        assertEquals(production.name, name);
        assertEquals(production.location.getUri(), file.uri);
        assertEquals(production.location.getRange(), range);
    }

    /** Assert that a file contains the given procedure definition. */
    void assertProcedure(FileAnalysis file, String name, Range range) {
        ProcedureDefinition proc = file.procedureDefinitions
            .stream()
            .filter(p -> p.name.equals(name))
            .findAny()
            .orElse(null);

        assertNotNull(proc);
        assertEquals(proc.name, name);
        assertEquals(proc.location.getUri(), file.uri);
        assertEquals(proc.location.getRange(), range);
    }

    /** Assert that a Tcl procedure is called at the given position. */
    void assertCall(FileAnalysis analysis, int line, int character, String procedureName) {
        SoarFile file = file(analysis.uri);
        TclAstNode node = file.tclNode(new Position(line, character));
        ProcedureCall call = analysis.procedureCalls.get(node);
        assertNotNull(call);
        assertEquals(call.definition.name, procedureName);
    }

    Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter),
                         new Position(endLine, endCharacter));
    }
}

package com.soartech.soarls;

import java.util.List;
import java.util.Optional;

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

    /** Retrieve document service as its concrete type. We do this
     * here instead of inside the language server test fixture because
     * other tests generally shouldn't be testing internal state.
     */
    SoarDocumentService documentService() {
        return (SoarDocumentService) languageServer.getTextDocumentService();
    }

    /** Retrieve the analysis for the entire project, assuming
     * load.soar as the entry point.
     */
    ProjectAnalysis projectAnalysis() {
        return documentService().getAnalysis(resolve("load.soar"));
    }

    /** Retrieve the analysis for the file with the given relative
     * path.
     */
    FileAnalysis fileAnalysis(String relativePath) {
        return projectAnalysis().files.get(resolve(relativePath));
    }

    SoarFile file(String uri) {
        return documentService().getFile(uri);
    }

    @Test
    public void performsAnalysis() {
        FileAnalysis analysis = fileAnalysis("load.soar");
        assertNotNull(analysis);
    }

    @Test
    public void analysesSourcedFiles() {
        assertNotNull(fileAnalysis("micro-ngs.tcl"));
        assertNotNull(fileAnalysis("productions.soar"));
        // I'm not sure whether we should create analysis objects for files that don't exist.
        // assertNotNull(fileAnalysis("missing-file.soar"));
    }

    @Test
    public void detectSourcedFiles() {
        FileAnalysis analysis = fileAnalysis("load.soar");

        assertEquals(analysis.filesSourced.get(0), resolve("micro-ngs.tcl"));
        assertEquals(analysis.filesSourced.get(1), resolve("productions.soar"));
        assertEquals(analysis.filesSourced.get(2), resolve("missing-file.soar"));
    }

    @Test
    public void leafFileSourcesNothing() {
        FileAnalysis analysis = fileAnalysis("micro-ngs.tcl");

        assertNotNull(analysis);
        assert(analysis.filesSourced.isEmpty());
    }

    @Test
    public void loadFileHasNoProductions() {
        FileAnalysis analysis = fileAnalysis("load.soar");

        assertNotNull(analysis.productions);
        assert(analysis.productions.isEmpty());
    }

    @Test
    public void detectsProductions() {
        FileAnalysis analysis = fileAnalysis("productions.soar");

        System.out.println(analysis.productions);

        assertNotNull(analysis.productions);
        assertProduction(analysis, "elaborate*top-state", range(0, 0, 4, 1));
        assertProduction(analysis, "proc-not-defined", range(7, 0, 12, 1));
    }

    /** This is testing the procedures that are defined in a single
     * file. It is not looking at procedures for the whole project. */
    @Test
    public void detectsProcedures() {
        FileAnalysis analysis = fileAnalysis("micro-ngs.tcl");

        assertNotNull(analysis.procedureDefinitions);
        assertProcedure(analysis, "ngs-match-top-state", range(6, 0, 8, 1));
        assertProcedure(analysis, "ngs-create-attribute", range(12, 0, 14, 1));
        assertProcedure(analysis, "ngs-bind", range(18, 0, 20, 1));
    }

    /** This is similar to the detectsProcedures test, but it is
     * starting at the project analysis. */
    @Test
    public void procedureDefinitionAstNodes() {
        ProjectAnalysis analysis = projectAnalysis();
        ProcedureDefinition def = analysis.procedureDefinitions.get("ngs-match-top-state");
        assertEquals(def.location.getUri(), resolve("micro-ngs.tcl"));
        assertEquals(def.location.getRange(), range(6, 0, 8, 1));
    }

    @Test
    public void procedureDefinitionComments() {
        ProjectAnalysis analysis = projectAnalysis();
        assertProcComment(analysis, "ngs-match-top-state", Optional.of("# This is the actual implementation"));
        assertProcComment(analysis, "ngs-create-attribute", Optional.empty());
        assertProcComment(analysis, "ngs-bind", Optional.of("# The actual implementation of ngs-bind"));
    }

    @Test
    public void collectProjectWideProcedureDefinitions() {
        ProjectAnalysis analysis = projectAnalysis();
        assertNotNull(analysis.procedureDefinitions.get("ngs-match-top-state"));
        assertNotNull(analysis.procedureDefinitions.get("ngs-bind"));
        assertNotNull(analysis.procedureDefinitions.get("ngs-create-attribute"));
    }

    @Test
    public void detectsProcedureCalls() {
        FileAnalysis analysis = fileAnalysis("productions.soar");

        assertNotNull(analysis.procedureCalls);
        assertCall(analysis, 1, 5, "ngs-match-top-state");
        assertCall(analysis, 3, 5, "ngs-create-attribute");
    }

    @Test
    public void variableDefinitions() {
        ProjectAnalysis analysis = projectAnalysis();
        assertVariable(analysis, "NGS_YES", "*YES*", "micro-ngs.tcl");
        assertVariable(analysis, "NGS_NO", "*NO*", "micro-ngs.tcl");
    }

    @Test
    public void variableRetrievalsInFileAnalysis() {
        FileAnalysis analysis = fileAnalysis("productions.soar");
        assertFalse(analysis.variableRetrievals.isEmpty());
    }

    @Test
    public void variableUsages() {
        ProjectAnalysis analysis = projectAnalysis();
        VariableDefinition def = analysis.variableDefinitions.get("NGS_YES");
        assertNotNull(def);
        List<VariableRetrieval> usages = analysis.variableRetrievals.get(def);
        assertNotNull(usages);
        assertEquals(usages.size(), 2);
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

    /** Assert that a procedure definition either does not have a
     * comment, or if it does, that the prefix matches. */
    void assertProcComment(ProjectAnalysis analysis, String procedureName, Optional<String> commentPrefix) {
        ProcedureDefinition procedure = analysis.procedureDefinitions.get(procedureName);
        assertNotNull(procedure);

        if (commentPrefix.isPresent()) {
            assertNotNull(procedure.commentAstNode);
        } else {
            assertNull(procedure.commentAstNode);
        }

        commentPrefix.ifPresent(prefix -> assertTrue(procedure.commentText.startsWith(prefix)));
    }

    /** Assert that a variable was defined with the given name, value,
     * and source file. */
    void assertVariable(ProjectAnalysis analysis, String name, String value, String relativePath) {
        VariableDefinition def = analysis.variableDefinitions.get(name);
        assertNotNull(def);
        assertEquals(def.name, name);
        assertEquals(def.location.getUri(), resolve(relativePath));
    }

    Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter),
                         new Position(endLine, endCharacter));
    }
}

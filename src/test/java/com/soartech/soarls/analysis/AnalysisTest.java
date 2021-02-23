package com.soartech.soarls.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.soartech.soarls.LanguageServerTestFixture;
import com.soartech.soarls.SoarDocumentService;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.tcl.TclAstNode;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

/**
 * These tests are for checking the internal state of the language server as it analyses a
 * workspace.
 *
 * <p>Black box integration tests, which test only the language server API, are in the ProjectTest
 * class. However, since those often rely on the underlying data model provided by the analysis, if
 * any of these tests are failing then it is unlikely that the language server is functioning
 * correctly. Therefore, if you are debugging a problem you should usually focus on getting these
 * tests to pass before dealing with other issues.
 */
public class AnalysisTest extends LanguageServerTestFixture {
  /** The project analysis for the entire project, assuming load.soar as the entry point. */
  final ProjectAnalysis analysis;

  public AnalysisTest() throws Exception {
    super("project");
    open("load.soar");
    this.analysis = documentService().getAnalysis(resolve("load.soar")).get();
  }

  URI resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath);
  }

  /**
   * Retrieve document service as its concrete type. We do this here instead of inside the language
   * server test fixture because other tests generally shouldn't be testing internal state.
   */
  SoarDocumentService documentService() {
    return (SoarDocumentService) languageServer.getTextDocumentService();
  }

  /** Retrieve the analysis for the file with the given relative path. */
  FileAnalysis fileAnalysis(String relativePath) {
    return analysis.files.get(resolve(relativePath));
  }

  SoarFile file(URI uri) {
    return documentService().documents.get(uri);
  }

  @Test
  public void performsAnalysis() {
    FileAnalysis fileAnalysis = fileAnalysis("load.soar");
    assertNotNull(fileAnalysis);
  }

  @Test
  public void analysesSourcedFiles() {
    assertNotNull(fileAnalysis("micro-ngs/macros.tcl"));
    assertNotNull(fileAnalysis("productions.soar"));
    // I'm not sure whether we should create analysis objects for files that don't exist.
    // assertNotNull(fileAnalysis("missing-file.soar"));
  }

  @Test
  public void detectSourcedFiles() {
    FileAnalysis analysis = fileAnalysis("load.soar");

    assertEquals(analysis.filesSourced.get(0), resolve("micro-ngs/load.soar"));
    assertEquals(analysis.filesSourced.get(1), resolve("productions.soar"));
    assertEquals(analysis.filesSourced.get(2), resolve("missing-file.soar"));
  }

  @Test
  public void leafFileSourcesNothing() {
    FileAnalysis analysis = fileAnalysis("micro-ngs/macros.tcl");

    assertNotNull(analysis);
    assert (analysis.filesSourced.isEmpty());
  }

  @Test
  public void loadFileHasNoProductions() {
    FileAnalysis analysis = fileAnalysis("load.soar");

    assertNotNull(analysis.productions);
    assert (analysis.productions.isEmpty());
  }

  @Test
  public void detectsProductions() {
    FileAnalysis analysis = fileAnalysis("productions.soar");

    System.out.println(analysis.productions);

    assertNotNull(analysis.productions);
    assertProduction(analysis, "elaborate*top-state", range(0, 0, 4, 1));
    assertProduction(analysis, "proc-not-defined", range(7, 0, 12, 1));
  }

  /**
   * This is testing the procedures that are defined in a single file. It is not looking at
   * procedures for the whole project.
   */
  @Test
  public void detectsProcedures() {
    FileAnalysis analysis = fileAnalysis("micro-ngs/macros.tcl");

    assertNotNull(analysis.procedureDefinitions);
    assertProcedure(analysis, "ngs-match-top-state", range(6, 0, 8, 1));
    assertProcedure(analysis, "ngs-create-attribute", range(12, 0, 14, 1));
    assertProcedure(analysis, "ngs-bind", range(18, 0, 20, 1));
  }

  /** This is similar to the detectsProcedures test, but it is starting at the project analysis. */
  @Test
  public void procedureDefinitionAstNodes() {
    ProcedureDefinition def = analysis.procedureDefinitions.get("ngs-match-top-state");
    assertEquals(def.location.getUri(), resolve("micro-ngs/macros.tcl").toString());
    assertEquals(def.location.getRange(), range(6, 0, 8, 1));
  }

  /**
   * Procedures are associated with the previous comment node, even if there is whitespace between
   * them. See https://github.com/soartech/soar-language-server/issues/26 for discussion.
   */
  @Test
  public void procedureDefinitionComments() {
    assertProcComment("ngs-match-top-state", Optional.of("# This is the actual implementation"));
    assertProcComment(
        "ngs-create-attribute",
        Optional.of("# This is associated with the next proc, despite the newline."));
    assertProcComment("ngs-bind", Optional.of("# The actual implementation of ngs-bind"));
  }

  @Test
  public void procedureDefinitionOptionalArguments() {
    ProcedureDefinition def = analysis.procedureDefinitions.get("ngs-match-top-state");
    assertEquals(def.arguments.size(), 2);
    assertEquals(def.arguments.get(0).name, "id");
    assertEquals(def.arguments.get(0).defaultValue, Optional.empty());
    assertEquals(def.arguments.get(1).name, "bind");
    assertEquals(def.arguments.get(1).defaultValue, Optional.of("\"\""));
  }

  @Test
  public void procedureArgumentsAcrossMultipleLines() {
    ProcedureDefinition def = analysis.procedureDefinitions.get("ngs-match-goal");
    assertNotNull(def);
    assertEquals(def.arguments.size(), 5);
    assertEquals(def.arguments.get(0).defaultValue, Optional.empty());
    assertEquals(def.arguments.get(1).defaultValue, Optional.empty());
    assertEquals(def.arguments.get(2).defaultValue, Optional.empty());
    assertEquals(def.arguments.get(3).defaultValue, Optional.of("\"\""));
    assertEquals(def.arguments.get(4).defaultValue, Optional.of("\"\""));
  }

  @Test
  public void collectProjectWideProcedureDefinitions() {
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
    assertVariable("NGS_YES", "ngs-yes-was-redefined", "productions.soar");
    assertVariable("NGS_NO", "*NO*", "micro-ngs/macros.tcl");
  }

  @Test
  public void variableRetrievalsInFileAnalysis() {
    FileAnalysis analysis = fileAnalysis("productions.soar");
    assertFalse(analysis.variableRetrievals.isEmpty());
  }

  @Test
  public void variableUsages() {
    VariableDefinition def = analysis.variableDefinitions.get("NGS_YES");
    assertNotNull(def);
    List<VariableRetrieval> usages = analysis.variableRetrievals.get(def);
    assertNotNull(usages);
    assertEquals(usages.size(), 1);
  }

  /** Assert that a file contains the given production. */
  void assertProduction(FileAnalysis file, String name, Range range) {
    Production production =
        file.productions.values().stream()
            .flatMap(List::stream)
            .filter(p -> p.name.equals(name))
            .findAny()
            .orElse(null);

    assertNotNull(production);
    assertEquals(production.name, name);
    assertEquals(production.location.getUri(), file.uri.toString());
    assertEquals(production.location.getRange(), range);
  }

  /** Assert that a file contains the given procedure definition. */
  void assertProcedure(FileAnalysis file, String name, Range range) {
    ProcedureDefinition proc =
        file.procedureDefinitions.stream().filter(p -> p.name.equals(name)).findAny().orElse(null);

    assertNotNull(proc);
    assertEquals(proc.name, name);
    assertEquals(proc.location.getUri(), file.uri.toString());
    assertEquals(proc.location.getRange(), range);
  }

  /** Assert that a Tcl procedure is called at the given position. */
  void assertCall(FileAnalysis analysis, int line, int character, String procedureName) {
    SoarFile file = file(analysis.uri);
    TclAstNode node = file.tclNode(new Position(line, character));
    ProcedureCall call = analysis.procedureCall(node).get();
    assertEquals(call.definition.get().name, procedureName);
  }

  /**
   * Assert that a procedure definition either does not have a comment, or if it does, that the
   * prefix matches.
   */
  void assertProcComment(String procedureName, Optional<String> commentPrefix) {
    ProcedureDefinition procedure = analysis.procedureDefinitions.get(procedureName);
    assertNotNull(procedure);

    assertEquals(commentPrefix.isPresent(), procedure.commentAstNode.isPresent());
    assertEquals(commentPrefix.isPresent(), procedure.commentText.isPresent());

    commentPrefix.ifPresent(prefix -> assertTrue(procedure.commentText.get().startsWith(prefix)));
  }

  /** Assert that a variable was defined with the given name, value, and source file. */
  void assertVariable(String name, String value, String relativePath) {
    VariableDefinition def = analysis.variableDefinitions.get(name);
    assertNotNull(def);
    assertEquals(def.name, name);
    assertEquals(def.value, value);
    assertEquals(def.location.getUri(), resolve(relativePath).toString());
  }
}

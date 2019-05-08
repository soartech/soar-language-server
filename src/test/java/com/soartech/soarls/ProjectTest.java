package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

/**
 * These are full-workspace integration tests that should be expressed in terms of the language
 * server API. Internal state of the language server should not be tested here. For those tests, see
 * AnalysisTest.
 */
public class ProjectTest extends LanguageServerTestFixture {
  public ProjectTest() throws Exception {
    super("project");

    waitForAnalysis("load.soar");
  }

  // Tests for load.soar

  @Test
  public void analyzesLoadFile() {
    assertNotNull(diagnosticsForFile("load.soar"));
  }

  @Test
  public void hasErrorsInLoadFile() {
    List<Diagnostic> diagnostics = diagnosticsForFile("load.soar");
    assertEquals(diagnostics.size(), 1);
  }

  @Test
  public void errorForMissingFile() {
    List<Diagnostic> diagnostics = diagnosticsForFile("load.soar");
    Diagnostic sourceError = diagnostics.get(0);
    assertEquals(sourceError.getRange(), range(6, 0, 6, 24));
    assertEquals(sourceError.getMessage(), "File not found");
  }

  // Tests for micro-ngs.tcl

  @Test
  public void analyzesTclFile() {
    assertNotNull(diagnosticsForFile("micro-ngs/macros.tcl"));
  }

  @Test
  public void noErrorsInTclFile() {
    List<Diagnostic> diagnostics = diagnosticsForFile("micro-ngs/macros.tcl");
    assert (diagnostics.isEmpty());
  }

  // Tests for productions.soar

  @Test
  public void analyzesSoarFile() {
    assertNotNull(diagnosticsForFile("productions.soar"));
  }
}

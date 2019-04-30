package com.soartech.soarls;

import static org.junit.Assert.*;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.Test;

/** Tests for diagnostics which should produce warnings, not errors. */
public class WarningTest extends SingleFileTestFixture {
  public WarningTest() throws Exception {
    super("warnings", "test.soar");
    waitForAnalysis("test.soar");
  }

  @Test
  public void noErrorsReported() {
    assertFalse(
        this.getFileDiagnostics()
            .stream()
            .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));
  }

  @Test
  public void unknownRHSFunction() {
    Diagnostic diagnostic =
        getFileDiagnostics()
            .stream()
            .filter(d -> d.getRange().equals(range(3, 0, 7, 1)))
            .findAny()
            .get();
    assertEquals(diagnostic.getSeverity(), DiagnosticSeverity.Warning);
    assertEquals(diagnostic.getMessage(), "No RHS function named 'force-learn'");
  }

  @Test
  public void duplicateProduction() {
    Diagnostic diagnostic =
        getFileDiagnostics()
            .stream()
            .filter(d -> d.getRange().equals(range(15, 0, 19, 1)))
            .findAny()
            .get();
    assertEquals(diagnostic.getSeverity(), DiagnosticSeverity.Warning);
    assertEquals(
        diagnostic.getMessage(),
        "Ignoring elaborate*duplicate because it is a duplicate of elaborate*original");
  }
}

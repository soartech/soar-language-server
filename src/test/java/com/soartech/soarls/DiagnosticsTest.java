package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

/**
 * Tests for diagnostics from invalid Soar code.
 *
 * <p>The LanguageServerTestFixture (from which this test class inherits) stores the most recent
 * PublishDiagnosticsParams that were sent from the server. Thus, this class is for integration
 * tests that are expressed in terms of the LSP API.
 *
 * <p>These tests are for Soar productions where any Tcl is successfully interpreted, but the
 * resulting Soar code is invalid.
 */
public class DiagnosticsTest extends SingleFileTestFixture {
  public DiagnosticsTest() throws Exception {
    super("diagnostics", "test.soar");
    waitForAnalysis("test.soar");
  }

  @Test
  public void diagnosticsReported() {
    assertNotNull(this.getFileDiagnostics());
    assertEquals(11, this.getFileDiagnostics().size());
  }

  @Test
  public void missingArrow() {
    boolean diagnosticFound = false;
    for (Diagnostic diagnostic : getFileDiagnostics()) {
      if (diagnostic
          .getMessage()
          .contains("In production 'missing-arrow', expected --> in production")) {
        assertEquals(diagnostic.getRange(), range(4, 0, 7, 1));
        diagnosticFound = true;
        break;
      }
    }
    assertTrue(diagnosticFound);
  }

  @Test
  public void missingStateKeyword() {
    boolean diagnosticFound = false;
    for (Diagnostic diagnostic : getFileDiagnostics()) {
      if (diagnostic
          .getMessage()
          .equals(
              "Warning: On the LHS of production missing-state-keyword, identifier <s> is not connected to any goal or impasse.")) {
        assertEquals(diagnostic.getRange(), range(9, 0, 13, 1));
        diagnosticFound = true;
        break;
      }
    }
    assertTrue(diagnosticFound);
  }

  @Test
  public void unboundRhsVariable() {
    boolean diagnosticFound = false;
    for (Diagnostic diagnostic : getFileDiagnostics()) {
      if (diagnostic
          .getMessage()
          .contains("Error: production unbound-rhs-variable has a bad RHS--")) {
        assertEquals(diagnostic.getRange(), range(15, 0, 19, 1));
        diagnosticFound = true;
        break;
      }
    }
    assertTrue(diagnosticFound);
  }

  @Test
  public void missingCaret() {
    boolean diagnosticFound = false;
    for (Diagnostic diagnostic : getFileDiagnostics()) {
      if (diagnostic
          .getMessage()
          .contains("In production 'missing-caret', expected ^ followed by attribute")) {
        diagnosticFound = true;
        assertEquals(diagnostic.getRange(), range(21, 0, 25, 1));
        break;
      }
    }
    assertTrue(diagnosticFound);
  }

  @Test
  public void missingProductionQuote() {
    // check for diagnostic created by parser at last quote due to mismatched quotes
    Diagnostic parserDiagnostic =
        getFileDiagnostics()
            .stream()
            .filter(d -> d.getRange().equals(range(31, 0, 31, 1)))
            .findAny()
            .get();
    assertEquals(parserDiagnostic.getMessage(), "Missing closing quote");

    Diagnostic sourcedDiagnostic =
        getFileDiagnostics()
            .stream()
            .filter(d -> d.getRange().equals(range(27, 0, 27, 16)))
            .findAny()
            .get();
    assertEquals(
        sourcedDiagnostic.getMessage(),
        "In production 'missing-quote', expected ( to begin condition element\n\n(Ignoring production missing-quote)");
  }

  @Test
  public void invalidCommands() {
    assertInvalidCommand("(state", range(28, 3, 28, 29));
    assertInvalidCommand("-->", range(29, 0, 29, 3));
    assertInvalidCommand("(<s>", range(30, 3, 30, 20));
    assertInvalidCommand("undefined-proc", range(33, 0, 33, 24));
  }

  /** Assert that there is an invalid command at the given range. */
  void assertInvalidCommand(String name, Range range) {
    Diagnostic diagnostic =
        getFileDiagnostics().stream().filter(d -> d.getRange().equals(range)).findAny().get();
    assertEquals(diagnostic.getMessage(), "invalid command name \"" + name + "\"");
  }
}

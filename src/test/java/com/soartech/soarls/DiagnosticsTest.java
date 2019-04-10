package com.soartech.soarls;

import static org.junit.Assert.*;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.junit.Test;

/**
 * Tests for diagnostics from invalid Soar code.
 *
 * <p>The LanguageServerTestFixture (from which this test class inherits) stores the most recent
 * PublishDiagnosticsParams that were sent from the server. Thus, this class is for integration
 * tests that expressed in terms of the LSP API.
 *
 * <p>These tests are for Soar productions where any Tcl is successfully interpreted, but the
 * resulting Soar code is invalid.
 */
public class DiagnosticsTest extends SingleFileTestFixture {
  public DiagnosticsTest() throws Exception {
    super("diagnostics", "test.soar");
  }

  @Test
  public void diagnosticsReported() {
    assertNotNull(this.getFileDiagnostics());
    assertEquals(6, this.getFileDiagnostics().size());
  }

  @Test
  public void missingArrow() {
    boolean diagnosticFound = false;
    for (Diagnostic diagnostic : getFileDiagnostics()) {
      if (diagnostic
          .getMessage()
          .contains("In production 'missing-arrow', expected --> in production")) {
        Position start = diagnostic.getRange().getStart();
        Position end = diagnostic.getRange().getEnd();

        assertEquals(4, start.getLine());
        assertEquals(4, start.getCharacter());
        assertEquals(7, end.getLine());
        assertEquals(0, end.getCharacter());
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
        Position start = diagnostic.getRange().getStart();
        Position end = diagnostic.getRange().getEnd();
        assertEquals(9, start.getLine());
        assertEquals(4, start.getCharacter());
        assertEquals(13, end.getLine());
        assertEquals(0, end.getCharacter());
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
        Position start = diagnostic.getRange().getStart();
        Position end = diagnostic.getRange().getEnd();
        assertEquals(15, start.getLine());
        assertEquals(4, start.getCharacter());
        assertEquals(19, end.getLine());
        assertEquals(0, end.getCharacter());
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
        Position start = diagnostic.getRange().getStart();
        Position end = diagnostic.getRange().getEnd();
        assertEquals(21, start.getLine());
        assertEquals(4, start.getCharacter());
        assertEquals(25, end.getLine());
        assertEquals(0, end.getCharacter());
        diagnosticFound = true;
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
            .filter(d -> d.getRange().equals(range(27, 3, 27, 16)))
            .findAny()
            .get();
    assertEquals(
        sourcedDiagnostic.getMessage(),
        "In production 'missing-quote', expected ( to begin condition element\n\n(Ignoring production missing-quote)");
  }
}

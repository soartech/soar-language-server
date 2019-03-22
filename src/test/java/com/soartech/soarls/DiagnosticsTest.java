package com.soartech.soarls;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for diagnostics from invalid Soar code.
 *
 * The LanguageServerTestFixture (from which this test class inherits)
 * stores the most recent PublishDiagnosticsParams that were sent from
 * the server. Thus, this class is for integration tests that
 * expressed in terms of the LSP API.
 *
 * These tests are for Soar productions where any Tcl is successfully
 * interpreted, but the resulting Soar code is invalid.
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
            if (diagnostic.getMessage().contains("In production 'missing-arrow', expected --> in production")) {
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
            if (diagnostic.getMessage().equals("Warning: On the LHS of production missing-state-keyword, identifier <s> is not connected to any goal or impasse.")) {
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
            if (diagnostic.getMessage().contains("Error: production unbound-rhs-variable has a bad RHS--")) {
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
            if (diagnostic.getMessage().contains("In production 'missing-caret', expected ^ followed by attribute")) {
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
        boolean parserDiagnosticFound = false;
        for (Diagnostic diagnostic : getFileDiagnostics()) {
            if (diagnostic.getMessage().equals("Unexpected end of input. Unmatched quote.")) {
                Position start = diagnostic.getRange().getStart();
                Position end = diagnostic.getRange().getEnd();
                assertEquals(31, start.getLine());
                assertEquals(0, start.getCharacter());
                assertEquals(31, end.getLine());
                assertEquals(1, end.getCharacter());
                parserDiagnosticFound = true;
                break;
            }
        }
        assertTrue(parserDiagnosticFound);

        boolean sourcedDiagnosticFound = false;
        for (Diagnostic diagnostic : getFileDiagnostics()) {
            if (diagnostic.getMessage().contains("In production 'missing-quote', expected ( to begin condition element")) {
                Position start = diagnostic.getRange().getStart();
                Position end = diagnostic.getRange().getEnd();
                assertEquals(27, start.getLine());
                assertEquals(3, start.getCharacter());
                assertEquals(27, end.getLine());
                assertEquals(16, end.getCharacter());
                sourcedDiagnosticFound = true;
                break;
            }
        }
        assertTrue(sourcedDiagnosticFound);
    }
}

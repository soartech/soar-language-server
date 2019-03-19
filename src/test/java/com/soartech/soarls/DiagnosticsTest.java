package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
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
// TODO: Remove this ignore
@org.junit.Ignore
public class DiagnosticsTest extends SingleFileTestFixture {
    public DiagnosticsTest() throws Exception {
        super("diagnostics", "test.soar");
    }

    @Test
    public void diagnosticsReported() {
        assertNotNull(this.diagnostics);
    }

    @Test
    public void missingArrow() {
        fail("unimplemented");
    }

    @Test
    public void missingStateKeyword() {
        fail("unimplemented");
    }

    @Test
    public void unboundRhsVariable() {
        fail("unimplemented");
    }

    @Test
    public void missingCaret() {
        fail("unimplemented");
    }
}

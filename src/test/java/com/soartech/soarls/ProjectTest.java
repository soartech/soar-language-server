package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;

/** These are full-workspace integration tests that should be
 * expressed in terms of the language server API. Internal state of
 * the language server should not be tested here. For those tests, see
 * AnalysisTest.
 */
public class ProjectTest extends LanguageServerTestFixture {
    public ProjectTest() throws Exception {
        super("project");

        // Opening any file in the project should trigger diagnostics
        // for the entire project.
        open("productions.soar");
    }

    // Tests for load.soar

    @Test
    public void analyzesLoadFile() {
        assertNotNull(diagnosticsForFile("load.soar"));
    }

    @Test
    @Ignore
    public void hasErrorsInLoadFile() {
        List<Diagnostic> diagnostics = diagnosticsForFile("load.soar");
        assert(!diagnostics.isEmpty());
    }

    @Test
    @Ignore
    public void errorForMissingFile() {
        List<Diagnostic> diagnostics = diagnosticsForFile("load.soar");
        fail("unimplemented");
    }

    // Tests for micro-ngs.tcl

    @Test
    public void analyzesTclFile() {
        assertNotNull(diagnosticsForFile("micro-ngs/macros.tcl"));
    }

    @Test
    public void noErrorsInTclFile() {
        List<Diagnostic> diagnostics = diagnosticsForFile("micro-ngs/macros.tcl");
        assert(diagnostics.isEmpty());
    }

    // Tests for productions.soar

    @Test
    public void analyzesSoarFile() {
        assertNotNull(diagnosticsForFile("productions.soar"));
    }
}

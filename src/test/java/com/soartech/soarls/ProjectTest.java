package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.Ignore
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
    public void hasErrorsInLoadFile() {
        List<Diagnostic> diagnostics = diagnosticsForFile("load.soar");
        assert(!diagnostics.isEmpty());
    }

    @Test
    public void errorForMissingFile() {
        List<Diagnostic> diagnostics = diagnosticsForFile("load.soar");
        fail("unimplemented");
    }

    // Tests for micro-ngs.tcl

    @Test
    public void analyzesTclFile() {
        assertNotNull(diagnosticsForFile("micro-ngs.tcl"));
    }

    @Test
    public void noErrorsInTclFile() {
        List<Diagnostic> diagnostics = diagnosticsForFile("micro-ngs.tcl");
        assert(diagnostics.isEmpty());
    }

    // Tests for productions.soar

    @Test
    public void analyzesSoarFile() {
        assertNotNull(diagnosticsForFile("productions.soar"));
    }
}

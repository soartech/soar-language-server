package com.soartech.soarls;

import com.soartech.soarls.FileAnalysis;
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

    /** Retrieve the analysis for the file with the given relative
     * path. We implement this here instead of inside the language
     * server test fixture because we generally don't want to expose
     * these implementation details in other tests.
     */
    FileAnalysis analysis(String relativePath) {
        SoarDocumentService docs = (SoarDocumentService) languageServer.getTextDocumentService();
        return docs.getAnalysis(resolve(relativePath));
    }

    @Test
    public void performsAnalysis() {
        FileAnalysis analysis = analysis("load.soar");
        assertNotNull(analysis);
    }

    @Test
    public void detectSourcedFiles() {
        FileAnalysis analysis = analysis("load.soar");

        assertEquals(analysis.filesSourced.get(0), resolve("micro-ngs.tcl"));
        assertEquals(analysis.filesSourced.get(1), resolve("productions.soar"));
        assertEquals(analysis.filesSourced.get(2), resolve("missing-file.soar"));
    }
}

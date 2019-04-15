package com.soartech.soarls;

import static org.junit.Assert.*;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.Test;

public class BadProjectTest extends LanguageServerTestFixture {
  public BadProjectTest() throws Exception {
    super("bad-project");
    open("test.soar");
    waitForAnalysis("test.soar");
  }

  /**
   * Even though the project is missing a manifest, when we open test.soar it should become the
   * default entry point.
   */
  @Test
  public void fileWithoutManifest() {
    List<Diagnostic> diagnostics = diagnosticsForFile("test.soar");
    assertNotNull(diagnostics);
  }

  /**
   * We normally report errors about missing files, but in this case the source command is being
   * wrapped in a Tcl exception handler. It is normal for it to fail, so we shouldn't report an
   * error.
   */
  @Test
  public void catchSourceException() {
    List<Diagnostic> diagnostics = diagnosticsForFile("test.soar");
    assertTrue(diagnostics.isEmpty());
  }
}

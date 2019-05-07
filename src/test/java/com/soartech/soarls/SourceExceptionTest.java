package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

public class SourceExceptionTest extends LanguageServerTestFixture {
  public SourceExceptionTest() throws Exception {
    super("source-exception");
    waitForAnalysis("test.soar");
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

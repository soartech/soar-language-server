package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

public class SourceFromClasspathTest extends LanguageServerTestFixture {
  public SourceFromClasspathTest() throws Exception {
    super("source-from-classpath");
  }

  /** JSoar supports sourcing files from the classpath. This project should not have any errors. */
  @Test
  public void sourceFromClasspath() throws Exception {
    waitForAnalysis("test.soar");
    List<Diagnostic> diagnostics = diagnosticsForFile("test.soar");
    assertTrue(diagnostics.isEmpty());
  }
}

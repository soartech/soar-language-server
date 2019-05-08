package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

public class BadProjectTest extends LanguageServerTestFixture {
  public BadProjectTest() throws Exception {
    super("bad-project");
  }

  /** When there is a badly formed manifest, we report diagnostics for the soarAgents.json file. */
  @Test
  public void fileWithoutManifest() {
    List<Diagnostic> diagnostics = diagnosticsForFile("soarAgents.json");
    assertNotNull(diagnostics);
    assertFalse(diagnostics.isEmpty());
  }
}

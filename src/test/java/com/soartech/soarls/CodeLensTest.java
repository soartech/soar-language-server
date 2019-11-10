package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.junit.jupiter.api.Test;

/** We use code lenses at the tops of files to indicate which agent(s) a file belongs to. */
public class CodeLensTest extends LanguageServerTestFixture {
  public CodeLensTest() throws Exception {
    super("multiple-entry-point");
  }

  @Test
  void checkCapabilities() {
    assertEquals(capabilities.getCodeLensProvider(), new CodeLensOptions());
  }

  @Test
  void agentMembership() throws Exception {
    CodeLensParams params = new CodeLensParams(fileId("common.soar"));
    List<? extends CodeLens> codeLenses =
        languageServer.getTextDocumentService().codeLens(params).get();
    CodeLens codeLens =
        codeLenses
            .stream()
            .filter(lens -> lens.getRange().equals(range(0, 0, 0, 0)))
            .findAny()
            .get();
    assertEquals(codeLens.getCommand().getTitle(), "Member of primary, secondary");
  }
}

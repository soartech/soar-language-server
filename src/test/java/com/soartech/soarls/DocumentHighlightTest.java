package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.junit.jupiter.api.Test;

/**
 * The current behaviour is to highlight an entire production/command when the cursor is anywhere
 * inside of it. This is mainly just a placeholder to test two things: - We've correctly parsed the
 * locations of commands. - The test fixture works.
 */
public class DocumentHighlightTest extends SingleFileTestFixture {
  public DocumentHighlightTest() throws Exception {
    super("file", "test.soar");
  }

  @Test
  public void highlightProduction() throws Exception {
    DocumentHighlightParams params = documentHighlightParams(file, 3, 6);
    List<? extends DocumentHighlight> contents =
        languageServer.getTextDocumentService().documentHighlight(params).get();
    assertEquals(contents.get(0).getRange(), range(2, 0, 8, 0));
  }
}

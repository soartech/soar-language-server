package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.junit.jupiter.api.Test;

/**
 * If configured, we turn commands into hyperlinks to the Tcl expansion file. This is off by
 * default.
 */
public class DocumentLinkTest extends LanguageServerTestFixture {
  public DocumentLinkTest() throws Exception {
    super("project");
  }

  DocumentLinkParams params(String relativePath) {
    return new DocumentLinkParams(fileId(relativePath));
  }

  @Test
  public void noLinksByDefault() throws Exception {
    List<DocumentLink> links =
        languageServer.getTextDocumentService().documentLink(params("productions.soar")).get();

    assertTrue(links.isEmpty());
  }

  @Test
  public void linksIfConfigured() throws Exception {
    config.hyperlinkExpansionFile = true;
    sendConfiguration();

    List<DocumentLink> links =
        languageServer.getTextDocumentService().documentLink(params("productions.soar")).get();

    assertFalse(links.isEmpty());
  }
}

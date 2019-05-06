package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.junit.jupiter.api.Test;

/**
 * Tests for textDocument/hover request.
 *
 * <p>Hovering over Tcl variables should show their value, while hovering over a proc should show
 * its signature.
 */
public class HoverTest extends SingleFileTestFixture {
  public HoverTest() throws Exception {
    super("hover", "test.soar");
    config.renderHoverVerbatim = false;
    sendConfiguration();
  }

  @Test
  public void checkCapabilities() {
    assertEquals(capabilities.getHoverProvider(), true);
  }

  @Test
  public void hoverVariableValue() throws Exception {
    TextDocumentPositionParams params = textDocumentPosition(file, 20, 44);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    MarkupContent contents = hover.getContents().getRight();
    assertEquals(contents.getKind(), MarkupKind.PLAINTEXT);
    assertEquals(contents.getValue(), "*YES*");
  }

  @Test
  public void hoverVariableRange() throws Exception {
    TextDocumentPositionParams params = textDocumentPosition(file, 20, 44);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    assertEquals(hover.getRange(), range(20, 43, 20, 51));
  }

  @Test
  public void hoverVariableTopLevelCommand() throws Exception {
    // The 'L' in '$ALPHA'
    TextDocumentPositionParams params = textDocumentPosition(file, 28, 23);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    MarkupContent contents = hover.getContents().getRight();
    assertEquals(contents.getValue(), "alpha");
  }

  /** This checks variables of the form `prefix-$variable`. */
  @Test
  public void hoverVariableConcatenatedWithWord() throws Exception {
    // The 'E' in 'prefix-$BETA'
    TextDocumentPositionParams params = textDocumentPosition(file, 29, 30);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    MarkupContent contents = hover.getContents().getRight();
    assertEquals(contents.getValue(), "beta");
  }

  /**
   * For procedure calls, the hover text shows the first line of the comment text if the client has
   * configured it this way. Also note that we show the comment despite there being a blank line
   * between the comment and the procedure definition.
   */
  @Test
  public void hoverProcSingleLine() throws Exception {
    config.fullCommentHover = false;
    sendConfiguration();

    // ngs-bind
    TextDocumentPositionParams params = textDocumentPosition(file, 18, 9);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    MarkedString string = hover.getContents().getLeft().get(0).getRight();
    assertEquals(string.getLanguage(), "raw");
    assertEquals(string.getValue(), "This is a stub for NGS bind.");
  }

  /** This proc has a comment that has extra leading spaces. They should be stripped away. */
  @Test
  public void hoverProcDocsExtraSpaces() throws Exception {
    // ngs-create-attribute
    TextDocumentPositionParams params = textDocumentPosition(file, 20, 10);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    MarkedString string = hover.getContents().getLeft().get(0).getRight();
    assertEquals(string.getLanguage(), "raw");
    assertEquals(string.getValue(), "Create an attribute.");
  }

  /** The default configuration is to show the full comment text should be shown. */
  @Test
  public void hoverProcFullComment() throws Exception {
    config.fullCommentHover = true;
    sendConfiguration();

    // ngs-bind
    TextDocumentPositionParams params = textDocumentPosition(file, 18, 9);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();

    MarkedString string = hover.getContents().getLeft().get(0).getRight();
    assertEquals(string.getLanguage(), "raw");
    assertEquals(
        string.getValue(),
        "\n"
            + "This is a stub for NGS bind.\n"
            + "\n"
            + "This extra detail in the comments should only be shown if the client\n"
            + "was configured for it.");
  }

  /**
   * This procedure does not have a comment above it; there have not yet been any comments at this
   * point in the file.
   */
  @Test
  public void hovorProcNoComment() throws Exception {
    // ngs-match-top-state
    TextDocumentPositionParams params = textDocumentPosition(file, 17, 9);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();

    MarkedString string = hover.getContents().getLeft().get(0).getRight();
    assertEquals(string.getValue(), "ngs-match-top-state");
  }

  /**
   * This procedure does not have a comment above it; there have been previous comments, but they
   * were associated with other items.
   */
  @Test
  public void hoverProcNoCommentAtEnd() throws Exception {
    // ngs-has-no-comment
    TextDocumentPositionParams params = textDocumentPosition(file, 40, 9);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();

    MarkedString string = hover.getContents().getLeft().get(0).getRight();
    assertEquals(string.getValue(), "ngs-has-no-comment");
  }

  /**
   * Proc hovers should only be shown when the cursor is on the command word. Otherwise, we end up
   * showing hover information almost all of the time.
   */
  @Test
  public void doNotShowForProcArguments() throws Exception {
    // The 'm' in 'matched'
    TextDocumentPositionParams params = textDocumentPosition(file, 20, 35);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    assertNull(hover);
  }

  /** The hover range covers the entire invocation of the procedure, including its arguments. */
  @Test
  public void hoverProcRange() throws Exception {
    TextDocumentPositionParams params = textDocumentPosition(file, 18, 9);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    assertEquals(hover.getRange(), range(18, 5, 18, 13));
  }

  @Test
  public void hoverNotApplicable() throws Exception {
    // In the middle of a comment, there is nothing to hover over.
    TextDocumentPositionParams params = textDocumentPosition(file, 8, 0);
    try {
      Hover hover = languageServer.getTextDocumentService().hover(params).get();
      assertNull(hover);
    } catch (Exception e) {
      fail("hover threw an exception");
    }
  }

  @Test
  public void hoverUnknownFile() throws Exception {
    TextDocumentPositionParams params = textDocumentPosition("notexistent.soar", 0, 0);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();
    assertNull(hover);
  }
}

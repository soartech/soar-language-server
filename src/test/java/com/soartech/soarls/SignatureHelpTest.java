package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.junit.jupiter.api.Test;

public class SignatureHelpTest extends SingleFileTestFixture {
  public SignatureHelpTest() throws Exception {
    super("signature", "test.soar");
  }

  @Test
  public void checkCapabilities() {
    SignatureHelpOptions options = capabilities.getSignatureHelpProvider();
    assertNotNull(options);
    assertFalse(options.getTriggerCharacters().isEmpty());
  }

  @Test
  public void procArguments() throws Exception {
    SignatureHelpParams params = signatureHelpParams(file, 13, 7);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-bind");
    assertParameter(info, 0, "id");
    assertParameter(info, 1, "args");
  }

  /**
   * This procedure call does not have all its arguments filled in. We still show the signature for
   * the variant with the fewest arguments.
   */
  @Test
  public void procFewerArguments() throws Exception {
    SignatureHelpParams params = signatureHelpParams(file, 23, 7);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-bind");
    assertParameter(info, 0, "id");
    assertParameter(info, 1, "args");
  }

  @Test
  public void procMoreArguments() throws Exception {
    SignatureHelpParams params = signatureHelpParams(file, 14, 7);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-stable-gte-lt");
    assertParameter(info, 0, "id");
    assertParameter(info, 1, "attr");
    assertParameter(info, 2, "low_val");
    assertParameter(info, 3, "high_val");
    assertEquals(info.getParameters().size(), 4);
  }

  @Test
  public void procOptionalArgumentsDefault() throws Exception {
    // Call ngs-gte-lt without binding the value, so the last argument gets its default value.
    SignatureHelpParams params = signatureHelpParams(file, 15, 7);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-gte-lt");
    assertParameter(info, 0, "id");
    assertParameter(info, 1, "attr");
    assertParameter(info, 2, "low_val");
    assertParameter(info, 3, "high_val");
    assertEquals(info.getParameters().size(), 4);
  }

  @Test
  public void procOptionalArgumentsFilledIn() throws Exception {
    // Call ngs-gte-lt, binding the value.
    SignatureHelpParams params = signatureHelpParams(file, 16, 7);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-gte-lt");
    assertParameter(info, 0, "id");
    assertParameter(info, 1, "attr");
    assertParameter(info, 2, "low_val");
    assertParameter(info, 3, "high_val");
    assertParameter(info, 4, "val_id");
    assertEquals(info.getParameters().size(), 5);
  }

  @Test
  public void procNotDefined() throws Exception {
    SignatureHelpParams params = signatureHelpParams(file, 18, 7);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    assert (help.getSignatures().isEmpty());
  }

  @Test
  public void cursorOnParameter() throws Exception {
    // On the 'v' in 'value', the first parameter.
    SignatureHelpParams params = signatureHelpParams(file, 16, 20);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-gte-lt");
  }

  @Test
  public void activeParameter() throws Exception {
    // On the '1', the third parameter.
    SignatureHelpParams params = signatureHelpParams(file, 16, 26);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    assertEquals(help.getActiveParameter(), 2);
  }

  /**
   * It is common that the cursor is placed somewhere after a proc that could use signature help. In
   * this case, the cursor is not actually on the AST node that we care about, so we need to make
   * sure that this case is covered.
   */
  @Test
  public void afterEndOfLine() throws Exception {
    // Cursor is on the second spaces after the end of ngs-bind: `[ngs-bind _]`
    SignatureHelpParams params = signatureHelpParams(file, 23, 14);
    SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

    SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
    assertSignature(info, "ngs-bind");
  }

  void assertSignature(SignatureInformation info, String expected) {
    assertEquals(info.getLabel().split(" ")[0], expected);
  }

  void assertParameter(SignatureInformation info, int index, String name) {
    ParameterInformation param = info.getParameters().get(index);
    assertEquals(param.getLabel().getLeft(), name);
  }
}

package com.soartech.soarls;

import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignatureHelpTest extends SingleFileTestFixture {
    public SignatureHelpTest() throws Exception {
        super("signature", "test.soar");
    }

    @Test
    public void procArguments() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 14, 8);
        SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

        SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
        assertSignature(info, "ngs-bind");
        assertParameter(info, 0, "id");
        assertParameter(info, 1, "args");
    }

    @Test
    public void procMoreArguments() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 15, 8);
        SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

        SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
        assertSignature(info, "ngs-stable-gte-lt");
        assertParameter(info, 0, "id");
        assertParameter(info, 1, "attr");
        assertParameter(info, 2, "low_val");
        assertParameter(info, 3, "high_val");
    }

    @Test
    public void procOptionalArguments() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 16, 8);
        SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

        SignatureInformation info = help.getSignatures().get(help.getActiveSignature());
        assertSignature(info, "ngs-gte-lt");
        assertParameter(info, 0, "id");
        assertParameter(info, 1, "attr");
        assertParameter(info, 2, "low_val");
        assertParameter(info, 3, "high_val");
        assertParameter(info, 4, "val_id");
    }

    @Test
    public void procNotDefined() throws Exception {
        TextDocumentPositionParams params = textDocumentPosition(file, 19, 8);
        SignatureHelp help = languageServer.getTextDocumentService().signatureHelp(params).get();

        assert(help.getSignatures().isEmpty());
    }

    void assertSignature(SignatureInformation info, String expected) {
        assertEquals(info.getLabel().split(" ")[0], expected);
    }

    void assertParameter(SignatureInformation info, int index, String name) {
        ParameterInformation param = info.getParameters().get(index);
        assertEquals(param.getLabel().getLeft(), name);
    }
}

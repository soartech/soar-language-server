package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompletionTest extends SingleFileTestFixture {
    public CompletionTest() throws Exception {
        super("completion", "test.soar");
    }

    @org.junit.Ignore
    @Test
    public void triggerOnCharacter() {
        fail("unimplemented");
    }

    @Test
    public void tclVariable() throws Exception {
        CompletionParams params = new CompletionParams(
            fileId(file),
            new Position(7, 59));
        List<CompletionItem> completions = languageServer.getTextDocumentService().completion(params).get().getLeft();

        assertCompletion(completions, "NGS_NO");
        assertCompletion(completions, "NGS_YES");
    }

    void assertCompletion(List<CompletionItem> completions, String expected) {
        boolean present = completions
            .stream()
            .filter(completion -> completion.getLabel().equals(expected))
            .findAny()
            .isPresent();

        if (!present) {
            fail("missing " + expected);
        }
    }
}

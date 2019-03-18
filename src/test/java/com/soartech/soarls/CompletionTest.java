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

    /** I'm not sure if this is a relevant thing to test, or if logic
     * surrounding trigger characters is entirely handled
     * client-side. */
    @org.junit.Ignore
    @Test
    public void triggerOnCharacter() {
        fail("unimplemented");
    }

    @Test
    public void tclVariable() throws Exception {
        CompletionParams params = new CompletionParams(
            fileId(file),
            new Position(12, 59));
        List<CompletionItem> completions = languageServer.getTextDocumentService().completion(params).get().getLeft();

        assertCompletion(completions, "NGS_NO");
        assertCompletion(completions, "NGS_YES");

        // Procs shouldn't be included in variable completions.
        assertNotCompletion(completions, "ngs-bind");
    }

    @Test
    public void tclProdedure() throws Exception {
        CompletionParams params = new CompletionParams(
            fileId(file),
            new Position(11, 10));
        List<CompletionItem> completions = languageServer.getTextDocumentService().completion(params).get().getLeft();

        assertCompletion(completions, "ngs-bind");

        // This is a proc, but it doesn't match the prefix.
        assertNotCompletion(completions, "ngs-ex");

        // These are variables.
        assertNotCompletion(completions, "NGS_NO");
        assertNotCompletion(completions, "NGS_YES");
    }

    /** Test that the completion list contains this item. */
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

    /** Test that the completion list does _not_ contain this item. */
    void assertNotCompletion(List<CompletionItem> completions, String expected) {
        boolean present = completions
            .stream()
            .filter(completion -> completion.getLabel().equals(expected))
            .findAny()
            .isPresent();

        if (present) {
            fail("contains " + expected + " but shouldn't");
        }
    }
}

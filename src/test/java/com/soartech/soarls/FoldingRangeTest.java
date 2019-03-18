package com.soartech.soarls;

import java.util.List;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for textDocument/foldingRange request.
 *
 * We aim to fold comments and productions, but there is no reason to
 * fold source statements ("imports" in the lingo of the language
 * server spec) because Soar projects tend to have dedicated load.soar
 * files, and they tend to not be very long.
 */
@org.junit.Ignore
public class FoldingRangeTest extends SingleFileTestFixture {
    final List<FoldingRange> ranges;
    
    public FoldingRangeTest() throws Exception {
        super("folding", "test.soar");

        FoldingRangeRequestParams params = new FoldingRangeRequestParams(fileId(file));
        this.ranges = languageServer.getTextDocumentService().foldingRange(params).get();
    }

    @Test
    public void foldFileComment() {
        assertRange(FoldingRangeKind.Comment, 0, 2);
    }

    @Test
    public void foldProductionComment() {
        assertRange(FoldingRangeKind.Comment, 4, 6);

        // Not sure if single line comments should be foldable or not.
        assertRange(FoldingRangeKind.Comment, 15, 15);
    }

    @Test
    public void foldProductions() {
        assertRange(FoldingRangeKind.Region, 7, 13);
        assertRange(FoldingRangeKind.Region, 16, 20);
    }

    /** Test that a range exists matching the given parameters. */
    void assertRange(String kind, int startLine, int endLine) {
        FoldingRange range = ranges
            .stream()
            .filter(r -> r.getStartLine() == startLine)
            .findFirst()
            .orElse(null);
        if (range == null) {
            fail("no range starting at line " + startLine);
        }
        assertEquals(range.getEndLine(), endLine);
        assertEquals(range.getKind(), kind);
    }
}

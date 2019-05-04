package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

/**
 * Tests for textDocument/foldingRange request.
 *
 * <p>We aim to fold comments and productions, but there is no reason to fold source statements
 * ("imports" in the lingo of the language server spec) because Soar projects tend to have dedicated
 * load.soar files, and they tend to not be very long.
 */
public class FoldingRangeTest extends SingleFileTestFixture {
  final List<FoldingRange> ranges;

  public FoldingRangeTest() throws Exception {
    super("folding", "test.soar");

    FoldingRangeRequestParams params = new FoldingRangeRequestParams(fileId(file));
    this.ranges = languageServer.getTextDocumentService().foldingRange(params).get();
  }

  @Test
  public void checkCapabilities() {
    assertEquals(capabilities.getFoldingRangeProvider(), Either.forLeft(true));
  }

  @Test
  public void foldFileComment() {
    assertRange(FoldingRangeKind.Comment, 0, 2);
  }

  @Test
  public void foldProductionComment() {
    assertRange(FoldingRangeKind.Comment, 4, 6);

    // Single line comments are not to be included.
    assertNoRange(15);
  }

  @Test
  public void foldProductions() {
    assertRange(FoldingRangeKind.Region, 7, 13);
    assertRange(FoldingRangeKind.Region, 16, 20);
  }

  /** Test that a range exists matching the given parameters. */
  void assertRange(String kind, int startLine, int endLine) {
    FoldingRange range =
        ranges.stream().filter(r -> r.getStartLine() == startLine).findFirst().orElse(null);
    if (range == null) {
      fail("no range starting at line " + startLine);
    }
    assertEquals(range.getEndLine(), endLine);
    assertEquals(range.getKind(), kind);
  }

  /** Test for the _absence_ of a range at the given line. */
  void assertNoRange(int line) {
    boolean present =
        ranges
            .stream()
            .filter(r -> r.getStartLine() <= line)
            .filter(r -> r.getEndLine() >= line)
            .findAny()
            .isPresent();

    if (present) {
      fail("there should be no range covering line " + line);
    }
  }
}

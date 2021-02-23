package com.soartech.soarls;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

/** Tests for textDocument/documentSymbol request. */
public class DocumentSymbolTest extends SingleFileTestFixture {
  public DocumentSymbolTest() throws Exception {
    super("symbols", "test.soar");
  }

  @Test
  public void checkCapabilities() {
    assertEquals(capabilities.getDocumentSymbolProvider(), true);
  }

  @Test
  public void productionsTree() throws Exception {
    DocumentSymbolParams params = new DocumentSymbolParams(fileId(file));
    List<DocumentSymbol> symbols =
        languageServer.getTextDocumentService().documentSymbol(params).get().stream()
            .map(Either::getRight)
            .collect(toList());

    List<DocumentSymbol> expected =
        Arrays.asList(
            procedure("ngs-match-top-state", range(0, 0, 0, 72)),
            procedure("generate-productions", range(2, 0, 14, 1)),
            variable("set ALPHA alpha", range(16, 0, 16, 15)),
            variable("set BETA beta", range(17, 0, 17, 13)),
            call(
                "generate-productions $ALPHA",
                range(19, 0, 19, 27),
                Arrays.asList(
                    production("generated*alpha*1", range(19, 0, 19, 27)),
                    production("generated*alpha*2", range(19, 0, 19, 27)))),
            call(
                "generate-productions $BETA",
                range(20, 0, 20, 26),
                Arrays.asList(
                    production("generated*beta*1", range(20, 0, 20, 26)),
                    production("generated*beta*2", range(20, 0, 20, 26)))),
            production("normal-production", range(22, 0, 26, 1)));

    assertArrayEquals(symbols.toArray(), expected.toArray());
  }

  // Helper functions for constructing expected output.

  // TODO: It would be great if we could classify variables as SymbolKind.Constant, but this is
  // tricky because Tcl doesn't truely have variable assignment; `set` is just a procedure call
  // which updates the Tcl interpreter's variable table as a side effect. Because of this, it's
  // difficult to differentiate between variable assignments and other procedure calls.
  DocumentSymbol variable(String name, Range range) {
    return new DocumentSymbol(name, SymbolKind.Event, range, range, null, Arrays.asList());
    // return new DocumentSymbol(name, SymbolKind.Constant, range, range, null, null);
  }

  DocumentSymbol production(String name, Range range) {
    return new DocumentSymbol(name, SymbolKind.Object, range, range, null, null);
  }

  DocumentSymbol procedure(String name, Range range) {
    return new DocumentSymbol(name, SymbolKind.Function, range, range, null, null);
  }

  DocumentSymbol call(String name, Range range, List<DocumentSymbol> children) {
    return new DocumentSymbol(name, SymbolKind.Event, range, range, null, children);
  }
}

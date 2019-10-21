package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;

/**
 * This test project attempts to source a file that has not yet been created. Inside the test
 * fixture we will create and edit the file, which should cause diagnostic errors to go away.
 *
 * <p>This addresses https://github.com/soartech/soar-language-server/issues/164. It is important
 * that a modification to the new file triggers a new analysis, even though it was never
 * successfully analyzed in the original pass.
 */
public class NewFileTest extends LanguageServerTestFixture {
  public NewFileTest() throws Exception {
    super("new-file");
    waitForAnalysis("test.soar");
  }

  @Test
  public void errorBeforeNewFile() {
    assertTrue(fileNotFoundDiagnostic().isPresent());
  }

  @Test
  public void createFileClearsError() throws Exception {
    createFile();
    editFile("");
    waitForAnalysis("test.soar");

    assertFalse(fileNotFoundDiagnostic().isPresent());
  }

  @Test
  public void addMissingProcedure() throws Exception {
    createFile();
    editFile("proc ngs-match-top-state { id } { return \"(state $id ^superstate nil)\" }");
    waitForAnalysis("test.soar");
    List<Diagnostic> diagnostics = diagnosticsForFile("test.soar");

    assertTrue(diagnostics.isEmpty());
  }

  /** Search the diagnostics list for a "file not found" error. */
  private Optional<Diagnostic> fileNotFoundDiagnostic() {
    List<Diagnostic> diagnostics = diagnosticsForFile("test.soar");
    return diagnostics.stream().filter(d -> d.getMessage().contains("File not found")).findFirst();
  }

  private void createFile() {
    URI uri = workspaceRoot.resolve("created-later.soar");
    DidOpenTextDocumentParams params =
        new DidOpenTextDocumentParams(new TextDocumentItem(uri.toString(), "soar", 0, ""));
    languageServer.getTextDocumentService().didOpen(params);
  }

  private void editFile(String contents) {
    URI uri = workspaceRoot.resolve("created-later.soar");
    DidChangeTextDocumentParams params =
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri.toString(), 0),
            Arrays.asList(new TextDocumentContentChangeEvent(range(0, 0, 0, 0), 0, contents)));
    languageServer.getTextDocumentService().didChange(params);
  }
}

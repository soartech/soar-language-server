package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;

/**
 * Completions, more so than most requests, must be as responsive as possible. Also, completions are
 * generally requested immediately after a modification to a document. Therefore, we explicitly
 * apply document changes just some of these tests, and we intentionally configure the server such
 * that it will not have enough time to re-analyse the codebase.
 */
public class CompletionTest extends SingleFileTestFixture {
  public CompletionTest() throws Exception {
    super("completion", "test.soar");

    // Allow analysis to finish, and reconfigure so that a fresh
    // analysis will not complete between modifications being applied
    // and completions being requested.
    waitForAnalysis("test.soar");
    config.debounceTime = 100;
    sendConfiguration();
  }

  /** Insert a string at the given line and column. */
  void insertText(String contents, int line, int column) {
    String uri = resolve("test.soar");
    List<TextDocumentContentChangeEvent> contentChanges =
        Arrays.asList(
            new TextDocumentContentChangeEvent(range(line, column, line, column), 0, contents));
    DidChangeTextDocumentParams params =
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, -1), contentChanges);
    languageServer.getTextDocumentService().didChange(params);
  }

  @Test
  public void tclVariable() throws Exception {
    insertText("$", 13, 58);

    CompletionParams params = new CompletionParams(fileId(file), new Position(13, 59));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    assertCompletion(completions, "NGS_NO");
    assertCompletion(completions, "NGS_YES");

    // Procs shouldn't be included in variable completions.
    assertNotCompletion(completions, "ngs-bind");
  }

  @Test
  public void tclProcedure() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(12, 10));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    assertCompletion(completions, "ngs-bind");

    // This is a proc, but it doesn't match the prefix.
    assertNotCompletion(completions, "ngs-ex");

    // These are variables.
    assertNotCompletion(completions, "NGS_NO");
    assertNotCompletion(completions, "NGS_YES");
  }

  @Test
  public void variableItemKind() throws Exception {
    insertText("$", 13, 58);

    CompletionParams params = new CompletionParams(fileId(file), new Position(13, 59));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    assertFalse(completions.isEmpty());
    for (CompletionItem completion : completions) {
      assertEquals(completion.getKind(), CompletionItemKind.Constant);
    }
  }

  @Test
  public void procedureItemKind() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(12, 10));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    for (CompletionItem completion : completions) {
      assertEquals(completion.getKind(), CompletionItemKind.Function);
    }
  }

  /**
   * Although the client shouldn't normally request information for invalid positions, if we don't
   * properly track edits then it's possible for server's model to get out of sync. This checks for
   * completions which are far beyond the end of a line.
   */
  @Test
  public void outOfBounds() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(13, 999));
    assertNull(languageServer.getTextDocumentService().completion(params).get());
  }

  /**
   * Although the LSP spec only requires that we return completion text, this means that editors
   * would have to determine exactly what to replace, which is generally based on however they
   * define word boundaries. By returning a text edit, we're explicit to the editor about what
   * exactly should be replaced, which is especially helpful given how loose Tcl's definition of a
   * word is.
   */
  @Test
  public void textEdit() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(12, 10));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    CompletionItem completion = completions.get(0);
    assertEquals(completion.getLabel(), "ngs-bind");
    assertEquals(completion.getTextEdit().getLeft().getRange(), range(12, 5, 12, 10));
  }

  /** When possible, we provide doc comments. */
  @Test
  public void documentation() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(12, 10));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    CompletionItem completion = completions.get(0);
    assertEquals(completion.getLabel(), "ngs-bind");
    assertEquals(completion.getDocumentation().getLeft(), "# Stubs for a few NGS commands\n");
  }

  /** Test that the completion list contains this item. */
  void assertCompletion(List<CompletionItem> completions, String expected) {
    boolean present =
        completions.stream()
            .filter(completion -> completion.getLabel().equals(expected))
            .findAny()
            .isPresent();

    if (!present) {
      fail("missing " + expected);
    }
  }

  @Test
  public void insertSnippet() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(12, 10));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    CompletionItem completion = completions.get(0);
    assertEquals(completion.getLabel(), "ngs-bind");
    assertEquals(completion.getInsertTextFormat(), InsertTextFormat.Snippet);
    assertEquals(completion.getTextEdit().getLeft().getRange(), range(12, 5, 12, 10));
    assertEquals(
        completion.getTextEdit().getLeft().getNewText(), "ngs-bind ${1:obj_id} ${2:args}$0");
  }

  @Test
  // @Disabled
  public void insertSnippetOptionalArgs() throws Exception {
    CompletionParams params = new CompletionParams(fileId(file), new Position(14, 20));
    List<CompletionItem> completions =
        languageServer.getTextDocumentService().completion(params).get().getLeft();

    CompletionItem completion = completions.get(0);
    assertEquals(completion.getLabel(), "ngs-create-attribute-by-operator");
    assertEquals(completion.getTextEdit().getLeft().getRange(), range(14, 5, 14, 20));
    assertEquals(
        completion.getTextEdit().getLeft().getNewText(),
        "ngs-create-attribute-by-operator ${1:state_id} ${2:parent_obj_id} ${3:attribute} ${4:value} ${5:{ replacement_behavior \"\" \\}} ${6:{ add_prefs \"=\" \\}}$0");
  }

  /** Test that the completion list does _not_ contain this item. */
  void assertNotCompletion(List<CompletionItem> completions, String expected) {
    boolean present =
        completions.stream()
            .filter(completion -> completion.getLabel().equals(expected))
            .findAny()
            .isPresent();

    if (present) {
      fail("contains " + expected + " but shouldn't");
    }
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toString();
  }
}

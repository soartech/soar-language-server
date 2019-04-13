package com.soartech.soarls;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Extend this to create a test class. Create a Soar project in
 * test/resources/<relativeWorkspaceRoot>, and this class will instantiate a langauge client in that
 * directory.
 *
 * <p>See also SingleFileTestFixture, which is a convenience for simpler tests.
 *
 * <p>This is largely borrowed from the Kotlin language server.
 */
public class LanguageServerTestFixture implements LanguageClient {
  protected final Path workspaceRoot;

  protected final LanguageServer languageServer;

  /** The capabilities that were returned from the server on initialization. */
  final ServerCapabilities capabilities;

  /** The most recent diagnostics that were sent from the server for each file. */
  Map<String, PublishDiagnosticsParams> diagnostics = new HashMap<>();

  /** A record of all edits that have been applied to each file. */
  Map<String, List<TextEdit>> edits = new HashMap<>();

  protected LanguageServerTestFixture(String relativeWorkspaceRoot) throws Exception {
    URI anchorUri = this.getClass().getResource("/Anchor.txt").toURI();
    workspaceRoot = Paths.get(anchorUri).getParent().resolve(relativeWorkspaceRoot);
    System.out.println("Creating test fixture with workspace root " + workspaceRoot);

    Server languageServer = new Server();
    InitializeParams init = new InitializeParams();
    init.setRootUri(workspaceRoot.toUri().toString());
    languageServer.connect(this);
    capabilities = languageServer.initialize(init).get().getCapabilities();
    this.languageServer = languageServer;
  }

  void waitForAnalysis(String relativePath) throws Exception {
    String uri = workspaceRoot.resolve(relativePath).toUri().toString();
    System.out.println("Waiting for analysis from " + uri);
    ((SoarDocumentService) languageServer.getTextDocumentService()).getAnalysis(uri).get();
  }

  TextDocumentIdentifier fileId(String relativePath) {
    Path file = workspaceRoot.resolve(relativePath);
    return new TextDocumentIdentifier(file.toUri().toString());
  }

  TextDocumentPositionParams textDocumentPosition(String relativePath, int line, int column) {
    TextDocumentIdentifier fileId = fileId(relativePath);
    Position position = new Position(line, column);
    return new TextDocumentPositionParams(fileId, position);
  }

  protected void open(String relativePath) throws Exception {
    Path path = workspaceRoot.resolve(relativePath);
    String content = new String(Files.readAllBytes(path));
    TextDocumentItem document = new TextDocumentItem(path.toUri().toString(), "Soar", 0, content);

    languageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(document));
  }

  List<Diagnostic> diagnosticsForFile(String relativePath) {
    Path path = workspaceRoot.resolve(relativePath);
    return diagnostics.get(path.toUri().toString()).getDiagnostics();
  }

  // Implement LanguageClient

  @Override
  public void logMessage(MessageParams message) {
    System.out.println(message.toString());
  }

  @Override
  public void publishDiagnostics(PublishDiagnosticsParams params) {
    System.out.println(params.toString());
    this.diagnostics.put(params.getUri(), params);
  }

  @Override
  public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
    System.out.println(params.toString());
    this.edits = params.getEdit().getChanges();

    return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true));
  }

  @Override
  public void showMessage(MessageParams message) {
    System.out.println(message.toString());
  }

  @Override
  public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
    System.out.println(params.toString());
    return null;
  }

  @Override
  public void telemetryEvent(Object object) {
    System.out.println(object.toString());
  }

  // Helpers

  protected static Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
    return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
  }
}

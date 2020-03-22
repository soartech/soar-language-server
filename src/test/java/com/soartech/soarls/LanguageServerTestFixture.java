package com.soartech.soarls;

import static java.util.stream.Collectors.toList;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
  protected final URI workspaceRoot;

  protected final LanguageServer languageServer;

  /** The capabilities that were returned from the server on initialization. */
  final ServerCapabilities capabilities;

  /**
   * The configuration that has been sent to the server. If a test wants to update it, modify it
   * here and then call updateConfiguration().
   */
  Configuration config = new Configuration();

  /** The most recent diagnostics that were sent from the server for each file. */
  Map<String, PublishDiagnosticsParams> diagnostics = new HashMap<>();

  /** A record of all edits that have been applied to each file. */
  Map<String, List<TextEdit>> edits = new HashMap<>();

  protected LanguageServerTestFixture(String relativeWorkspaceRoot) throws Exception {
    URL anchorUrl = this.getClass().getResource("/Anchor.txt");
    workspaceRoot =
        anchorUrl
            .toURI()
            .resolve(relativeWorkspaceRoot.replaceAll(" ", "%20").replaceAll("([^/])$", "$1/"));
    System.out.println("Creating test fixture with workspace root " + workspaceRoot);

    Server languageServer = new Server();
    languageServer.connect(this);

    InitializeParams init = new InitializeParams();
    init.setRootUri(workspaceRoot.toString());
    capabilities = languageServer.initialize(init).get().getCapabilities();

    languageServer.initialized(new InitializedParams());

    this.languageServer = languageServer;

    config.debounceTime = 0;
    sendConfiguration();
  }

  /** Update the server's configuration. */
  void sendConfiguration() {
    JsonObject settings = new JsonObject();
    settings.add("soar", new Gson().toJsonTree(config));
    DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
    languageServer.getWorkspaceService().didChangeConfiguration(params);
  }

  /** Block until the analysis starting at the given file is complete. */
  void waitForAnalysis(String relativePath) throws Exception {
    URI uri = workspaceRoot.resolve(relativePath);
    System.out.println("Waiting for analysis from " + uri);
    ((SoarDocumentService) languageServer.getTextDocumentService()).waitForAnalysis(uri);
  }

  /** Construct a text document identifier based on a relative path to the workspace root. */
  TextDocumentIdentifier fileId(String relativePath) {
    String uri = workspaceRoot.resolve(relativePath).toString();
    return new TextDocumentIdentifier(uri);
  }

  TextDocumentPositionParams textDocumentPosition(String relativePath, int line, int column) {
    TextDocumentIdentifier fileId = fileId(relativePath);
    Position position = new Position(line, column);
    return new TextDocumentPositionParams(fileId, position);
  }

  protected void open(String relativePath) throws Exception {
    URI uri = workspaceRoot.resolve(relativePath);
    String content = new String(Files.readAllBytes(Paths.get(uri)));
    TextDocumentItem document = new TextDocumentItem(uri.toString(), "Soar", 0, content);

    languageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(document));
  }

  List<Diagnostic> diagnosticsForFile(String relativePath) {
    URI uri = workspaceRoot.resolve(relativePath);
    return diagnostics.get(uri.toString()).getDiagnostics();
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

    BiConsumer<String, List<TextEdit>> applyTextEdits =
        (uri, edits) -> {
          List<TextDocumentContentChangeEvent> contentChanges =
              edits
                  .stream()
                  .map(
                      textEdit ->
                          new TextDocumentContentChangeEvent(
                              textEdit.getRange(), -1, textEdit.getNewText()))
                  .collect(toList());
          DidChangeTextDocumentParams didChangeParams =
              new DidChangeTextDocumentParams(
                  new VersionedTextDocumentIdentifier(uri, -1), contentChanges);
          languageServer.getTextDocumentService().didChange(didChangeParams);
        };

    Consumer<Either<TextDocumentEdit, ResourceOperation>> applyDocumentChange =
        change -> {
          if (change.isRight()) {
            ResourceOperation operation = change.getRight();
            if (operation instanceof CreateFile) {
              CreateFile createFile = (CreateFile) operation;
              DidOpenTextDocumentParams didOpenParams =
                  new DidOpenTextDocumentParams(
                      new TextDocumentItem(createFile.getUri(), "soar", -1, ""));
              languageServer.getTextDocumentService().didOpen(didOpenParams);
            }
          }
        };

    // Apply all text edits
    Optional.ofNullable(params.getEdit().getChanges())
        .map(changes -> changes.entrySet().stream())
        .orElseGet(Stream::empty)
        .forEach(entry -> applyTextEdits.accept(entry.getKey(), entry.getValue()));

    // Apply all document changes
    Optional.ofNullable(params.getEdit().getDocumentChanges())
        .map(List::stream)
        .orElseGet(Stream::empty)
        .forEach(applyDocumentChange);

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
  public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
    JsonObject jsonObj = (new Gson()).toJsonTree(config).getAsJsonObject();
    List<Object> config = Arrays.asList(jsonObj);
    return CompletableFuture.completedFuture(config);
  }

  @Override
  public void telemetryEvent(Object object) {
    System.out.println(object.toString());
  }

  @Override
  public CompletableFuture<Void> registerCapability(RegistrationParams params) {
    // The server will attempt to call this, but right now nothing in the test suite depends on it.
    return CompletableFuture.completedFuture(null);
  }

  // Helpers

  protected static Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
    return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
  }

  /**
   * Retrieve the file for the given path. Note that SoarFile is immutable. If you are inspecting
   * changes to a file, retrieve the file before and/or after the change; you will receive two
   * different objects.
   */
  protected SoarFile retrieveFile(String relativePath) {
    URI uri = workspaceRoot.resolve(relativePath);
    return ((SoarDocumentService) languageServer.getTextDocumentService()).documents.get(uri);
  }
}

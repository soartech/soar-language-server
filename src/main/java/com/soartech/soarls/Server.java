package com.soartech.soarls;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DocumentLinkOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server implements LanguageServer, LanguageClientAware {
  private static final Logger LOG = LoggerFactory.getLogger(LanguageServer.class);

  private final SoarDocumentService documentService = new SoarDocumentService();
  private final SoarWorkspaceService workspaceService = new SoarWorkspaceService(documentService);
  private LanguageClient client = null;

  public Server() {
    // NOTE: I'm not sure where the proper place to set this is.
    System.setProperty("jsoar.agent.interpreter", "tcl");
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspaceService;
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return documentService;
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {}

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    LOG.info("Initializing server");
    // We ensure there is a trailing slash so the root URI gets treated as a directory.
    this.workspaceService.setWorkspaceRoot(params.getRootUri().replaceAll("([^/])$", "$1/"));

    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
    // capabilities.setDocumentHighlightProvider(true);
    capabilities.setFoldingRangeProvider(true);
    capabilities.setCompletionProvider(new CompletionOptions(false, Arrays.asList("$", "[")));
    capabilities.setSignatureHelpProvider(new SignatureHelpOptions(Arrays.asList(" ")));
    capabilities.setHoverProvider(true);
    capabilities.setDefinitionProvider(true);
    capabilities.setCodeActionProvider(true);
    capabilities.setExecuteCommandProvider(new ExecuteCommandOptions());
    capabilities.setReferencesProvider(true);
    capabilities.setDocumentLinkProvider(new DocumentLinkOptions());
    capabilities.setRenameProvider(true);

    return CompletableFuture.completedFuture(new InitializeResult(capabilities));
  }

  @Override
  public void initialized(InitializedParams params) {
    // Here we register for changes to the manifest file, so that we trigger a new analysis when
    // configurations change.
    FileSystemWatcher watcher = new FileSystemWatcher("**/soarAgents.json");
    List<FileSystemWatcher> watchers = Arrays.asList(watcher);
    DidChangeWatchedFilesRegistrationOptions options =
        new DidChangeWatchedFilesRegistrationOptions(watchers);
    Registration registration =
        new Registration("changes", "workspace/didChangeWatchedFiles", options);
    List<Registration> registrations = Arrays.asList(registration);
    client.registerCapability(new RegistrationParams(registrations));
  }

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
    documentService.connect(client);
  }
}

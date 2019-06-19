package com.soartech.soarls;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DocumentLinkOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
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

  public Server() {
    // NOTE: I'm not sure where the proper place to set this is.
    System.setProperty("jsoar.agent.interpreter", "tcl");
  }

  // The following overrides are defined in the order in which they are called during the lifecycle
  // of the language server.

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspaceService;
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return documentService;
  }

  @Override
  public void connect(LanguageClient client) {
    LOG.info("connect()");
    workspaceService.connect(client);
    documentService.connect(client);
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    LOG.info("Initializing server");

    // We ensure there is a trailing slash so the root URI gets treated as a directory.
    URI workspaceRootUri = URI.create(params.getRootUri().replaceAll("([^/])$", "$1/"));
    workspaceService.setWorkspaceRoot(workspaceRootUri);
    documentService.setWorkspaceRoot(workspaceRootUri);

    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
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
    capabilities.setDocumentSymbolProvider(true);

    return CompletableFuture.completedFuture(new InitializeResult(capabilities));
  }

  @Override
  public void initialized(InitializedParams params) {
    LOG.info("initialized()");
    workspaceService.initialized();
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {}
}

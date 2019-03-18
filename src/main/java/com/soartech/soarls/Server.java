package com.soartech.soarls;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class Server implements LanguageServer, LanguageClientAware {
    private final SoarDocumentService documentService = new SoarDocumentService();
    private final WorkspaceService workspaceService = new SoarWorkspaceService();

    Server() {
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
    public void exit() {
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setDocumentHighlightProvider(true);
        capabilities.setFoldingRangeProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(false, Arrays.asList("$", "[")));

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public void connect(LanguageClient client) {
        documentService.connect(client);
    }
}

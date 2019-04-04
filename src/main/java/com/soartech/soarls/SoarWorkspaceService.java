package com.soartech.soarls;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.Gson;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.services.WorkspaceService;

class SoarWorkspaceService implements WorkspaceService {
    private final SoarDocumentService documentService;

    SoarWorkspaceService(SoarDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        Gson gson = new Gson();
        if (params.getCommand().equals("set-entry-point")) {
            String uri = gson.fromJson((JsonElement) params.getArguments().get(0), String.class);
            documentService.setEntryPoint(uri);
        }
        return CompletableFuture.completedFuture(null);
    }
}

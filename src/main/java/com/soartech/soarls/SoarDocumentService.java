package com.soartech.soarls;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.services.TextDocumentService;

class SoarDocumentService implements TextDocumentService {
    private Map<String, String> documents = new HashMap();

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        documents.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        documents.remove(params.getTextDocument().getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        for (TextDocumentContentChangeEvent change: params.getContentChanges()) {
            if (change.getRange() == null) {
                // We are using full document updates.
                documents.put(params.getTextDocument().getUri(), change.getText());
            } else {
                // We are using incremental updates.
                System.err.println("Incremental document updates are not implemented.");
            }
        }
    }
}

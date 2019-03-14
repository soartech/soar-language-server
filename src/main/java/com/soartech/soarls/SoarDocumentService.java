package com.soartech.soarls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jsoar.kernel.Agent;
import org.jsoar.kernel.SoarException;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.SoarCommands;
import static java.util.stream.Collectors.toList;

class SoarDocumentService implements TextDocumentService {
    private Map<String, SoarFile> documents = new HashMap<>();

    private LanguageClient client;

    private Set<String> variables = new HashSet();

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();
        SoarFile soarFile = new SoarFile(doc.getUri(), doc.getText());
        documents.put(doc.getUri(), soarFile);
        reportDiagnostics();
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        reportDiagnostics();
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        documents.remove(params.getTextDocument().getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        for (TextDocumentContentChangeEvent change: params.getContentChanges()) {
            // The parameters which are set depends on whether we are
            // using full or incremental updates.
            if (change.getRange() == null) {
                // We are using full document updates.
                SoarFile soarFile = new SoarFile(params.getTextDocument().getUri(), change.getText());
                documents.put(params.getTextDocument().getUri(), soarFile);
            } else {
                // We are using incremental updates.
                System.err.println("Incremental document updates are not implemented.");
            }
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        List<CompletionItem> completions = new ArrayList();
        for (String variable: variables) {
            completions.add(new CompletionItem(variable));
        }
        return CompletableFuture.completedFuture(Either.forLeft(completions));
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams params) {
        List<DocumentHighlight> highlights = new ArrayList();

        SoarFile file = documents.get(params.getTextDocument().getUri());
        if (file != null) {
            // Position position = params.getPosition();
            int offset = file.offset(params.getPosition());
            for (ParsedCommand command: file.commands) {
                int start = command.getLocation().getOffset() - 1;
                int end = start + command.getLocation().getLength();
                if (start <= offset && offset <= end) {
                    Range range = new Range(file.position(start), file.position(end + 1));
                    highlights.add(new DocumentHighlight(range));
                    break;
                }
            }
        }

        return CompletableFuture.completedFuture(highlights);
    }

    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        // List<FoldingRange> ranges = new ArrayList();
        List<FoldingRange> ranges =
            file.commands.stream()
            .map(
                c -> new FoldingRange(
                    file.position(c.getLocation().getOffset() - 1).getLine(),
                    file.position(c.getLocation().getOffset() - 1 + c.getLocation().getLength()).getLine()))
            .collect(toList());
        return CompletableFuture.completedFuture(ranges);
    }


    public void connect(LanguageClient client) {
        this.client = client;
    }

    private void reportDiagnostics() {
        // This is a stub implementation, just so we can see some
        // errors published to the client.
        Agent agent = new Agent();

        for (String uri: documents.keySet()) {
            List<Diagnostic> diagnosticList = new ArrayList<>();

            try {
                SoarCommands.source(agent.getInterpreter(), uri);
            } catch (SoarException e) {
                // Hard code a location, but include the exception
                // text.
                Diagnostic diagnostic = new Diagnostic(
                        new Range(new Position(0, 0), new Position(0, 8)),
                        "Failed to source production in this file: " + e,
                        DiagnosticSeverity.Error,
                        "soar");
                diagnosticList.add(diagnostic);
            }

            PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams(uri, diagnosticList);
            client.publishDiagnostics(diagnostics);
        }

        try {
            this.variables = new HashSet(Arrays.asList(agent.getInterpreter().eval("info vars").split(" ")));
        } catch (SoarException e) {
        }
    }
}

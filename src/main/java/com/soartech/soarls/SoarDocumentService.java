package com.soartech.soarls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
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
import org.eclipse.lsp4j.FoldingRangeKind;
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
import org.jsoar.kernel.exceptions.SoarInterpreterException;
import org.jsoar.kernel.exceptions.TclInterpreterException;
import org.jsoar.util.SourceLocation;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.SoarCommands;
import static java.util.stream.Collectors.toList;

class SoarDocumentService implements TextDocumentService {
    private Map<String, SoarFile> documents = new HashMap<>();

    private LanguageClient client;

    private Set<String> variables = new HashSet();

    private Set<String> procedures = new HashSet();

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
        SoarFile file = documents.get(params.getTextDocument().getUri());
        String line = file.line(params.getPosition().getLine());

        int cursor = params.getPosition().getCharacter();
        // The position of the start of the token.
        int start = -1;
        // The set of completions to draw from.
        Set<String> source = null;
        CompletionItemKind kind = CompletionItemKind.Function;

        // Find the start of the token and determine its type.
        for (int i = cursor; i >= 0; --i) {
            switch (line.charAt(i)) {
            case '$':
                source = variables;
                kind = CompletionItemKind.Constant;
                break;
            case ' ':
            case '[':
                source = procedures;
                kind = CompletionItemKind.Function;
                break;
            }
            if (source != null) {
                start = i + 1;
                break;
            }
        }
        if (source == null) {
            source = procedures;
            kind = CompletionItemKind.Function;
            start = 0;
        }

        CompletionItemKind itemKind = kind;

        String prefix = line.substring(start, cursor);
        List<CompletionItem> completions = source
            .stream()
            .filter(s -> s.startsWith(prefix))
            .map(s -> new CompletionItem(s))
            .map(item -> { item.setKind(itemKind); return item; })
            .collect(Collectors.toList());

        return CompletableFuture.completedFuture(Either.forLeft(completions));
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams params) {
        List<DocumentHighlight> highlights = new ArrayList<>();

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
        List<FoldingRange> ranges =
            file.commands.stream()
            .map(c -> {
                    FoldingRange range = new FoldingRange(
                        file.position(c.getLocation().getOffset() - 1).getLine(),
                        file.position(c.getLocation().getOffset() - 1 + c.getLocation().getLength()).getLine());
                    range.setKind(FoldingRangeKind.Region);
                    return range;
                })
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
            } catch (SoarInterpreterException ex) {
                SoarFile file = documents.get(uri);
                SourceLocation location = ex.getSourceLocation();
                Position start = file.position(location.getOffset() - 1);   // -1 to include starting character in diagnostic
                Position end = file.position(location.getOffset() + location.getLength());
                Diagnostic diagnostic = new Diagnostic(
                        new Range(start, end),
                        "Failed to source production in this file: " + ex,
                        DiagnosticSeverity.Error,
                        "soar");
                diagnosticList.add(diagnostic);
            } catch (TclInterpreterException ex) {
            } catch (SoarException ex) {
                // Hard code a location, but include the exception text
                // Default exception will highlight first 8 characters of first line
                Diagnostic diagnostic = new Diagnostic(
                        new Range(new Position(0, 0), new Position(0, 8)),
                        "PLACEHOLDER: Failed to source production in this file: " + ex,
                        DiagnosticSeverity.Error,
                        "soar");
                diagnosticList.add(diagnostic);
            } finally {
                // add diagnostics for any "soft" exceptions that were thrown and caught but not propagated up
                SoarFile file = documents.get(uri);
                diagnosticList.addAll(file.getDiagnostics(agent.getInterpreter().getExceptionsManager()));
            }

            // add any diagnostics found while initially parsing file
            diagnosticList.addAll(documents.get(uri).getDiagnostics());

            PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams(uri, diagnosticList);
            client.publishDiagnostics(diagnostics);
        }

        // Collect variables.
        try {
            this.variables = new HashSet<>(Arrays.asList(agent.getInterpreter().eval("info vars").split(" ")));
        } catch (SoarException e) {
        }

        // Collect procedures.
        try {
            this.procedures = new HashSet<>(Arrays.asList(agent.getInterpreter().eval("info proc").split(" ")));
        } catch (SoarException e) {
        }
    }
}

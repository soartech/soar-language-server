package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
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

    private Agent agent = new Agent();

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
        String uri = params.getTextDocument().getUri();

        for (TextDocumentContentChangeEvent change: params.getContentChanges()) {
            SoarFile soarFile = documents.get(uri);
            soarFile.applyChange(change);
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        String line = file.line(params.getPosition().getLine());

        int cursor = params.getPosition().getCharacter();
        if (cursor >= line.length()) {
            return CompletableFuture.completedFuture(Either.forLeft(new ArrayList<>()));
        }

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

        if (start >= line.length()) start = line.length() - 1;
        if (start < 0) start = 0;

        if (cursor > line.length()) cursor = line.length();
        if (cursor < start) cursor = start;

        String prefix = line.substring(start, cursor);
        List<CompletionItem> completions = source
            .stream()
            .filter(s -> s.startsWith(prefix))
            .map(CompletionItem::new)
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
            file.ast.getChildren().stream()
            .map(c -> {
                    FoldingRange range = new FoldingRange(
                        file.position(c.getStart()).getLine(),
                        file.position(c.getStart() + c.getLength() - 1).getLine());

                    if (c.getType() == TclAstNode.COMMAND) {
                        range.setKind(FoldingRangeKind.Region);
                    } else if (c.getType() == TclAstNode.COMMENT) {
                        range.setKind(FoldingRangeKind.Comment);
                    }

                    return range;
                })
            .filter(r -> r.getStartLine() < r.getEndLine())
            .collect(toList());
        return CompletableFuture.completedFuture(ranges);
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        String line = file.line(params.getPosition().getLine());

        Hover hover = null;

        // Find the token that the cursor is currently hovering
        // over. It would be better to do this using the Tcl AST,
        // because then we could figure out thing like when the cursor
        // is on an argument.
        Matcher matcher = Pattern.compile("[a-zA-Z-_]+").matcher(line);
        while (matcher.find()) {
            if (matcher.start() <= params.getPosition().getCharacter() && params.getPosition().getCharacter() < matcher.end()) {
                String token = line.substring(matcher.start(), matcher.end());
                if (variables.contains(token)) {
                    String value = "";
                    try {
                        value = agent.getInterpreter().eval("return $" + token);
                    } catch (SoarException e) {
                    }
                    Range range = new Range(
                        new Position(params.getPosition().getLine(), matcher.start() - 1), // Include the leading '$'
                        new Position(params.getPosition().getLine(), matcher.end() - 1)); // Range endings are inclusive
                    hover = new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
                    break;
                }
            }
        }

        return CompletableFuture.completedFuture(hover);
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        String line = file.line(params.getPosition().getLine());

        List<SignatureInformation> signatures = new ArrayList<>();

        // Find the token that the cursor is currently hovering
        // over. It would be better to do this using the Tcl AST,
        // because then we could figure out thing like when the cursor
        // is on an argument.
        Matcher matcher = Pattern.compile("[a-zA-Z-_]+").matcher(line);
        while (matcher.find()) {
            if (matcher.start() <= params.getPosition().getCharacter() && params.getPosition().getCharacter() < matcher.end()) {
                String token = line.substring(matcher.start(), matcher.end());
                if (!procedures.contains(token)) {
                    break;
                }

                String args = "";
                try {
                    args = agent.getInterpreter().eval("info args " + token);
                } catch (SoarException e) {
                }
                List<ParameterInformation> arguments = Arrays.stream(args.split(" "))
                    .map(arg -> new ParameterInformation(arg))
                    .collect(Collectors.toList());
                String label = token + " " + args;
                SignatureInformation info = new SignatureInformation(label, "", arguments);
                signatures.add(info);
                break;
            }
        }
        SignatureHelp help = new SignatureHelp(signatures, 0, 0);
        return CompletableFuture.completedFuture(help);
    }

    /** Wire up a reference to the client, so that we can send diagnostics. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    private void reportDiagnostics() {
        agent = new Agent();

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
            }

            SoarFile file = documents.get(uri);
            // add any diagnostics found while initially parsing file
            // add diagnostics for any "soft" exceptions that were thrown and caught but not propagated up
            diagnosticList.addAll(file.getAllDiagnostics(agent.getInterpreter().getExceptionsManager()));

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
            this.procedures = new HashSet<>(Arrays.asList(agent.getInterpreter().eval("info procs").split(" ")));
        } catch (SoarException e) {
        }
    }
}

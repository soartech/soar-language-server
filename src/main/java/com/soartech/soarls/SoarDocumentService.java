package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

import com.google.common.base.Joiner;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.CreateFileOptions;
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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
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
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jsoar.kernel.Agent;
import org.jsoar.kernel.SoarException;
import org.jsoar.kernel.exceptions.SoarInterpreterException;
import org.jsoar.kernel.exceptions.TclInterpreterException;
import org.jsoar.util.SourceLocation;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.SoarCommand;
import org.jsoar.util.commands.SoarCommandContext;
import org.jsoar.util.commands.SoarCommands;
import static java.util.stream.Collectors.toList;

class SoarDocumentService implements TextDocumentService {
    /** Soar and Tcl files in the workspace. This is just for
     * maintaining the state of the files, which includes their raw
     * contents, parsed syntax tree, and convenience methods for
     * working with this representation. It does not include
     * diagnostics information.
     */
    private Map<String, SoarFile> documents = new HashMap<>();

    /** Diagnostics information about each file. This includes things
     * like the other files that get sourced, declarations of Tcl
     * procedures and variables, production declarations, and so
     * on. The analyseFile method is the entry point for how this
     * information gets generated.
     */
    private Map<String, ProjectAnalysis> analyses = new HashMap<>();

    /** The URI of the currently active entry point. The results of
     * analysing a codebase can be different depending on where we
     * start evaluating from. In some cases, such as reporting
     * diagnostics, we can send results for all possible entry
     * points. In other cases, such as go-to-definition, we need to
     * compute results with respect to a single entry point. */
    private String activeEntryPoint = null;

    private LanguageClient client;

    private Agent agent = new Agent();

    /** The names of all the Tcl variables that are defined by the agent. */
    private Set<String> variables = new HashSet<>();

    /** The names of all the Tcl procedures that are defined by the agent. */
    private Set<String> procedures = new HashSet<>();

    /** Retrieve the analysis for the given entry point. */
    public ProjectAnalysis getAnalysis(String uri) {
        return analyses.get(uri);
    }

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
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(TextDocumentPositionParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        TclAstNode node = file.tclNode(params.getPosition());

        Location location = null;
        if (node.getType() == TclAstNode.NORMAL_WORD) {
            TclAstNode parent = node.parent;

            // if parent is QUOTED_WORD then currently on an SP command -> expand the code in buffer
            // if parent is COMMAND_WORD then go to definition if found
            if (parent.getType() == TclAstNode.QUOTED_WORD)
                location = goToDefinitionExpansion(file, parent);
            else if (parent.getType() == TclAstNode.COMMAND_WORD) {
                location = goToDefinition(file, node);
            }
        }

        List<Location> goToLocation = new ArrayList<>();
        if (location != null)
            goToLocation.add(location);

        return CompletableFuture.completedFuture(Either.forLeft(goToLocation));
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

        TclAstNode hoveredNode = file.tclNode(params.getPosition());
        FileAnalysis analysis = getAnalysis(activeEntryPoint).files.get(file.uri);

        ProcedureCall call = analysis.procedureCalls.get(hoveredNode);
        if (call != null) {
            String value = hoveredNode.getInternalText(file.contents.toCharArray());
            Range range = file.rangeForNode(hoveredNode);
            hover = new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
            return CompletableFuture.completedFuture(hover);
        }

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

    /** Retrieve the file with the given URI, reading it from the filesystem if necessary. */
    SoarFile getFile(String uri) {
        SoarFile file = documents.get(uri);

        if (file == null) {
            try {
                Path path = Paths.get(new URI(uri));
                List<String> lines = Files.readAllLines(path);
                String contents = Joiner.on("\n").join(lines);
                file = new SoarFile(uri, contents);
                documents.put(uri, file);
            } catch (Exception e) {
                System.err.println("Failed to open file: " + e);
            }
        }

        return file;
    }

    private void reportDiagnostics() {
        reportDiagnosticsForOpenFiles();
    }

    /** This implementation tries to load each open file and computes
     * diagnostics on a per-file basis. The problem with this approach
     * is that a file might rely on variables and procedures having
     * been defined before it is loaded.
     */
    private void reportDiagnosticsForOpenFiles() {
        agent = new Agent();

        System.err.println("Reporting diagnostics for " + documents.keySet());

        for (String uri: documents.keySet()) {
            try {
                ProjectAnalysis analysis = analyse(uri);
                this.analyses.put(analysis.entryPointUri, analysis);

                // TEMPORARY: We aren't currently maintaining the
                // entry point, but this will work well enough for
                // single-file projects at the moment.
                activeEntryPoint = uri;
            } catch (SoarException e) {
                System.err.println("analyse error: " + e);
            }

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

    /**
     * Finds the procedure definition of the given node
     * Returns the location of the procedure definition or null if it doesn't exist
     */
    private Location goToDefinition(SoarFile file, TclAstNode node) {
        ProjectAnalysis projectAnalysis = analyses.get(activeEntryPoint);

        String name = file.getNodeInternalText(node);
        ProcedureDefinition definition = projectAnalysis.procedureDefinitions.get(name);
        if (definition == null) return null;

        return definition.location;
    }

    /** Method will get expanded code, write to temp buffer file,
     * then return location of expanded code
     * Assumes that the node to be expanded is of type QUOTED_WORD */
    private Location goToDefinitionExpansion(SoarFile file, TclAstNode node) {
        if (node == null) return null;
        System.err.println("expanding node: " + node.getInternalText(file.contents.toCharArray()));

        String expanded_soar;
        try {
            expanded_soar = file.getExpandedCommand(agent, node);
        } catch (SoarException e) {
            e.printStackTrace();
            return null;
        }
        if (expanded_soar == null || expanded_soar.isEmpty()) return null;
        // add new line for separation from any existing code
        // when appending to the top of the file
        expanded_soar += "\n\n";

        String old_uri = file.uri;
        int index = old_uri.lastIndexOf("/") + 1;
        String new_uri = old_uri.substring(0, index) + "~" + old_uri.substring(index);

        Position create_position = createFileWithContent(new_uri, expanded_soar);

        return new Location(new_uri, new Range(create_position, create_position));
    }

    /** Create file with given contents
     * If file already exists prepend contents to beginning of file*/
    private Position createFileWithContent(String file_uri, String content) {
        // create new "buffer" file to show expanded soar code
        CreateFile createFile = new CreateFile(file_uri, new CreateFileOptions(false, false));
        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setDocumentChanges(new ArrayList<>(Arrays.asList(Either.forRight(createFile))));
        ApplyWorkspaceEditParams workspaceEditParams = new ApplyWorkspaceEditParams(workspaceEdit);

        client.applyEdit(workspaceEditParams);

        // set new content of file to expanded_soar
        Map<String, List<TextEdit>> edit_map = new HashMap<>();
        List<TextEdit> edits = new ArrayList<>();
        Position start = new Position(0, 0);
        edits.add(new TextEdit(new Range(start, start), content));
        edit_map.put(file_uri, edits);
        workspaceEdit = new WorkspaceEdit(edit_map);

        client.applyEdit(new ApplyWorkspaceEditParams(workspaceEdit));

        return start;
    }

    /** Perform a full analysis of a project starting from the given
     * entry point.
     */
    private ProjectAnalysis analyse(String uri) throws SoarException {
        ProjectAnalysis analysis = new ProjectAnalysis(uri);

        Agent agent = new Agent();

        agent.getInterpreter().eval("rename proc proc_internal");
        analyseFile(analysis, agent, uri);

        return analysis;
    }

    private void analyseFile(ProjectAnalysis projectAnalysis, Agent agent, String uri) throws SoarException {
        SoarFile file = getFile(uri);
        System.err.println("Retrieved file for " + uri + " :: " + file);
        if (file == null) {
            return;
        }

        FileAnalysis analysis = new FileAnalysis(uri);

        /** Any information that needs to be accessable to the interpreter callbacks. */
        class Context {
            /** The node we are currently iterating over. */
            TclAstNode currentNode = null;

            /** Tho most recent comment that was iterated over. */
            TclAstNode mostRecentComment = null;
        }
        final Context ctx = new Context();

        // We need to save the commands we override so that we can
        // restore them later. It's okay if we try to get a command
        // that does not yet exist; for example, on the first pass,
        // the proc command will not have been added.
        Map<String, SoarCommand> originalCommands = new HashMap<>();
        for (String cmd: Arrays.asList("source", "sp", "proc")) {
            try {
                originalCommands.put(cmd, agent.getInterpreter().getCommand(cmd, null));
            } catch (SoarException e) {
            }
        }

        try {
            agent.getInterpreter().addCommand("source", soarCommand(args -> {
                        System.err.println("Executing " + Arrays.toString(args) + " from " + uri);

                        try {
                            Path currentPath = Paths.get(new URI(uri));
                            Path pathToSource = currentPath.resolveSibling(args[1]);
                            String path = pathToSource.toUri().toString();
                            analysis.filesSourced.add(path);

                            analyseFile(projectAnalysis, agent, path);
                        } catch (Exception e) {
                            System.err.println("exception while tracing source: ");
                            e.printStackTrace(System.err);
                        }
                        return "";
                    }));

            agent.getInterpreter().addCommand("sp", soarCommand(args -> {
                        System.err.println("Executing " + Arrays.toString(args));

                        Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                        analysis.productions.add(new Production(args[1], location));
                        return "";
                    }));

            agent.getInterpreter().addCommand("proc", soarCommand(args -> {
                        Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                        ProcedureDefinition proc = new ProcedureDefinition(args[1], location);
                        proc.ast = ctx.currentNode;
                        if (ctx.mostRecentComment != null) {
                            // Note that because of the newline,
                            // comments end at the beginning of the
                            // following line.
                            int commentEndLine = file.position(ctx.mostRecentComment.getEnd()).getLine();
                            int procStartLine = file.position(ctx.currentNode.getStart()).getLine();
                            System.err.println("comment ends at " + commentEndLine + "; proc starts at " + procStartLine);
                            if (commentEndLine == procStartLine) {
                                proc.commentAstNode = ctx.mostRecentComment;
                                proc.commentText = ctx.mostRecentComment.getInternalText(file.contents.toCharArray());
                            }
                        }
                        analysis.procedureDefinitions.add(proc);
                        projectAnalysis.procedureDefinitions.put(proc.name, proc);

                        // The args arrays has stripped away the
                        // braces, so we need to add them back in
                        // before we evaluate the command, but using
                        // the real proc command instead.
                        args[0] = "proc_internal";
                        return agent.getInterpreter().eval("{" + Joiner.on("} {").join(args) + "}");
                    }));

            for (TclAstNode node: file.ast.getChildren()) {
                ctx.currentNode = node;
                if (node.getType() == TclAstNode.COMMENT) {
                    ctx.mostRecentComment = node;
                } else {
                    String commandText = node.getInternalText(file.contents.toCharArray());
                    agent.getInterpreter().eval(commandText);
                }
            }

            // Traverse file ast tree
            // for each COMMAND node found, if the node contains a NORMAL_WORD child
            // then add the procedure call to the file analysis
            file.traverseAstTree(node -> {
                switch (node.getType()) {
                    case TclAstNode.COMMAND:
                    case TclAstNode.COMMAND_WORD: {
                        TclAstNode firstChild = node.getChild(TclAstNode.NORMAL_WORD);
                        if (firstChild != null) {
                            String name = file.getNodeInternalText(firstChild);
                            ProcedureCall procedureCall = new ProcedureCall(firstChild);
                            procedureCall.definition = projectAnalysis.procedureDefinitions.get(name);

                            analysis.procedureCalls.put(firstChild, procedureCall);
                        }
                        break;
                    }
                }
            });
            
            projectAnalysis.files.put(uri, analysis);
        } finally {
            // Restore original commands
            for (Map.Entry<String, SoarCommand> cmd: originalCommands.entrySet()) {
                agent.getInterpreter().addCommand(cmd.getKey(), cmd.getValue());
            }
        }
    }

    interface SoarCommandExecute {
        String execute(String[] args) throws SoarException;
    }

    /** A convenience function for implementing the SoarCommand
     * interface by passing a lambda instead. */
    static SoarCommand soarCommand(SoarCommandExecute implementation) {
        return new SoarCommand() {
            @Override
            public String execute(SoarCommandContext context, String[] args) throws SoarException {
                return implementation.execute(args);
            }

            @Override
            public Object getCommand() { return this; }
        };
    }
}

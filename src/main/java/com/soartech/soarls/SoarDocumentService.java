package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
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
import org.eclipse.lsp4j.ReferenceParams;
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
import org.jsoar.kernel.exceptions.SoftTclInterpreterException;
import org.jsoar.kernel.exceptions.TclInterpreterException;
import org.jsoar.util.SourceLocation;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.SoarCommand;
import org.jsoar.util.commands.SoarCommandContext;
import org.jsoar.util.commands.SoarCommands;

import static java.util.stream.Collectors.joining;
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

    /** Retrieve the analysis for the given entry point. */
    public ProjectAnalysis getAnalysis(String uri) {
        return analyses.get(uri);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();

        SoarFile soarFile = new SoarFile(doc.getUri(), doc.getText());
        documents.put(soarFile.uri, soarFile);

        if (activeEntryPoint == null) {
            this.setEntryPoint(soarFile.uri);
        }

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
            documents.get(uri).applyChange(change);
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        String uri = params.getTextDocument().getUri();

        List<Either<Command, CodeAction>> actions = new ArrayList<>();
        if (!params.getTextDocument().getUri().equals(activeEntryPoint)) {
            actions.add(Either.forLeft(new Command("set project entry point", "set-entry-point", Lists.newArrayList(uri))));
        }
        return CompletableFuture.completedFuture(actions);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(TextDocumentPositionParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        TclAstNode node = file.tclNode(params.getPosition());

        Location location = null;
        if (node.getType() == TclAstNode.NORMAL_WORD) {
            TclAstNode parent = node.getParent();

            // if parent is QUOTED_WORD then currently on an SP command -> expand the code in buffer
            // if parent is COMMAND_WORD then go to procedure definition if found.
            if (parent.getType() == TclAstNode.QUOTED_WORD) {
                location = goToDefinitionExpansion(file, parent);
            } else if (parent.getType() == TclAstNode.COMMAND_WORD) {
                location = goToDefinitionProcedure(file, node);
            }
        } else if (node.getType() == TclAstNode.VARIABLE || node.getType() == TclAstNode.VARIABLE_NAME) {
            location = goToDefinitionVariable(file, node);
        }

        List<Location> goToLocation = new ArrayList<>();
        if (location != null) {
            goToLocation.add(location);
        }

        return CompletableFuture.completedFuture(Either.forLeft(goToLocation));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        ProjectAnalysis analysis = getAnalysis(activeEntryPoint);
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
                source = analysis.variableDefinitions.keySet();
                kind = CompletionItemKind.Constant;
                break;
            case ' ':
            case '[':
                source = analysis.procedureDefinitions.keySet();
                kind = CompletionItemKind.Function;
                break;
            }
            if (source != null) {
                start = i + 1;
                break;
            }
        }
        if (source == null) {
            source = analysis.procedureDefinitions.keySet();
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
            .collect(toList());

        return CompletableFuture.completedFuture(Either.forLeft(completions));
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams params) {
        final SoarFile file = documents.get(params.getTextDocument().getUri());
        final int offset = file.offset(params.getPosition());

        final List<DocumentHighlight> highlights = file
            .ast
            .getChildren()
            .stream()
            .filter(node -> node.getType() != TclAstNode.COMMENT)
            .filter(node -> node.getStart() <= offset && offset <= node.getEnd())
            .map(node -> new Range(file.position(node.getStart()), file.position(node.getEnd() + 1)))
            .map(DocumentHighlight::new)
            .collect(toList());

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
        FileAnalysis analysis = getAnalysis(activeEntryPoint).files.get(params.getTextDocument().getUri());
        SoarFile file = documents.get(analysis.uri);
        TclAstNode hoveredNode = file.tclNode(params.getPosition());

        Function<TclAstNode, Hover> hoverVariable = node -> {
            VariableRetrieval retrieval = analysis.variableRetrievals.get(node);
            if (retrieval == null) return null;
            String value = retrieval.definition.value;
            Range range = file.rangeForNode(node);
            return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
        };

        Function<TclAstNode, Hover> hoverProcedureCall = node -> {
            ProcedureCall call = analysis.procedureCalls.get(node);
            if (call == null) return null;
            String value = file.getNodeInternalText(node);
            if (call.definition != null) {
                value = call.definition.name + " " + Joiner.on(" ").join(call.definition.arguments);
            }
            // We are clearly not storing the right information
            // here. Computing the range should be much simpler.
            List<TclAstNode> callChildren = call.callSiteAst.getParent().getChildren();
            Range range = new Range(file.position(callChildren.get(0).getStart()),
                                    file.position(callChildren.get(callChildren.size() - 1).getEnd()));
            return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
        };

        Supplier<Hover> getHover = () -> {
            switch (hoveredNode.getType()) {
            case TclAstNode.VARIABLE:
                return hoverVariable.apply(hoveredNode);
            case TclAstNode.VARIABLE_NAME:
                return hoverVariable.apply(hoveredNode.getParent());
            default:
                return hoverProcedureCall.apply(hoveredNode);
            }
        };

        return CompletableFuture.completedFuture(getHover.get());
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        SoarFile file = documents.get(params.getTextDocument().getUri());
        TclAstNode astNode = file.tclNode(params.getPosition());
        ProjectAnalysis analysis = getAnalysis(activeEntryPoint);
        FileAnalysis fileAnalysis = analysis.files.get(file.uri);

        List<Location> references = new ArrayList<>();

        Optional<ProcedureDefinition> procDef = fileAnalysis
            .procedureCall(astNode)
            .flatMap(call -> Optional.ofNullable(call.definition));
        if (!procDef.isPresent()) {
            procDef = fileAnalysis
                .procedureDefinitions
                .stream()
                .filter(def -> def.ast.containsChild(astNode))
                .findFirst();
        }
        procDef.ifPresent(def -> {
                for (ProcedureCall call: analysis.procedureCalls.get(def)) {
                    references.add(call.callSiteLocation);
                }
            });

        Optional<VariableDefinition> varDef = fileAnalysis
            .variableRetrieval(astNode)
            .flatMap(ret -> Optional.ofNullable(ret.definition));
        if (!varDef.isPresent()) {
            varDef = fileAnalysis
                .variableDefinitions
                .stream()
                .filter(def -> def.ast.containsChild(astNode))
                .findFirst();
        }
        varDef.ifPresent(def -> {
                for (VariableRetrieval ret: analysis.variableRetrievals.get(def)) {
                    references.add(ret.readSiteLocation);
                }
            });

        return CompletableFuture.completedFuture(references);
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        FileAnalysis analysis = getAnalysis(activeEntryPoint).files.get(params.getTextDocument().getUri());
        SoarFile file = documents.get(analysis.uri);
        TclAstNode astNode = file.tclNode(params.getPosition());

        List<SignatureInformation> signatures = new ArrayList<>();

        ProcedureCall call = analysis.procedureCalls.get(astNode);
        if (call != null && call.definition != null) {
            ProcedureDefinition def = call.definition;
            String label = def.name + " " + Joiner.on(" ").join(def.arguments);
            List<ParameterInformation> arguments = def.arguments.stream().map(arg -> new ParameterInformation(arg)).collect(toList());
            SignatureInformation info = new SignatureInformation(label, "", arguments);
            signatures.add(info);
        }

        SignatureHelp help = new SignatureHelp(signatures, 0, 0);
        return CompletableFuture.completedFuture(help);
    }

    /** Wire up a reference to the client, so that we can send diagnostics. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    /** Set the entry point of the Soar agent - the first file that
     * should be sourced. */
    public void setEntryPoint(String uri) {
        this.activeEntryPoint = uri;
        try {
            ProjectAnalysis analysis = analyse(this.activeEntryPoint);
            this.analyses.put(analysis.entryPointUri, analysis);
        } catch (SoarException e) {
            System.err.println("analyse error: " + e);
        }
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

        if (activeEntryPoint != null) {
            try {
                ProjectAnalysis analysis = analyse(activeEntryPoint);
                this.analyses.put(analysis.entryPointUri, analysis);
            } catch (SoarException e) {
                System.err.println("analyse error: " + e);
            }
        }
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
            final SoarFile file = documents.get(uri);
            final List<Diagnostic> diagnosticList = new ArrayList<>();

            try {
                SoarCommands.source(agent.getInterpreter(), uri);
            } catch (SoarInterpreterException ex) {
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

            // add any diagnostics found while initially parsing file
            diagnosticList.addAll(file.getDiagnostics());

            // add diagnostics for any "soft" exceptions that were thrown and caught but not propagated up
            for (SoftTclInterpreterException e: agent.getInterpreter().getExceptionsManager().getExceptions()) {
                int offset = file.contents.indexOf(e.getCommand());
                if (offset < 0) offset = 0;
                Range range = new Range(
                    file.position(offset),
                    file.position(offset + e.getCommand().length()));
                diagnosticList.add(new Diagnostic(
                                       range,
                                       e.getMessage().trim(),
                                       DiagnosticSeverity.Error,
                                       "soar"
                                       ));
            }

            PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams(uri, diagnosticList);
            client.publishDiagnostics(diagnostics);
        }
    }

    /**
     * Finds the procedure definition of the given node
     * Returns the location of the procedure definition or null if it doesn't exist
     */
    private Location goToDefinitionProcedure(SoarFile file, TclAstNode node) {
        ProjectAnalysis projectAnalysis = analyses.get(activeEntryPoint);

        String name = file.getNodeInternalText(node);
        ProcedureDefinition definition = projectAnalysis.procedureDefinitions.get(name);
        if (definition == null) return null;

        return definition.location;
    }

    private Location goToDefinitionVariable(SoarFile file, TclAstNode node) {
        ProjectAnalysis projectAnalysis = analyses.get(activeEntryPoint);
        FileAnalysis fileAnalysis = projectAnalysis.files.get(file.uri);

        TclAstNode variableNode = node.getType() == TclAstNode.VARIABLE ? node : node.getParent();
        System.err.println("Looking of definition of variable at node " + variableNode);
        VariableRetrieval retrieval = fileAnalysis.variableRetrievals.get(variableNode);
        if (retrieval == null) return null;
        return retrieval.definition.location;
    }

    /** Method will get expanded code, write to temp buffer file,
     * then return location of expanded code
     * Assumes that the node to be expanded is of type QUOTED_WORD */
    private Location goToDefinitionExpansion(SoarFile file, TclAstNode node) {
        if (node == null) return null;
        System.err.println("expanding node: " + node.getInternalText(file.contents.toCharArray()));

        String expanded_soar = file.getExpandedCommand(agent, node);

        if (expanded_soar == null || expanded_soar.isEmpty()) return null;
        // add new line for separation from any existing code
        // when appending to the top of the file
        expanded_soar += "\n\n";

        String new_uri = getBufferedUri(file.uri);
        Position create_position = createFileWithContent(new_uri, expanded_soar);

        return new Location(new_uri, new Range(create_position, create_position));
    }

    /** Create file with given contents
     * If file already exists prepend contents to beginning of file*/
    private Position createFileWithContent(String file_uri, String content) {
        // create new "buffer" file to show expanded soar code
        CreateFile createFile = new CreateFile(file_uri, new CreateFileOptions(true, false));
        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setDocumentChanges(new ArrayList<>(Arrays.asList(Either.forRight(createFile))));
        ApplyWorkspaceEditParams workspaceEditParams = new ApplyWorkspaceEditParams(workspaceEdit);
        Position start = new Position(0, 0);

        CompletableFuture<ApplyWorkspaceEditResponse> future = client.applyEdit(workspaceEditParams);
        future.thenRun(() -> {
            // set new content of file to expanded_soar
            Map<String, List<TextEdit>> edit_map = new HashMap<>();
            List<TextEdit> edits = new ArrayList<>();

            edits.add(new TextEdit(new Range(start, start), content));
            edit_map.put(file_uri, edits);
            WorkspaceEdit edit = new WorkspaceEdit(edit_map);

            client.applyEdit(new ApplyWorkspaceEditParams(edit));
        });

        return start;
    }

    /** Given a file uri, returns buffer file uri
     * Where filename is modified with a prepended ~
     * file:///C:/test/origin_file.soar -> file:///C:/test/~origin_file.soar */
    private String getBufferedUri(String uri) {
        int index = uri.lastIndexOf("/") + 1;
        return uri.substring(0, index) + "~" + uri.substring(index);
    }

    /** Perform a full analysis of a project starting from the given
     * entry point.
     */
    private ProjectAnalysis analyse(String uri) throws SoarException {
        ProjectAnalysis analysis = new ProjectAnalysis(uri);

        Agent agent = new Agent();

        agent.getInterpreter().eval("rename proc proc_internal");
        agent.getInterpreter().eval("rename set set_internal");
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
        for (String cmd: Arrays.asList("source", "sp", "proc", "set")) {
            try {
                originalCommands.put(cmd, agent.getInterpreter().getCommand(cmd, null));
            } catch (SoarException e) {
            }
        }

        try {
            agent.getInterpreter().addCommand("source", soarCommand(args -> {
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
                        Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                        analysis.productions.add(new Production(args[1], location));
                        return "";
                    }));

            agent.getInterpreter().addCommand("proc", soarCommand(args -> {
                        Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                        ProcedureDefinition proc = new ProcedureDefinition(args[1], location);
                        proc.ast = ctx.currentNode;
                        proc.arguments = Arrays.asList(args[2].trim().split("\\s+"));
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
                        projectAnalysis.procedureCalls.put(proc, new ArrayList<>());

                        // The args arrays has stripped away the
                        // braces, so we need to add them back in
                        // before we evaluate the command, but using
                        // the real proc command instead.
                        args[0] = "proc_internal";
                        return agent.getInterpreter().eval("{" + Joiner.on("} {").join(args) + "}");
                    }));

            agent.getInterpreter().addCommand("set", soarCommand(args -> {
                        String name = args[1];
                        Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                        VariableDefinition var = new VariableDefinition(name, location);
                        var.ast = ctx.currentNode;
                        if (ctx.mostRecentComment != null) {
                            int commentEndLine = file.position(ctx.mostRecentComment.getEnd()).getLine();
                            int varStartLine = file.position(ctx.currentNode.getStart()).getLine();
                            if (commentEndLine == varStartLine) {
                                var.commentAstNode = ctx.mostRecentComment;
                                var.commentText = ctx.mostRecentComment.getInternalText(file.contents.toCharArray());
                            }
                        }
                        analysis.variableDefinitions.add(var);
                        projectAnalysis.variableDefinitions.put(var.name, var);
                        projectAnalysis.variableRetrievals.put(var, new ArrayList<>());

                        args[0] = "set_internal";
                        var.value = agent.getInterpreter().eval("{" + Joiner.on("} {").join(args) + "}");
                        return var.value;
                    }));

            // Traverse file ast tree
            // for each COMMAND node found, if the node contains a NORMAL_WORD child
            // then add the procedure call to the file analysis
            file.traverseAstTree(node -> {
                if (node.expanded == null)
                    node.expanded = file.getNodeInternalText(node);

                if (node.getType() == TclAstNode.COMMENT) {
                    ctx.mostRecentComment = node;
                } else if (node.getType() == TclAstNode.COMMAND) {
                    ctx.currentNode = node;
                    try {
                        agent.getInterpreter().eval(node.expanded);
                    } catch (SoarException e) {
                        // If anything goes wrong, we just bail out
                        // early. The tree traversal will continue, so
                        // we might still collect useful information.
                        System.err.println("Error while evaluating Soar command: " + e);
                        return;
                    }
                }

                switch (node.getType()) {
                    case TclAstNode.COMMAND: {
                        TclAstNode quoted_word = node.getChild(TclAstNode.QUOTED_WORD);
                        // if command is production
                        if (quoted_word != null) {
                            quoted_word.expanded = getExpandedCode(file, quoted_word);
                            TclAstNode command = node.getChild(TclAstNode.NORMAL_WORD);
                            command.expanded = file.getNodeInternalText(command);
                            node.expanded = command.expanded + " \"" + quoted_word.expanded + '"';
                        }
                    }
                    case TclAstNode.COMMAND_WORD: {
                        TclAstNode firstChild = node.getChild(TclAstNode.NORMAL_WORD);
                        if (firstChild != null) {
                            String name = file.getNodeInternalText(firstChild);
                            Location location = new Location(uri, file.rangeForNode(node));
                            ProcedureCall procedureCall = new ProcedureCall(location, firstChild);
                            procedureCall.definition = projectAnalysis.procedureDefinitions.get(name);

                            analysis.procedureCalls.put(firstChild, procedureCall);
                            if (procedureCall.definition != null) {
                                projectAnalysis.procedureCalls.get(procedureCall.definition).add(procedureCall);
                            }
                        }
                    } break;
                    case TclAstNode.VARIABLE: {
                        TclAstNode nameNode = node.getChild(TclAstNode.VARIABLE_NAME);
                        if (nameNode != null) {
                            String name = file.getNodeInternalText(nameNode);
                            Location location = new Location(uri, file.rangeForNode(node));
                            VariableRetrieval retrieval = new VariableRetrieval(location, node);
                            retrieval.definition = projectAnalysis.variableDefinitions.get(name);

                            System.err.println("Recording retrieval of " + name + " at node " + node + ": " + retrieval);
                            analysis.variableRetrievals.put(node, retrieval);
                            if (retrieval.definition != null) {
                                projectAnalysis.variableRetrievals.get(retrieval.definition).add(retrieval);
                            }
                        }
                    } break;
                }
            });

            String expanded_file = getExpandedFile(file);
            String buffer_uri = getBufferedUri(uri);
            createFileWithContent(buffer_uri, expanded_file);
            SoarFile soarFile = new SoarFile(buffer_uri, expanded_file);
            documents.put(soarFile.uri, soarFile);
            
            projectAnalysis.files.put(uri, analysis);
        } finally {
            // Restore original commands
            for (Map.Entry<String, SoarCommand> cmd: originalCommands.entrySet()) {
                agent.getInterpreter().addCommand(cmd.getKey(), cmd.getValue());
            }
        }
    }

    String getExpandedFile(SoarFile file) {
        return file.ast.getChildren().stream()
                .map(child -> child.expanded)
                .collect(joining("\n"));
    }

    private String printAstTree(SoarFile file) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(baos, true)) {
            file.ast.printTree(ps, file.contents.toCharArray(), 4);
        }
        String data = new String(baos.toByteArray());
        return data;
    }

    private String getExpandedCode(SoarFile file, TclAstNode node) {
        return getExpandedCode(file.getNodeInternalText(node));
    }

    private String getExpandedCode(String code) {
        try {
            return agent.getInterpreter().eval("return " + '"' + code + '"');
        } catch (SoarException e) {
            return code;
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
                System.err.println("Executing " + Arrays.toString(args));
                return implementation.execute(args);
            }

            @Override
            public Object getCommand() { return this; }
        };
    }
}

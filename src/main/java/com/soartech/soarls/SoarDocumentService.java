package com.soartech.soarls;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.soartech.soarls.analysis.Analysis;
import com.soartech.soarls.analysis.FileAnalysis;
import com.soartech.soarls.analysis.ProcedureCall;
import com.soartech.soarls.analysis.ProcedureDefinition;
import com.soartech.soarls.analysis.ProjectAnalysis;
import com.soartech.soarls.analysis.VariableDefinition;
import com.soartech.soarls.analysis.VariableRetrieval;
import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.util.Debouncer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
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
import org.jsoar.util.commands.SoarCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SoarDocumentService implements TextDocumentService {
  private static final Logger LOG = LoggerFactory.getLogger(SoarDocumentService.class);

  /**
   * Soar and Tcl files in the workspace. This is just for maintaining the state of the files, which
   * includes their raw contents, parsed syntax tree, and convenience methods for working with this
   * representation. It does not include diagnostics information.
   */
  public final Documents documents = new Documents();

  /**
   * Diagnostics information about each file. This includes things like the other files that get
   * sourced, declarations of Tcl procedures and variables, production declarations, and so on. The
   * analyseFile method is the entry point for how this information gets generated.
   */
  private final ConcurrentHashMap<String, ProjectAnalysis> analyses = new ConcurrentHashMap<>();

  /** Handles to diagnostics information that is currently being computed. */
  private final ConcurrentHashMap<String, CompletableFuture<ProjectAnalysis>> pendingAnalyses =
      new ConcurrentHashMap<>();

  /**
   * The debouncer is used to schedule analysis runs. They can be submitted as often as you like,
   * but they will only be run periodically.
   */
  private final Debouncer debouncer = new Debouncer(Duration.ofMillis(1000));

  /**
   * The URI of the currently active entry point. The results of analysing a codebase can be
   * different depending on where we start evaluating from. In some cases, such as reporting
   * diagnostics, we can send results for all possible entry points. In other cases, such as
   * go-to-definition, we need to compute results with respect to a single entry point.
   */
  private String activeEntryPoint = null;

  private LanguageClient client;

  private Agent agent = new Agent();

  /**
   * Retrieve the most recently completed analysis for the given entry point. If an analysis has
   * already been completed then the future will resolve immediately; otherwise, you may assume that
   * the analysis is in progress and the future will resolve eventually.
   */
  public CompletableFuture<ProjectAnalysis> getAnalysis(String uri) {
    CompletableFuture<ProjectAnalysis> pending = pendingAnalyses.get(uri);
    if (pending != null) {
      if (pending.isDone()) {
        try {
          analyses.put(uri, pending.get());
          pendingAnalyses.remove(uri, pending);
        } catch (Exception e) {
          LOG.error("Retrieving result of analysis", e);
        }
      }
    }

    ProjectAnalysis analysis = analyses.get(uri);
    return analysis == null ? pending : CompletableFuture.completedFuture(analysis);
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    TextDocumentItem doc = params.getTextDocument();
    SoarFile soarFile = documents.open(doc);

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
    documents.close(params.getTextDocument().getUri());
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    documents.applyChanges(params);
    scheduleAnalysis();
  }

  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
    String uri = params.getTextDocument().getUri();

    List<Either<Command, CodeAction>> actions = new ArrayList<>();
    if (!params.getTextDocument().getUri().equals(activeEntryPoint)) {
      actions.add(
          Either.forLeft(
              new Command("set project entry point", "set-entry-point", Lists.newArrayList(uri))));
    }
    return CompletableFuture.completedFuture(actions);
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(TextDocumentPositionParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              SoarFile file = documents.get(params.getTextDocument().getUri());
              TclAstNode node = file.tclNode(params.getPosition());

              Location location = null;
              if (node.getType() == TclAstNode.NORMAL_WORD) {
                TclAstNode parent = node.getParent();

                // if parent is QUOTED_WORD then currently on an SP command -> expand the code in
                // buffer
                // if parent is COMMAND_WORD then go to procedure definition if found.
                if (parent.getType() == TclAstNode.QUOTED_WORD) {
                  location = goToDefinitionExpansion(analysis, file, parent);
                } else if (parent.getType() == TclAstNode.COMMAND_WORD) {
                  location = goToDefinitionProcedure(analysis, file, node);
                }
              } else if (node.getType() == TclAstNode.VARIABLE
                  || node.getType() == TclAstNode.VARIABLE_NAME) {
                location = goToDefinitionVariable(analysis, file, node).orElse(null);
              }

              List<Location> goToLocation = new ArrayList<>();
              if (location != null) {
                goToLocation.add(location);
              }

              return Either.forLeft(goToLocation);
            });
  }

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
      CompletionParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              SoarFile file = analysis.files.get(params.getTextDocument().getUri()).file;
              String line = file.line(params.getPosition().getLine());

              int cursor = params.getPosition().getCharacter();
              if (cursor >= line.length()) {
                return Either.forLeft(new ArrayList<>());
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
              List<CompletionItem> completions =
                  source
                      .stream()
                      .filter(s -> s.startsWith(prefix))
                      .map(CompletionItem::new)
                      .map(
                          item -> {
                            item.setKind(itemKind);
                            return item;
                          })
                      .collect(toList());

              return Either.forLeft(completions);
            });
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
      TextDocumentPositionParams params) {
    final SoarFile file = documents.get(params.getTextDocument().getUri());
    final int offset = file.offset(params.getPosition());

    final List<DocumentHighlight> highlights =
        file.ast
            .getChildren()
            .stream()
            .filter(node -> node.getType() != TclAstNode.COMMENT)
            .filter(node -> node.getStart() <= offset && offset <= node.getEnd())
            .map(
                node -> new Range(file.position(node.getStart()), file.position(node.getEnd() + 1)))
            .map(DocumentHighlight::new)
            .collect(toList());

    return CompletableFuture.completedFuture(highlights);
  }

  @Override
  public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
    SoarFile file = documents.get(params.getTextDocument().getUri());
    List<FoldingRange> ranges =
        file.ast
            .getChildren()
            .stream()
            .map(
                c -> {
                  FoldingRange range =
                      new FoldingRange(
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
    return getAnalysis(activeEntryPoint)
        .thenApply(
            projectAnalysis -> {
              FileAnalysis analysis = projectAnalysis.files.get(params.getTextDocument().getUri());
              SoarFile file = analysis.file;
              TclAstNode hoveredNode = file.tclNode(params.getPosition());

              Function<TclAstNode, Hover> hoverVariable =
                  node -> {
                    VariableRetrieval retrieval = analysis.variableRetrievals.get(node);
                    if (retrieval == null) return null;
                    String value = retrieval.definition.map(def -> def.value).orElse("");
                    Range range = file.rangeForNode(node);
                    return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
                  };

              Function<TclAstNode, Hover> hoverProcedureCall =
                  node -> {
                    ProcedureCall call = analysis.procedureCalls.get(node);
                    if (call == null) return null;
                    String value =
                        call.definition
                            .map(def -> def.name + " " + Joiner.on(" ").join(def.arguments))
                            .orElse(file.getNodeInternalText(node));
                    // We are clearly not storing the right information
                    // here. Computing the range should be much simpler.
                    List<TclAstNode> callChildren = call.callSiteAst.getParent().getChildren();
                    Range range =
                        new Range(
                            file.position(callChildren.get(0).getStart()),
                            file.position(callChildren.get(callChildren.size() - 1).getEnd()));
                    return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
                  };

              Supplier<Hover> getHover =
                  () -> {
                    switch (hoveredNode.getType()) {
                      case TclAstNode.VARIABLE:
                        return hoverVariable.apply(hoveredNode);
                      case TclAstNode.VARIABLE_NAME:
                        return hoverVariable.apply(hoveredNode.getParent());
                      default:
                        return hoverProcedureCall.apply(hoveredNode);
                    }
                  };

              return getHover.get();
            });
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              FileAnalysis fileAnalysis = analysis.files.get(params.getTextDocument().getUri());
              TclAstNode astNode = fileAnalysis.file.tclNode(params.getPosition());

              List<Location> references = new ArrayList<>();

              Optional<ProcedureDefinition> procDef =
                  fileAnalysis.procedureCall(astNode).flatMap(call -> call.definition);
              if (!procDef.isPresent()) {
                procDef =
                    fileAnalysis
                        .procedureDefinitions
                        .stream()
                        .filter(def -> def.ast.containsChild(astNode))
                        .findFirst();
              }
              procDef.ifPresent(
                  def -> {
                    for (ProcedureCall call : analysis.procedureCalls.get(def)) {
                      references.add(call.callSiteLocation);
                    }
                  });

              Optional<VariableDefinition> varDef =
                  fileAnalysis.variableRetrieval(astNode).flatMap(ret -> ret.definition);
              if (!varDef.isPresent()) {
                varDef =
                    fileAnalysis
                        .variableDefinitions
                        .stream()
                        .filter(def -> def.ast.containsChild(astNode))
                        .findFirst();
              }
              varDef.ifPresent(
                  def -> {
                    for (VariableRetrieval ret : analysis.variableRetrievals.get(def)) {
                      references.add(ret.readSiteLocation);
                    }
                  });

              return references;
            });
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(analysis -> analysis.files.get(params.getTextDocument().getUri()))
        .thenApply(
            analysis -> {
              SoarFile file = analysis.file;
              TclAstNode astNode = file.tclNode(params.getPosition());

              List<SignatureInformation> signatures = new ArrayList<>();

              ProcedureCall call = analysis.procedureCalls.get(astNode);
              if (call != null) {
                call.definition.ifPresent(
                    def -> {
                      String label = def.name + " " + Joiner.on(" ").join(def.arguments);
                      List<ParameterInformation> arguments =
                          def.arguments
                              .stream()
                              .map(arg -> new ParameterInformation(arg))
                              .collect(toList());
                      SignatureInformation info = new SignatureInformation(label, "", arguments);
                      signatures.add(info);
                    });
              }

              SignatureHelp help = new SignatureHelp(signatures, 0, 0);
              return help;
            });
  }

  /** Wire up a reference to the client, so that we can send diagnostics. */
  public void connect(LanguageClient client) {
    this.client = client;
  }

  /** Set the entry point of the Soar agent - the first file that should be sourced. */
  public void setEntryPoint(String uri) {
    this.activeEntryPoint = uri;
    scheduleAnalysis();
  }

  private void reportDiagnostics() {
    reportDiagnosticsForOpenFiles();
  }

  /**
   * Schedule an analysis run. It is safe to call this multiple times in quick succession, because
   * the requests are debounced.
   */
  private void scheduleAnalysis() {
    CompletableFuture<ProjectAnalysis> future =
        pendingAnalyses.computeIfAbsent(this.activeEntryPoint, key -> new CompletableFuture());

    if (future.isDone()) {
      return;
    }
    debouncer.submit(
        () -> {
          ProjectAnalysis analysis = Analysis.analyse(this.documents, this.activeEntryPoint);
          future.complete(analysis);
        });
  }

  /**
   * This implementation tries to load each open file and computes diagnostics on a per-file basis.
   * The problem with this approach is that a file might rely on variables and procedures having
   * been defined before it is loaded.
   */
  private void reportDiagnosticsForOpenFiles() {
    agent = new Agent();

    for (String uri : documents.openUris()) {
      final SoarFile file = documents.get(uri);
      final List<Diagnostic> diagnosticList = new ArrayList<>();

      try {
        SoarCommands.source(agent.getInterpreter(), uri);
      } catch (SoarInterpreterException ex) {
        SourceLocation location = ex.getSourceLocation();
        Position start =
            file.position(
                location.getOffset() - 1); // -1 to include starting character in diagnostic
        Position end = file.position(location.getOffset() + location.getLength());
        Diagnostic diagnostic =
            new Diagnostic(
                new Range(start, end),
                "Failed to source production in this file: " + ex,
                DiagnosticSeverity.Error,
                "soar");
        diagnosticList.add(diagnostic);
      } catch (TclInterpreterException ex) {
      } catch (SoarException ex) {
        // Hard code a location, but include the exception text
        // Default exception will highlight first 8 characters of first line
        Diagnostic diagnostic =
            new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 8)),
                "PLACEHOLDER: Failed to source production in this file: " + ex,
                DiagnosticSeverity.Error,
                "soar");
        diagnosticList.add(diagnostic);
      }

      // add any diagnostics found while initially parsing file
      diagnosticList.addAll(file.getDiagnostics());

      // add diagnostics for any "soft" exceptions that were thrown and caught but not propagated up
      for (SoftTclInterpreterException e :
          agent.getInterpreter().getExceptionsManager().getExceptions()) {
        int offset = file.contents.indexOf(e.getCommand());
        if (offset < 0) offset = 0;
        Range range =
            new Range(file.position(offset), file.position(offset + e.getCommand().length()));
        diagnosticList.add(
            new Diagnostic(range, e.getMessage().trim(), DiagnosticSeverity.Error, "soar"));
      }

      PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams(uri, diagnosticList);
      client.publishDiagnostics(diagnostics);
    }
  }

  /**
   * Find the procedure definition of the given node. Returns the location of the procedure
   * definition or null if it doesn't exist.
   */
  private Location goToDefinitionProcedure(
      ProjectAnalysis projectAnalysis, SoarFile file, TclAstNode node) {
    String name = file.getNodeInternalText(node);
    ProcedureDefinition definition = projectAnalysis.procedureDefinitions.get(name);
    if (definition == null) return null;

    return definition.location;
  }

  private Optional<Location> goToDefinitionVariable(
      ProjectAnalysis projectAnalysis, SoarFile file, TclAstNode node) {
    FileAnalysis fileAnalysis = projectAnalysis.files.get(file.uri);

    LOG.trace("Looking up definition of variable at node {}", node);
    return fileAnalysis.variableRetrieval(node).flatMap(r -> r.definition).map(def -> def.location);
  }

  /**
   * Method will get expanded code, write to temp buffer file, then return location of expanded code
   * Assumes that the node to be expanded is of type QUOTED_WORD
   */
  private Location goToDefinitionExpansion(
      ProjectAnalysis projectAnalysis, SoarFile file, TclAstNode node) {
    TclAstNode commandNode = node;
    if (commandNode.getParent() == null) return null;
    while (commandNode.getParent().getType() != TclAstNode.ROOT) {
      commandNode = commandNode.getParent();
    }

    String expandedSoar =
        projectAnalysis
            .files
            .get(file.uri)
            .productions
            .getOrDefault(commandNode, ImmutableList.of())
            .stream()
            .map(production -> "sp {" + production.body + "}\n")
            .collect(joining("\n"));

    if (expandedSoar == null || expandedSoar.isEmpty()) return null;
    // add new line for separation from any existing code
    // when appending to the top of the file
    expandedSoar += "\n\n";

    String new_uri = getBufferedUri(file.uri);
    Position create_position = createFileWithContent(new_uri, expandedSoar);

    return new Location(new_uri, new Range(create_position, create_position));
  }

  /**
   * Create file with given contents If file already exists prepend contents to beginning of file
   */
  private Position createFileWithContent(String file_uri, String content) {
    // create new "buffer" file to show expanded soar code
    CreateFile createFile = new CreateFile(file_uri, new CreateFileOptions(true, false));
    WorkspaceEdit workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setDocumentChanges(new ArrayList<>(Arrays.asList(Either.forRight(createFile))));
    ApplyWorkspaceEditParams workspaceEditParams = new ApplyWorkspaceEditParams(workspaceEdit);
    Position start = new Position(0, 0);

    CompletableFuture<ApplyWorkspaceEditResponse> future = client.applyEdit(workspaceEditParams);
    future.thenRun(
        () -> {
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

  /**
   * Given a file uri, returns buffer file uri Where filename is modified with a prepended ~
   * file:///C:/test/origin_file.soar -> file:///C:/test/~origin_file.soar
   */
  private String getBufferedUri(String uri) {
    int index = uri.lastIndexOf("/") + 1;
    return uri.substring(0, index) + "~" + uri.substring(index);
  }
}

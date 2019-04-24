package com.soartech.soarls;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.soartech.soarls.analysis.Analysis;
import com.soartech.soarls.analysis.FileAnalysis;
import com.soartech.soarls.analysis.ProcedureCall;
import com.soartech.soarls.analysis.ProcedureDefinition;
import com.soartech.soarls.analysis.Production;
import com.soartech.soarls.analysis.ProjectAnalysis;
import com.soartech.soarls.analysis.VariableDefinition;
import com.soartech.soarls.analysis.VariableRetrieval;
import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.util.Debouncer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Path;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkParams;
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
  private final ConcurrentHashMap<URI, ProjectAnalysis> analyses = new ConcurrentHashMap<>();

  /** Handles to diagnostics information that is currently being computed. */
  private final ConcurrentHashMap<URI, CompletableFuture<ProjectAnalysis>> pendingAnalyses =
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
  private URI activeEntryPoint = null;

  // The path of the currently active workspace.
  private Path workspaceRootPath = null;

  private LanguageClient client;

  /**
   * Configuration sent by the client in a workspace/didChangeConfiguration notification. It is
   * received by the workspace service and then updated here. This object can be replaced at any
   * time, so it should always be accessed via this class; never store a reference to it, as it may
   * be out of date.
   */
  private Configuration config = new Configuration();

  /**
   * Retrieve the most recently completed analysis for the given entry point. If an analysis has
   * already been completed then the future will resolve immediately; otherwise, you may assume that
   * the analysis is in progress and the future will resolve eventually.
   */
  public CompletableFuture<ProjectAnalysis> getAnalysis(URI uri) {
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

  /** Get the URI of the file to use for Tcl expansions. */
  private URI tclExpansionUri() {
    return workspaceRootPath.resolve(config.tclExpansionFile).toUri();
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    TextDocumentItem doc = params.getTextDocument();
    SoarFile soarFile = documents.open(doc);

    if (activeEntryPoint == null) {
      this.setEntryPoint(soarFile.uri);
    }
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {}

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    URI uri = uri(params.getTextDocument().getUri());
    documents.close(uri);
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    URI uri = uri(params.getTextDocument().getUri());
    documents.applyChanges(params);

    // If the file that changed was never sourced, then there is no
    // need to re-analyse the project. This mainly prevents the Tcl
    // expansion buffer from triggering a continuous loop of analyses.
    boolean changeAffectsAnalysis =
        Optional.ofNullable(getAnalysis(activeEntryPoint).getNow(null))
            .map(analysis -> analysis.files.containsKey(uri))
            .orElse(false);
    if (changeAffectsAnalysis) {
      scheduleAnalysis();
    }
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
              URI uri = uri(params.getTextDocument().getUri());
              SoarFile file = analysis.file(uri).file;
              TclAstNode node = file.tclNode(params.getPosition());

              Location location = null;
              if (node.getType() == TclAstNode.NORMAL_WORD) {
                TclAstNode parent = node.getParent();

                // if parent is QUOTED_WORD then currently on an SP command -> expand the code in
                // buffer
                // if parent is COMMAND_WORD then go to procedure definition if found.
                if (parent.getType() == TclAstNode.QUOTED_WORD) {
                  // this is currently commented out as we do not want a buffer created for each
                  // file
                  // location = goToDefinitionExpansion(analysis, file, parent);
                } else if (parent.getType() == TclAstNode.COMMAND_WORD
                    || parent.getType() == TclAstNode.COMMAND) {
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
              URI uri = uri(params.getTextDocument().getUri());
              SoarFile file = analysis.file(uri).file;
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
                      .peek(item -> item.setKind(itemKind))
                      .collect(toList());

              return Either.forLeft(completions);
            });
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
      TextDocumentPositionParams params) {
    URI uri = uri(params.getTextDocument().getUri());
    final SoarFile file = documents.get(uri);
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
    URI uri = uri(params.getTextDocument().getUri());
    SoarFile file = documents.get(uri);
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
              URI uri = uri(params.getTextDocument().getUri());
              FileAnalysis analysis = projectAnalysis.file(uri);
              SoarFile file = analysis.file;
              TclAstNode hoveredNode = file.tclNode(params.getPosition());

              // If hovering over a production, populate bufferFile with expanded code
              Production currentProduction = analysis.production(params.getPosition());
              if (currentProduction != null) {
                createFileWithContent(tclExpansionUri(), "sp {" + currentProduction.body + "}\n");
              }

              Function<TclAstNode, Hover> hoverVariable =
                  node -> {
                    VariableRetrieval retrieval = analysis.variableRetrievals.get(node);
                    if (retrieval == null) return null;
                    String value = retrieval.definition.map(def -> def.value).orElse("");
                    Range range = file.rangeForNode(node);
                    return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
                  };

              Function<ProcedureCall, Optional<String>> hoverText =
                  call ->
                      call.definition
                          .flatMap(def -> def.commentText)
                          .map(
                              comment ->
                                  Arrays.stream(comment.split("\n"))
                                      .map(line -> line.replaceAll("\\s*#\\s?", "")))
                          .flatMap(
                              lines ->
                                  config.fullCommentHover
                                      ? Optional.of(lines.collect(joining("\n")))
                                      : lines.filter(line -> !line.isEmpty()).findFirst());

              Function<TclAstNode, Hover> hoverProcedureCall =
                  node ->
                      analysis
                          .procedureCall(node)
                          .filter(call -> call.callSiteAst.getChildren().get(0) == node)
                          .map(
                              call -> {
                                String value =
                                    hoverText.apply(call).orElse(file.getNodeInternalText(node));
                                List<TclAstNode> callChildren = call.callSiteAst.getChildren();
                                Range range =
                                    new Range(
                                        file.position(callChildren.get(0).getStart()),
                                        file.position(
                                            callChildren.get(callChildren.size() - 1).getEnd()));
                                return new Hover(
                                    new MarkupContent(MarkupKind.PLAINTEXT, value), range);
                              })
                          .orElse(null);

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
              URI uri = uri(params.getTextDocument().getUri());
              FileAnalysis fileAnalysis = analysis.file(uri);
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
                    analysis
                        .variableRetrievals
                        .keySet()
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

    // Construct a signature including the first N arguments.
    BiFunction<ProcedureDefinition, Integer, SignatureInformation> makeSignatureInfo =
        (def, argsIncluded) -> {
          String label =
              def.name
                  + " "
                  + def.arguments
                      .stream()
                      .limit(argsIncluded)
                      .map(arg -> arg.name)
                      .collect(joining(" "));
          List<ParameterInformation> parameters =
              def.arguments
                  .stream()
                  .limit(argsIncluded)
                  .map(arg -> new ParameterInformation(arg.name))
                  .collect(toList());

          return new SignatureInformation(label, "", parameters);
        };

    // Construct a signature for each valid number of arguments.
    BiFunction<ProcedureCall, Integer, Optional<SignatureHelp>> makeSignatureHelp =
        (call, cursorOffset) -> {
          ProcedureDefinition def = call.definition.orElse(null);
          if (def == null) return Optional.empty();
          long requiredArgs =
              def.arguments.stream().filter(arg -> !arg.defaultValue.isPresent()).count();
          int totalArgs = def.arguments.size();
          List<SignatureInformation> signatures =
              IntStream.rangeClosed((int) requiredArgs, totalArgs)
                  .mapToObj(argsIncluded -> makeSignatureInfo.apply(def, argsIncluded))
                  .collect(toList());

          int argumentsFilledIn = call.callSiteAst.getChildren().size() - 1;
          int activeSignature = Math.min(argumentsFilledIn, totalArgs) - (int) requiredArgs;

          Supplier<Integer> getActiveParameter =
              () -> {
                List<TclAstNode> children = call.callSiteAst.getChildren();
                for (int i = 1; i < children.size(); ++i) {
                  TclAstNode param = children.get(i);
                  if (param.getStart() <= cursorOffset && cursorOffset <= param.getEnd()) {
                    return i - 1;
                  }
                }
                // Default to a number that is greater than the number of children; otherwise, it
                // defaults to 0 and highlights the first parameter when the curser is in between
                // other parameters.
                return children.size();
              };
          Integer activeParameter = getActiveParameter.get();

          return Optional.of(new SignatureHelp(signatures, activeSignature, activeParameter));
        };

    URI uri = uri(params.getTextDocument().getUri());
    return getAnalysis(activeEntryPoint)
        .thenApply(project -> project.file(uri))
        .thenApply(
            analysis -> {
              TclAstNode cursorNode = analysis.file.tclNode(params.getPosition());
              int cursorOffset = analysis.file.offset(params.getPosition());
              return analysis
                  .procedureCall(cursorNode)
                  .flatMap(call -> makeSignatureHelp.apply(call, cursorOffset))
                  .orElseGet(SignatureHelp::new);
            });
  }

  /** Wire up a reference to the client, so that we can send diagnostics. */
  void connect(LanguageClient client) {
    this.client = client;
  }

  void setWorkspaceRootPath(Path workspaceRootPath) {
    this.workspaceRootPath = workspaceRootPath;
  }

  /** Set the entry point of the Soar agent - the first file that should be sourced. */
  void setEntryPoint(URI uri) {
    this.activeEntryPoint = uri;
    scheduleAnalysis();
  }

  void setConfiguration(Configuration config) {
    this.config = config;
    if (config.debounceTime != null) {
      LOG.info("Updating debounce time");
      debouncer.setDelay(Duration.ofMillis(config.debounceTime));
      scheduleAnalysis();
    }
  }

  /**
   * Schedule an analysis run. It is safe to call this multiple times in quick succession, because
   * the requests are debounced.
   */
  private void scheduleAnalysis() {
    if (this.activeEntryPoint == null) return;

    CompletableFuture<ProjectAnalysis> future =
        pendingAnalyses.computeIfAbsent(this.activeEntryPoint, key -> new CompletableFuture<>());

    if (future.isDone()) {
      return;
    }
    debouncer.submit(
        () -> {
          try {
            ProjectAnalysis analysis = Analysis.analyse(this.documents, this.activeEntryPoint);
            reportDiagnostics(analysis);
            future.complete(analysis);
          } catch (Exception e) {
            future.completeExceptionally(e);
          }
        });
  }

  /** Report diagnostics from the given analysis. */
  private void reportDiagnostics(ProjectAnalysis projectAnalysis) {
    for (FileAnalysis fileAnalysis : projectAnalysis.files.values()) {
      final List<Diagnostic> diagnosticList = new ArrayList<>();

      diagnosticList.addAll(fileAnalysis.diagnostics);

      // add any diagnostics found while initially parsing file
      diagnosticList.addAll(fileAnalysis.file.getDiagnostics());

      PublishDiagnosticsParams diagnostics =
          new PublishDiagnosticsParams(fileAnalysis.uri.toString(), diagnosticList);
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
    FileAnalysis fileAnalysis = projectAnalysis.file(file.uri);

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
            .file(file.uri)
            .productions
            .getOrDefault(commandNode, ImmutableList.of())
            .stream()
            .map(production -> "sp {" + production.body + "}\n")
            .collect(joining("\n"));

    if (expandedSoar == null || expandedSoar.isEmpty()) return null;
    // add new line for separation from any existing code
    // when appending to the top of the file
    expandedSoar += "\n\n";

    URI new_uri = getBufferedUri(file.uri);
    Position create_position = createFileWithContent(new_uri, expandedSoar);

    return new Location(new_uri.toString(), new Range(create_position, create_position));
  }

  /**
   * Create file with given contents If file already exists prepend contents to beginning of file
   */
  private Position createFileWithContent(URI file_uri, String content) {
    // create new "buffer" file to show expanded soar code
    CreateFile createFile = new CreateFile(file_uri.toString(), new CreateFileOptions(true, false));
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
          edit_map.put(file_uri.toString(), edits);
          WorkspaceEdit edit = new WorkspaceEdit(edit_map);

          client.applyEdit(new ApplyWorkspaceEditParams(edit));
        });

    return start;
  }

  @Override
  public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
    URI uri = uri(params.getTextDocument().getUri());
    SoarFile file = documents.get(uri);

    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              FileAnalysis fileAnalysis = analysis.file(uri);
              List<DocumentLink> links = new ArrayList<>();

              for (Map.Entry<TclAstNode, ImmutableList<Production>> entry :
                  fileAnalysis.productions.entrySet()) {
                TclAstNode node = entry.getKey();
                TclAstNode firstNormalWord = node.getChild(TclAstNode.NORMAL_WORD);
                for (Production production : entry.getValue()) {
                  Range highlightRange = production.location.getRange();
                  if (firstNormalWord != null) highlightRange = file.rangeForNode(firstNormalWord);

                  DocumentLink link = new DocumentLink(highlightRange);
                  link.setTarget(tclExpansionUri().toString());
                  links.add(link);
                }
              }

              // create the buffer file if we have a link to go to
              if (links.size() > 0) {
                createFileWithContent(tclExpansionUri(), "");
              }

              return links;
            });
  }

  /**
   * Given a file uri, returns buffer file uri Where filename is modified with a prepended ~
   * file:///C:/test/origin_file.soar -> file:///C:/test/~origin_file.soar
   */
  private URI getBufferedUri(URI uri_) {
    // TODO: The URI is being converted to a string and back as a
    // consequence of replacing usages of String with the URI class. This
    // keeps the original implementation of this function. There is
    // probably a better way to do this that makes use of the URI or Path
    // classes.
    String uri = uri_.toString();
    int index = uri.lastIndexOf("/") + 1;
    return uri(uri.substring(0, index) + "~" + uri.substring(index));
  }

  // Helpers

  /**
   * Convert a String to a URI. Constructing a URI can throw a URISyntaxException. However, a well
   * behaved LSP client should never send badly formed URIs, so it is more convenient to turn this
   * into an unchecked exception. The lsp4j library will handle them gracefully anyway.
   */
  static URI uri(String uriString) {
    try {
      return new URI(URLDecoder.decode(uriString));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}

package com.soartech.soarls;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.soartech.soarls.analysis.Analysis;
import com.soartech.soarls.analysis.FileAnalysis;
import com.soartech.soarls.analysis.ProcedureCall;
import com.soartech.soarls.analysis.ProcedureDefinition;
import com.soartech.soarls.analysis.ProjectAnalysis;
import com.soartech.soarls.analysis.VariableDefinition;
import com.soartech.soarls.analysis.VariableRetrieval;
import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.util.Debouncer;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
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

  private EntryPoints projectConfig = null;

  /**
   * The URI of the currently active entry point. The results of analysing a codebase can be
   * different depending on where we start evaluating from. In some cases, such as reporting
   * diagnostics, we can send results for all possible entry points. In other cases, such as
   * go-to-definition, we need to compute results with respect to a single entry point.
   */
  private URI activeEntryPoint = null;

  // The path of the currently active workspace.
  private URI workspaceRootUri = null;

  private LanguageClient client;

  /**
   * Configuration sent by the client in a workspace/didChangeConfiguration notification. It is
   * received by the workspace service and then updated here. This object can be replaced at any
   * time, so it should always be accessed via this class; never store a reference to it, as it may
   * be out of date.
   */
  private Configuration config = new Configuration();

  /** Retrieve the most recently completed analysis for the active entry point. */
  public CompletableFuture<ProjectAnalysis> getAnalysis() {
    return getAnalysis(activeEntryPoint);
  }

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
    URI uri = URI.create(workspaceRootUri.toString() + config.tclExpansionFile);
    return uri;
  }

  /** Retrieve the Tcl expansion file, creating it if necessary. */
  private CompletableFuture<SoarFile> tclExpansionFile() {
    return Optional.ofNullable(documents.get(tclExpansionUri()))
        .map(f -> CompletableFuture.completedFuture(f))
        .orElseGet(
            () -> {
              CreateFile createFile =
                  new CreateFile(tclExpansionUri().toString(), new CreateFileOptions(false, true));
              WorkspaceEdit edit = new WorkspaceEdit(Arrays.asList(Either.forRight(createFile)));
              ApplyWorkspaceEditParams params =
                  new ApplyWorkspaceEditParams(edit, "create expansion file");
              return client
                  .applyEdit(params)
                  .thenApply(response -> documents.get(tclExpansionUri()));
            });
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    TextDocumentItem doc = params.getTextDocument();
    SoarFile soarFile = documents.open(doc);

    if (activeEntryPoint == null) {
      this.activeEntryPoint = soarFile.uri;
      scheduleAnalysis();
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

  /**
   * We use this request as a hook to update the expanded Tcl buffer, because it is the best way we
   * have to determine the location of the cursor as well as the range in the document that is
   * selected.
   */
  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
    URI uri = uri(params.getTextDocument().getUri());

    // Collect the expanded bodies of all productions that overlap the selected range.
    Function<FileAnalysis, String> concatSelectedProductions =
        fileAnalysis -> {
          int startOffset = fileAnalysis.file.offset(params.getRange().getStart());
          int endOffset = fileAnalysis.file.offset(params.getRange().getEnd());
          return fileAnalysis
              .productions
              .entrySet()
              .stream()
              .filter(entry -> entry.getKey().getStart() <= endOffset)
              .filter(entry -> entry.getKey().getEnd() >= startOffset)
              .sorted((a, b) -> a.getKey().getStart() - b.getKey().getStart())
              .flatMap(entry -> entry.getValue().stream())
              .map(production -> "sp {" + production.body + "}\n")
              .collect(joining("\n"));
        };

    BiFunction<SoarFile, String, ApplyWorkspaceEditParams> makeParams =
        (file, contents) ->
            new ApplyWorkspaceEditParams(
                new WorkspaceEdit(
                    singletonMap(
                        file.uri.toString(),
                        Arrays.asList(new TextEdit(file.rangeForNode(file.ast), contents)))));

    // Given some contents, construct an action to replace the contents of the tcl expansion file.
    Function<String, CompletableFuture<ApplyWorkspaceEditResponse>> editFile =
        contents ->
            tclExpansionFile()
                .thenCompose(file -> client.applyEdit(makeParams.apply(file, contents)));

    // Try to retrieve expanded production bodies and modify the expansion file; if this fails,
    // that's okay. Then, we return our actual results.
    return getAnalysis(activeEntryPoint)
        .thenComposeAsync(
            analysis ->
                analysis
                    .file(uri)
                    .map(concatSelectedProductions)
                    .map(editFile)
                    .orElse(CompletableFuture.completedFuture(null)))
        .thenApply(
            response ->
                Arrays.asList(Either.forLeft(new Command("Log source tree", "log-source-tree"))));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(TextDocumentPositionParams params) {

    URI uri = uri(params.getTextDocument().getUri());

    Function<ProjectAnalysis, List<Location>> findDefinition =
        analysis ->
            analysis
                .file(uri)
                .map(file -> file.file)
                .flatMap(
                    file -> {
                      TclAstNode node = file.tclNode(params.getPosition());
                      switch (node.getType()) {
                        case TclAstNode.NORMAL_WORD:
                          return goToDefinitionProcedure(analysis, file, node);

                        case TclAstNode.VARIABLE:
                        case TclAstNode.VARIABLE_NAME:
                          return goToDefinitionVariable(analysis, file, node);

                        default:
                          return Optional.empty();
                      }
                    })
                .map(location -> singletonList(location))
                .orElseGet(ArrayList::new);

    return getAnalysis().thenApply(findDefinition.andThen(Either::forLeft));
  }

  /**
   * Find the procedure definition of the given node. Returns the location of the procedure
   * definition or null if it doesn't exist.
   */
  private Optional<Location> goToDefinitionProcedure(
      ProjectAnalysis projectAnalysis, SoarFile file, TclAstNode node) {
    String name = file.getNodeInternalText(node);
    return Optional.ofNullable(projectAnalysis.procedureDefinitions.get(name))
        .map(def -> def.location);
  }

  private Optional<Location> goToDefinitionVariable(
      ProjectAnalysis projectAnalysis, SoarFile file, TclAstNode node) {
    FileAnalysis fileAnalysis = projectAnalysis.file(file.uri).orElse(null);

    LOG.trace("Looking up definition of variable at node {}", node);
    return fileAnalysis.variableRetrieval(node).flatMap(r -> r.definition).map(def -> def.location);
  }

  @Override
  public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              // Get original name for lookups
              URI thisFileUri = uri(params.getTextDocument().getUri());
              FileAnalysis thisFileAnalysis = analysis.file(thisFileUri).orElse(null);
              SoarFile file = thisFileAnalysis.file;
              TclAstNode node = file.tclNode(params.getPosition());
              String oldName = file.contents.substring(node.getStart(), node.getEnd());

              // Final set of edits
              HashMap<String, List<TextEdit>> textEdits = new HashMap<>();

              // Assume variables can be accessed between files, so enumerate over them
              for (FileAnalysis otherFileAnalysis : analysis.files.values()) {
                SoarFile otherFile = otherFileAnalysis.file;
                String otherFileUriString = otherFile.uri.toString();
                TclAstNode root = otherFile.ast;
                String contents = otherFile.contents;
                // only attempt to rename leaf nodes like NORMAL_WORD or VARIABLE_NAME
                for (TclAstNode childNode : root.leafNodes()) {
                  int start = childNode.getStart();
                  int end = childNode.getEnd();
                  if (contents.substring(start, end).equals(oldName)) {
                    Range range = new Range(otherFile.position(start), otherFile.position(end));
                    textEdits.putIfAbsent(otherFileUriString, new ArrayList<>());
                    textEdits.get(otherFileUriString).add(new TextEdit(range, params.getNewName()));
                  }
                }
              }

              return new WorkspaceEdit(textEdits);
            });
  }

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
      CompletionParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              URI uri = uri(params.getTextDocument().getUri());
              SoarFile file = documents.get(uri);
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

    Function<FileAnalysis, Hover> getHover =
        analysis -> {
          Function<TclAstNode, Hover> hoverVariable =
              node -> {
                VariableRetrieval retrieval = analysis.variableRetrievals.get(node);
                if (retrieval == null) return null;
                String value = retrieval.definition.map(def -> def.value).orElse("");
                Range range = analysis.file.rangeForNode(node);
                return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, value), range);
              };

          String prefix = config.renderHoverVerbatim ? "    " : "";

          Function<ProcedureCall, Optional<String>> hoverText =
              call ->
                  call.definition
                      .flatMap(def -> def.commentText)
                      .map(
                          comment ->
                              Arrays.stream(comment.split("\n"))
                                  .map(line -> line.replaceAll("\\s*#\\s?", ""))
                                  .map(line -> prefix + line))
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
                            SoarFile file = analysis.file;
                            String value =
                                hoverText.apply(call).orElse(file.getNodeInternalText(node));
                            List<TclAstNode> callChildren = call.callSiteAst.getChildren();
                            Range range =
                                new Range(
                                    file.position(callChildren.get(0).getStart()),
                                    file.position(callChildren.get(0).getEnd()));
                            return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value), range);
                          })
                      .orElse(null);

          SoarFile file = analysis.file;
          TclAstNode hoveredNode = file.tclNode(params.getPosition());

          switch (hoveredNode.getType()) {
            case TclAstNode.VARIABLE:
              return hoverVariable.apply(hoveredNode);
            case TclAstNode.VARIABLE_NAME:
              return hoverVariable.apply(hoveredNode.getParent());
            default:
              return hoverProcedureCall.apply(hoveredNode);
          }
        };

    return getAnalysis(activeEntryPoint)
        .thenApply(
            projectAnalysis -> {
              URI uri = uri(params.getTextDocument().getUri());
              return projectAnalysis.file(uri).map(getHover).orElse(null);
            });
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    return getAnalysis(activeEntryPoint)
        .thenApply(
            analysis -> {
              URI uri = uri(params.getTextDocument().getUri());
              FileAnalysis fileAnalysis = analysis.file(uri).orElse(null);
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
        .thenApply(project -> project.file(uri).orElse(null))
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

  void setWorkspaceRootUri(URI workspaceRootUri) {
    this.workspaceRootUri = workspaceRootUri;
  }

  /** Set the entry point of the Soar agent - the first file that should be sourced. */
  void setProjectConfig(EntryPoints projectConfig) {
    this.projectConfig = projectConfig;
    this.activeEntryPoint = workspaceRootUri.resolve(projectConfig.activeEntryPoint().path);
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
            ProjectAnalysis analysis =
                Analysis.analyse(this.projectConfig, this.documents, this.activeEntryPoint);
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

  @Override
  public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
    URI uri = uri(params.getTextDocument().getUri());

    Function<FileAnalysis, List<DocumentLink>> collectLinks =
        fileAnalysis -> {
          SoarFile file = fileAnalysis.file;
          return fileAnalysis
              .productions
              .keySet()
              .stream()
              .map(key -> key.getChild(TclAstNode.NORMAL_WORD))
              .map(node -> new DocumentLink(file.rangeForNode(node)))
              .peek(link -> link.setTarget(tclExpansionUri().toString()))
              .collect(toList());
        };

    if (config.hyperlinkExpansionFile) {
      return getAnalysis(activeEntryPoint)
          .thenApply(analysis -> analysis.file(uri).map(collectLinks).orElse(null));
    } else {
      return CompletableFuture.completedFuture(new ArrayList<>());
    }
  }

  // Helpers

  /**
   * Convert a String to a URI. Constructing a URI can throw a URISyntaxException. However, a well
   * behaved LSP client should never send badly formed URIs, so it is more convenient to turn this
   * into an unchecked exception. The lsp4j library will handle them gracefully anyway.
   */
  static URI uri(String uriString) {
    try {
      return new URI(uriString).normalize();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  void printAnalysisTree(ProjectAnalysis analysis, PrintStream stream, URI uri, String prefix) {
    String linePrefix = prefix.substring(0, prefix.length() - 4) + "|-- ";
    stream.print(linePrefix + workspaceRootUri.relativize(uri));
    FileAnalysis fileAnalysis = analysis.file(uri).orElse(null);
    if (fileAnalysis == null) {
      stream.println(" MISSING");
    } else {
      stream.println();
      for (int i = 0; i != fileAnalysis.filesSourced.size(); ++i) {
        boolean isLast = i == fileAnalysis.filesSourced.size() - 1;
        URI sourcedUri = fileAnalysis.filesSourced.get(i);
        printAnalysisTree(analysis, stream, sourcedUri, prefix + (isLast ? "    " : "|   "));
      }
    }
  }
}

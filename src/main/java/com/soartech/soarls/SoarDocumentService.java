package com.soartech.soarls;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.soartech.soarls.ProjectConfiguration.EntryPoint;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
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
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.SymbolInformation;
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
   * scheduleAnalysis method is the entry point for how this information gets generated.
   */
  private final ConcurrentHashMap<URI, ProjectAnalysis> analyses = new ConcurrentHashMap<>();

  /** Handles to diagnostics information that is currently being computed. */
  private final ConcurrentHashMap<URI, CompletableFuture<ProjectAnalysis>> pendingAnalyses =
      new ConcurrentHashMap<>();

  /**
   * For each entry point, we debounce analysis requests so that when multiple edits are made in
   * quick succession we only perform an analysis once.
   *
   * <p>TODO: Combine this and the other hash maps that are keyed on URIs into a single structure.
   */
  private final ConcurrentHashMap<URI, Debouncer> debouncers = new ConcurrentHashMap<>();

  private ProjectConfiguration projectConfig = new ProjectConfiguration();

  /**
   * The URI of the currently active entry point. The results of analysing a codebase can be
   * different depending on where we start evaluating from. In some cases, such as reporting
   * diagnostics, we can send results for all possible entry points. In other cases, such as
   * go-to-definition, we need to compute results with respect to a single entry point.
   */
  private Optional<URI> activeEntryPoint = Optional.empty();

  // The path of the currently active workspace.
  private URI workspaceRootUri = null;

  private LanguageClient client = null;

  /**
   * Configuration sent by the client in a workspace/didChangeConfiguration notification. It is
   * received by the workspace service and then updated here. This object can be replaced at any
   * time, so it should always be accessed via this class; never store a reference to it, as it may
   * be out of date.
   */
  private Configuration config = new Configuration();

  /** Retrieve a stream of the analyses for all entry points. */
  public CompletableFuture<Stream<ProjectAnalysis>> getAllAnalyses() {
    Collector<CompletableFuture<ProjectAnalysis>, ?, CompletableFuture<Stream<ProjectAnalysis>>>
        collector =
            Collectors.reducing(
                CompletableFuture.completedFuture(Stream.empty()),
                a -> a.thenApply(Stream::of),
                (first, second) -> first.thenCombine(second, Stream::concat));

    return projectConfig
        .entryPoints()
        .map(entryPoint -> workspaceRootUri.resolve(entryPoint.path))
        .map(uri -> getAnalysis(uri))
        .filter(Objects::nonNull)
        .collect(collector);
  }

  /** Retrieve the most recently completed analysis for the active entry point. */
  public Optional<CompletableFuture<ProjectAnalysis>> getAnalysis() {
    return activeEntryPoint.map(entry -> getAnalysis(entry));
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

  /**
   * Retrieve the most up-to-date analyses for the given entry point, waiting for the currently
   * executing one to complete if necessary.
   *
   * <p>This should not generally be used, since it could block for a long time. This is exposed
   * only for the purposes of unit tests.
   */
  public ProjectAnalysis waitForAnalysis(URI uri) throws InterruptedException, ExecutionException {
    CompletableFuture<ProjectAnalysis> pending = pendingAnalyses.get(uri);
    if (pending != null) {
      analyses.put(uri, pending.get());
    }
    ProjectAnalysis analysis = analyses.get(uri);
    if (analysis == null) {
      throw new NullPointerException("Analyses should never be null.");
    }
    return analysis;
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
    documents.open(doc);
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
    List<ProjectAnalysis> analysisAffected =
        analyses
            .values()
            .stream()
            .filter(analysis -> analysis.sourcedUris.contains(uri))
            .collect(toList());
    for (ProjectAnalysis analysis : analysisAffected) {
      scheduleAnalysis(analysis.entryPointUri);
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
                .thenCompose(
                    file ->
                        client
                            // We first clear the contents so that the client will scroll to the top
                            // of the file, then we insert the actual contents. This ensures that
                            // after the edit, the contents of the file are in view.
                            .applyEdit(makeParams.apply(file, ""))
                            .thenComposeAsync(
                                response -> client.applyEdit(makeParams.apply(file, contents))));

    // Try to retrieve expanded production bodies and modify the expansion file; if this fails,
    // that's okay. Then, we return our actual results.
    return getAllAnalyses()
        .thenComposeAsync(
            analyses ->
                analyses
                    .map(analysis -> analysis.file(uri))
                    .filter(f -> f.isPresent())
                    .map(f -> f.get())
                    .findFirst()
                    .map(concatSelectedProductions)
                    .map(editFile)
                    .orElse(CompletableFuture.completedFuture(null)))
        .thenApply(
            response ->
                Arrays.asList(
                    Either.forLeft(new Command("Log source tree", "log-source-tree")),
                    Either.forLeft(
                        new Command(
                            "Log syntax tree", "log-syntax-tree", Arrays.asList(uri.toString())))));
  }

  /**
   * Note: It is recommended thot code lenses be created and resolved in two stages. However, since
   * at the present we are only sending one code lens per file, there's not much reason to worry
   * about performance in this case. If this changes, then we should implement the codeLens/resolve
   * request.
   *
   * <p>See
   * https://microsoft.github.io//language-server-protocol/specifications/specification-3-14/#codeLens_resolve
   * for more information.
   */
  @Override
  public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
    URI uri = uri(params.getTextDocument().getUri());
    return getAllAnalyses()
        .thenApply(
            analyses -> {
              String entryPointList =
                  analyses
                      .filter(analysis -> analysis.files.containsKey(uri))
                      .map(analysis -> analysis.entryPoint.name)
                      .collect(joining(", "));

              return Arrays.asList(
                  new CodeLens(
                      range(0, 0, 0, 0), new Command("Member of " + entryPointList, ""), null));
            });
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

    return getAllAnalyses()
        .thenApply(
            analyses ->
                analyses.flatMap(findDefinition.andThen(List::stream)).distinct().collect(toList()))
        .thenApply(Either::forLeft);
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
    return mapAnalysis(
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
    URI uri = uri(params.getTextDocument().getUri());
    SoarFile file = documents.get(uri);
    int lineNumber = params.getPosition().getLine();
    String line = file.line(lineNumber);

    int cursor = params.getPosition().getCharacter();
    if (cursor > line.length()) {
      return CompletableFuture.completedFuture(null);
    }
    // Find the start of the token and determine its type.
    CompletionItemKind kind = null;
    int start = cursor;
    outerloop:
    for (start = cursor; start > 0; --start) {
      switch (line.charAt(start - 1)) {
        case '$':
          kind = CompletionItemKind.Constant;
          break outerloop;
        case ' ':
        case '[':
          kind = CompletionItemKind.Function;
          break outerloop;
        default:
          break;
      }
    }

    CompletionItemKind itemKind = kind;
    int itemStart = start;
    String prefix = line.substring(itemStart, cursor);
    Range replacementRange = range(lineNumber, itemStart, lineNumber, cursor);

    return mapAnalysis(
        analysis -> {
          Stream<CompletionItem> completions = null;
          if (itemKind == CompletionItemKind.Constant) {
            completions = CompletionRequest.completeVariable(analysis, prefix, replacementRange);
          } else if (itemKind == CompletionItemKind.Function) {
            completions = CompletionRequest.completeProcedure(analysis, prefix, replacementRange);
          } else {
            return null;
          }

          return Either.forLeft(completions.collect(toList()));
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

  /**
   * We implement hover for two cases: variable retrievals and procedure calls. We select an
   * implementation based on the type of the AST node.
   *
   * <p>In the case of variables, we consider that running the codebase from different entry points
   * could produce different results. Therefore, we retrieve a variable's value with respect to each
   * of the analysis results, and then deduplicate. If all code paths produced the same value, then
   * we return a single result. Otherwise, we return a multi-line result where each line is
   * "entry_point_name: variable_value".
   *
   * <p>For procedure calls, we just use the analysis for the active entry point. Since hover text
   * for procedure calls shows the full documentation of the procedure, it wouldn't make sense to
   * concatenate multiple results even if they differed (which probably wouldn't happen in most
   * codebases anyway).
   */
  @Override
  public CompletableFuture<Hover> hover(TextDocumentPositionParams params) {
    URI uri = uri(params.getTextDocument().getUri());
    SoarFile file = documents.get(uri);
    TclAstNode hoveredNode = file.tclNode(params.getPosition());

    ////////
    // Helpers for variable hovers.

    // Retrieve a variable value given a particular analysis. The results of all these will be
    // merged.
    Function<ProjectAnalysis, String> getHoverValue =
        projectAnalysis ->
            projectAnalysis
                .file(uri)
                .flatMap(
                    fileAnalysis -> {
                      TclAstNode node = fileAnalysis.file.tclNode(params.getPosition());
                      return fileAnalysis
                          .variableRetrieval(node)
                          .flatMap(retrieval -> retrieval.definition)
                          .map(def -> def.value);
                    })
                .orElse("<NOT SET>");

    // Get the hover value with respect to each analysis, then combine them. If there are multiple
    // values, then we show each of them, but if they all agree then we only show a single value.
    Function<Stream<ProjectAnalysis>, Hover> hoverVariable =
        analyses -> {
          Map<String, List<ProjectAnalysis>> values = analyses.collect(groupingBy(getHoverValue));
          // If all code paths either set the same value or none at all, then just show the value.
          if (values.containsKey("<NOT SET>") && values.size() == 2) {
            values.remove("<NOT SET>");
          }
          Range range =
              hoveredNode.getType() == TclAstNode.VARIABLE
                  ? file.rangeForNode(hoveredNode)
                  : file.rangeForNode(hoveredNode.getParent());
          String value =
              values.size() == 1
                  ? values.keySet().stream().findFirst().orElse(null)
                  : values
                      .entrySet()
                      .stream()
                      .flatMap(
                          entry ->
                              entry
                                  .getValue()
                                  .stream()
                                  .map(analysis -> analysis.entryPoint.name)
                                  .map(name -> name != null ? name : "<UNNAMED ENTRY POINT>")
                                  .map(name -> name + ": " + entry.getKey()))
                      .sorted()
                      .collect(joining("\n"));
          return new Hover(asList(Either.forRight(new MarkedString("raw", value))), range);
        };

    ////////
    // Helpers for procedure hovers.

    // If we are configured to do so, then we prefix each line with four spaces so that markdown
    // renderers treat the text as verbatim.
    String prefix = config.renderHoverVerbatim ? "    " : "";

    // Format a procedure call for the hover tooltip. We strip leading `#` comment characters and
    // optionally filter to just the first line.
    Function<ProcedureCall, Optional<String>> hoverText =
        call ->
            call.definition
                .flatMap(def -> def.commentText)
                .map(
                    comment ->
                        Arrays.stream(comment.split("\n"))
                            .map(line -> line.replaceFirst("^\\s*#\\s?", ""))
                            .map(line -> prefix + line))
                .flatMap(
                    lines ->
                        config.fullCommentHover
                            ? Optional.of(lines.collect(joining("\n")))
                            : lines.filter(line -> !line.isEmpty()).findFirst());

    Function<FileAnalysis, Optional<Hover>> hoverProcedureCallFile =
        fileAnalysis -> {
          TclAstNode node = fileAnalysis.file.tclNode(params.getPosition());
          return fileAnalysis
              .procedureCall(node)
              .filter(call -> call.callSiteAst.getChildren().get(0) == node)
              .map(
                  call -> {
                    String value =
                        hoverText.apply(call).orElse(fileAnalysis.file.getNodeInternalText(node));
                    List<TclAstNode> callChildren = call.callSiteAst.getChildren();
                    Range range = fileAnalysis.file.rangeForNode(callChildren.get(0));
                    return config.renderHoverVerbatim
                        ? new Hover(new MarkupContent(MarkupKind.MARKDOWN, value), range)
                        : new Hover(asList(Either.forRight(new MarkedString("raw", value))), range);
                  });
        };

    Function<ProjectAnalysis, Hover> hoverProcedureCall =
        analysis -> analysis.file(uri).flatMap(hoverProcedureCallFile).orElse(null);

    ////////
    // Select an implementation depending on the node type.

    switch (hoveredNode.getType()) {
      case TclAstNode.VARIABLE:
      case TclAstNode.VARIABLE_NAME:
        return getAllAnalyses().thenApply(hoverVariable);
      default:
        return mapAnalysis(hoverProcedureCall);
    }
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    URI uri = uri(params.getTextDocument().getUri());

    Function<ProjectAnalysis, Stream<Location>> findReferences =
        analysis -> {
          FileAnalysis fileAnalysis = analysis.file(uri).orElse(null);
          if (fileAnalysis == null) {
            return Stream.of();
          }
          TclAstNode astNode = fileAnalysis.file.tclNode(params.getPosition());

          List<Location> references = new ArrayList<>();

          // We first try to find references to a variable.

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

          // If we successfully found a variable reference, then return now; we don't want to
          // return references to an enclosing procedure call.
          if (!references.isEmpty()) {
            return references.stream();
          }

          // If we weren't querying a variable, then try to find references to a procedure.

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

          return references.stream();
        };

    return getAllAnalyses()
        .thenApply(analyses -> analyses.flatMap(findReferences).distinct().collect(toList()));
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
          int activeSignature =
              Math.max((int) requiredArgs, Math.min(totalArgs, argumentsFilledIn))
                  - (int) requiredArgs;

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
    return mapAnalysis(
        project -> {
          FileAnalysis analysis = project.file(uri).orElse(null);
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

  void setWorkspaceRoot(URI workspaceRootUri) {
    this.workspaceRootUri = workspaceRootUri;
  }

  /** Set the entry point of the Soar agent - the first file that should be sourced. */
  void setProjectConfig(ProjectConfiguration projectConfig) {
    this.projectConfig = projectConfig;
    this.activeEntryPoint =
        projectConfig.activeEntryPoint().map(entry -> workspaceRootUri.resolve(entry.path));
    projectConfig
        .entryPoints()
        .forEach(
            entryPoint -> {
              URI uri = workspaceRootUri.resolve(entryPoint.path);
              scheduleAnalysis(uri);
            });
  }

  void setConfiguration(Configuration config) {
    this.config = config;
    if (config.debounceTime != null) {
      LOG.info("Updating debounce time");
      for (Debouncer debouncer : debouncers.values()) {
        debouncer.setDelay(Duration.ofMillis(config.debounceTime));
      }
      if (projectConfig != null) {
        projectConfig
            .entryPoints()
            .forEach(
                entryPoint -> {
                  URI uri = workspaceRootUri.resolve(entryPoint.path);
                  scheduleAnalysis(uri);
                });
      }
    }
  }

  /**
   * Schedule an analysis run. It is safe to call this multiple times in quick succession, because
   * the requests are debounced.
   */
  private void scheduleAnalysis(URI entryPointUri) {
    // TODO: this has the side effect of clearing the pendingAnalysis entry if there is one. I don't
    // like relying on that subtle behaviour.
    getAnalysis(entryPointUri);

    CompletableFuture<ProjectAnalysis> future =
        pendingAnalyses.computeIfAbsent(entryPointUri, key -> new CompletableFuture<>());

    if (future.isDone()) {
      return;
    }

    Debouncer debouncer =
        debouncers.computeIfAbsent(
            entryPointUri, uri -> new Debouncer(Duration.ofMillis(config.debounceTime)));

    // This is a clunky way to retrieve the entry point associated with a given URI.
    EntryPoint entryPoint =
        projectConfig
            .entryPoints()
            .filter(entry -> workspaceRootUri.resolve(entry.path).equals(entryPointUri))
            .findFirst()
            .orElse(null);

    debouncer.submit(
        () -> {
          try {
            LOG.info("Beginning analysis for {}", entryPointUri);
            ProjectAnalysis analysis =
                Analysis.analyse(this.projectConfig, this.documents, entryPoint, entryPointUri);
            reportDiagnostics(analysis);
            future.complete(analysis);
            LOG.info("Completed analysis for {}", entryPointUri);
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
      // NOTE: I believe that publishDiagnostics is NOT thread safe. If multiple analyses complete
      // at the same time, and they both try to send diagnostics, then the client might get into a
      // bad state. However, since this method here gets called from the analysis threadpool, which
      // has been allocated a single thread, it will not be called from mulitple threads at the same
      // time. If we change the threading model, then we MUST revisit this assumption.
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
      return mapAnalysis(analysis -> analysis.file(uri).map(collectLinks).orElse(null));
    } else {
      return CompletableFuture.completedFuture(new ArrayList<>());
    }
  }

  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
      DocumentSymbolParams params) {
    URI uri = uri(params.getTextDocument().getUri());

    return mapAnalysis(
        analysis ->
            DocumentSymbolRequest.symbols(analysis, uri)
                .map(Either::<SymbolInformation, DocumentSymbol>forRight)
                .collect(toList()));
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

  /** Create a new range. This is a shortcut to save a few characters. */
  static Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
    return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
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

  /**
   * Apply the given function in the context of the active ProjectAnalysis, if it exists and when it
   * is available.
   *
   * <p>This accounts for two kinds of uncertainty. Optional captures the possibility that the
   * active entry point does not exist (typically because the user either hasn't created or has an
   * invalid soarAgents.json file) and CompletableFuture models the possibility that the analysis
   * will exist but doesn't yet (because the analysis is still running).
   *
   * <p>For anyone interested, Optional and CompletableFuture are examples of functors.
   */
  private <T> CompletableFuture<T> mapAnalysis(Function<ProjectAnalysis, T> function) {
    return getAnalysis()
        .map(future -> future.thenApply(function))
        .orElseGet(() -> CompletableFuture.completedFuture(null));
  }
}

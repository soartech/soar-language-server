package com.soartech.soarls.analysis;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.soartech.soarls.Documents;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.tcl.TclParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.function.Function;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jsoar.kernel.Agent;
import org.jsoar.kernel.SoarException;
import org.jsoar.kernel.exceptions.SoarInterpreterException;
import org.jsoar.kernel.exceptions.SoftTclInterpreterException;
import org.jsoar.kernel.exceptions.TclInterpreterException;
import org.jsoar.util.SourceLocation;
import org.jsoar.util.commands.SoarCommand;
import org.jsoar.util.commands.SoarCommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcl.lang.Interp;
import tcl.lang.TCL;
import tcl.lang.TclException;

/** An analyser for Soar code bases. Run via the analyse() static method. */
public class Analysis {
  private static final Logger LOG = LoggerFactory.getLogger(Analysis.class);

  /**
   * The message given to a SoarException when we fail to open a file. Since we override the source
   * command, any exceptions related to reading a file end up here in this Java code instead of
   * inside the Tcl interpreter, so we need to make sure we still throw an exception so the Tcl code
   * executes as it would normally.
   */
  private static String MISSING_FILE = "MISSING_FILE";

  /**
   * The document manager may be shared with other analyses which are running concurrently. It is
   * safe for concurrent access.
   */
  private final Documents documents;

  private Stack<Path> directoryStack = new Stack<>();

  /**
   * The agent that will be used during this analysis. Whereas a normal Soar agent would just call
   * the source() method with the URI of the entry point, we will crawl the AST and evaluate one
   * command at a time, in order to be able to provide positions for errors. Keep in mind that the
   * agent is STATEFUL, and that evaluating side-effecting commands in an order that differs from
   * how they would normally be evaluated may produce different results.
   */
  private final Agent agent = new Agent();

  /**
   * Since we override the sp command to detect when it is called, we also need to keep the original
   * implementation around so we can call it and detect exceptions that are thrown.
   */
  private final SoarCommand spCommand;

  /** The values of globally accessable variables in scope. */
  private ImmutableMap<String, String> currentVariables;

  // These are essentially copies of the fields in the
  // ProjectAnalysis class, but mutable. They are used to build up
  // the analysis, and then they are copied to their immutable
  // counterpart at the end.

  private final String entryPointUri;
  private final Map<String, FileAnalysis> files = new HashMap<>();
  private final Map<String, ProcedureDefinition> procedureDefinitions = new HashMap<>();
  private final Map<ProcedureDefinition, List<ProcedureCall>> procedureCalls = new HashMap<>();
  private final Map<String, VariableDefinition> variableDefinitions = new HashMap<>();
  private final Map<VariableDefinition, List<VariableRetrieval>> variableRetrievals =
      new HashMap<>();

  private final Interp tclInterp;

  private Analysis(Documents documents, String entryPointUri) throws SoarException {
    this.documents = documents;
    this.entryPointUri = entryPointUri;

    // for performance reasons we do certain interactions directly on jsoar's internal tcl
    // interpreter
    // this is not exposed, so we forcibly grab it
    try {
      tclInterp = (Interp) FieldUtils.readField(agent.getInterpreter(), "interp", true);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Failed to get tcl interp from Soar interpreter", e);
    }

    try {
      this.directoryStack.push(Paths.get(new URI(entryPointUri)).getParent());
    } catch (Exception e) {
      LOG.error("failed to initialize directory stack", e);
    }

    agent.getInterpreter().eval("rename proc proc_internal");
    spCommand = agent.getInterpreter().getCommand("sp", null);
    currentVariables = getCurrentVariables();
  }

  /** Perform a full analysis of a project starting from the given entry point. */
  public static ProjectAnalysis analyse(Documents documents, String entryPointUri) {
    try {
      Analysis analysis = new Analysis(documents, entryPointUri);
      SoarFile file = documents.get(entryPointUri);
      analysis.analyseFile(file);
      LOG.info("Completed analysis {}", analysis);
      return analysis.toProjectAnalysis();
    } catch (Exception e) {
      LOG.error("running analysis", e);
      return null;
    }
  }

  /** Copy all accumulated state to an immutable ProjectAnalysis object. */
  private ProjectAnalysis toProjectAnalysis() {
    return new ProjectAnalysis(
        entryPointUri,
        files,
        procedureDefinitions,
        procedureCalls,
        variableDefinitions,
        variableRetrievals);
  }

  /**
   * Perform an analysis of a single file. This will be recursively called if this file sources
   * other files.
   *
   * <p>NOTE: There is currently no protection against infinite loops.
   */
  private void analyseFile(SoarFile file) throws SoarException {
    // Initialize the collections needed to make a FileAnalysis.
    Map<TclAstNode, ProcedureCall> procedureCalls = new HashMap<>();
    Map<TclAstNode, VariableRetrieval> variableRetrievals = new HashMap<>();
    List<ProcedureDefinition> procedureDefinitions = new ArrayList<>();
    List<VariableDefinition> variableDefinitions = new ArrayList<>();
    List<String> filesSourced = new ArrayList<>();
    Map<TclAstNode, List<Production>> productions = new HashMap<>();
    List<Diagnostic> diagnosticList = new ArrayList<>();

    /** Any information that needs to be accessable to the interpreter callbacks. */
    class Context {
      /** The node we are currently iterating over. */
      TclAstNode currentNode = null;

      /** The most recent comment that was iterated over. */
      TclAstNode mostRecentComment = null;
    }
    final Context ctx = new Context();

    // We need to save the commands we override so that we can
    // restore them later. It's okay if we try to get a command
    // that does not yet exist; for example, on the first pass,
    // the proc command will not have been added.
    Map<String, SoarCommand> originalCommands = new HashMap<>();
    for (String cmd : Arrays.asList("source", "sp", "proc", "pushd", "popd", "pwd")) {
      try {
        originalCommands.put(cmd, this.agent.getInterpreter().getCommand(cmd, null));
      } catch (SoarException e) {
      }
    }

    try {
      addCommand(
          "source",
          (context, args) -> {
            try {
              Path currentDirectory = this.directoryStack.peek();
              Path pathToSource = currentDirectory.resolve(args[1]);
              Path newDirectory = pathToSource.getParent();
              this.directoryStack.push(newDirectory);

              String uri = pathToSource.toUri().toString();
              filesSourced.add(uri);
              SoarFile sourcedFile = documents.get(uri);
              LOG.info("Retrieved file for {} :: {}", uri, sourcedFile);
              if (sourcedFile == null) {
                throw new SoarException(MISSING_FILE);
              } else {
                analyseFile(sourcedFile);
              }
            } catch (Exception e) {
              LOG.error("exception while tracing source", e);
              throw e;
            } finally {
              this.directoryStack.pop();
            }
            return "";
          });

      addCommand(
          "pushd",
          (context, args) -> {
            Path newDirectory = this.directoryStack.peek().resolve(args[1]);
            this.directoryStack.push(newDirectory);
            return "";
          });

      addCommand(
          "popd",
          (context, args) -> {
            this.directoryStack.pop();
            return "";
          });

      addCommand(
          "pwd",
          (context, args) -> {
            return this.directoryStack.peek().toAbsolutePath().toString();
          });

      addCommand(
          "sp",
          (context, args) -> {
            Location location = new Location(file.uri, file.rangeForNode(ctx.currentNode));
            Production production = new Production(args[1], location);
            productions.computeIfAbsent(ctx.currentNode, key -> new ArrayList<>()).add(production);
            LOG.info("Added production {} to {}", production.name, file.uri);

            // Call the original implementation, which will throw an exception if the production is
            // invalid (caught below).
            return spCommand.execute(context, args);
          });

      addCommand(
          "proc",
          (context, args) -> {
            String name = args[1];
            Location location = new Location(file.uri, file.rangeForNode(ctx.currentNode));

            char[] argsBuffer = ('"' + args[2] + '"').toCharArray();
            TclParser parser = new TclParser();
            parser.setInput(argsBuffer, 0, argsBuffer.length);
            TclAstNode procArgs = parser.parse();
            Function<TclAstNode, ProcedureDefinition.Argument> makeArgument =
                node -> {
                  List<TclAstNode> children = node.getChildren();
                  boolean hasDefault = children.size() == 2;
                  String argName =
                      hasDefault
                          ? children.get(0).getInternalText(argsBuffer)
                          : node.getInternalText(argsBuffer);
                  String defaultValue =
                      hasDefault ? children.get(1).getInternalText(argsBuffer) : null;
                  return new ProcedureDefinition.Argument(argName, defaultValue);
                };
            List<ProcedureDefinition.Argument> arguments =
                Optional.ofNullable(procArgs.getChild(TclAstNode.COMMAND))
                    .flatMap(node -> Optional.ofNullable(node.getChild(TclAstNode.QUOTED_WORD)))
                    .map(cmd -> cmd.getChildren().stream().map(makeArgument).collect(toList()))
                    .orElseGet(ArrayList::new);

            TclAstNode commentAstNode = null;
            String commentText = null;
            if (ctx.mostRecentComment != null) {
              // Note that because of the newline,
              // comments end at the beginning of the
              // following line.
              int commentEndLine = file.position(ctx.mostRecentComment.getEnd()).getLine();
              int procStartLine = file.position(ctx.currentNode.getStart()).getLine();
              if (commentEndLine == procStartLine) {
                commentAstNode = ctx.mostRecentComment;
                commentText = ctx.mostRecentComment.getInternalText(file.contents.toCharArray());
              }
            }
            ProcedureDefinition proc =
                new ProcedureDefinition(
                    name, location, arguments, ctx.currentNode, commentAstNode, commentText);
            procedureDefinitions.add(proc);
            this.procedureDefinitions.put(proc.name, proc);
            this.procedureCalls.put(proc, new ArrayList<>());

            // The args arrays has stripped away the
            // braces, so we need to add them back in
            // before we evaluate the command, but using
            // the real proc command instead.
            args[0] = "proc_internal";
            return agent.getInterpreter().eval("{" + Joiner.on("} {").join(args) + "}");
          });

      // Traverse file ast tree
      // for each COMMAND node found, if the node contains a NORMAL_WORD child
      // then add the procedure call to the file analysis
      file.traverseAstTree(
          node -> {
            if (node.expanded == null) node.expanded = file.getNodeInternalText(node);

            if (node.getType() == TclAstNode.COMMENT) {
              ctx.mostRecentComment = node;
            } else if (node.getType() == TclAstNode.COMMAND) {
              ctx.currentNode = node;
              try {
                agent.getInterpreter().eval(node.expanded);
              } catch (SoarInterpreterException ex) {
                LOG.error("interpreter exception {}", ex);
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
                // It's fine to use == for equality because this is the actual String object that
                // was used to construct the exception.
                if (ex.getMessage() == MISSING_FILE) {
                  Diagnostic diagnostic =
                      new Diagnostic(
                          file.rangeForNode(ctx.currentNode),
                          "File not found",
                          DiagnosticSeverity.Error,
                          "soar");
                  diagnosticList.add(diagnostic);
                }
              } catch (SoarException ex) {
                LOG.error("Error while evaluating Soar command: {}", node.expanded, ex);

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
            }

            switch (node.getType()) {
              case TclAstNode.COMMAND:
                {
                  // Collect values of variables that have changed.

                  ImmutableMap<String, String> newVariables = getCurrentVariables();
                  MapDifference<String, String> difference =
                      Maps.difference(currentVariables, newVariables);
                  Map<String, String> onRight = difference.entriesOnlyOnRight();
                  Map<String, MapDifference.ValueDifference<String>> differing =
                      difference.entriesDiffering();

                  for (Map.Entry<String, String> e : onRight.entrySet()) {
                    String name = e.getKey();
                    Location location = new Location(file.uri, file.rangeForNode(ctx.currentNode));
                    String value = e.getValue();
                    TclAstNode commentAstNode = null;
                    String commentText = null;
                    if (ctx.mostRecentComment != null) {
                      int commentEndLine = file.position(ctx.mostRecentComment.getEnd()).getLine();
                      int varStartLine = file.position(ctx.currentNode.getStart()).getLine();
                      if (commentEndLine == varStartLine) {
                        commentAstNode = ctx.mostRecentComment;
                        commentText =
                            ctx.mostRecentComment.getInternalText(file.contents.toCharArray());
                      }
                    }
                    VariableDefinition var =
                        new VariableDefinition(
                            name, location, ctx.currentNode, value, commentAstNode, commentText);
                    variableDefinitions.add(var);
                    this.variableDefinitions.put(var.name, var);
                    this.variableRetrievals.put(var, new ArrayList<>());
                  }

                  for (Map.Entry<String, MapDifference.ValueDifference<String>> e :
                      differing.entrySet()) {
                    String name = e.getKey();
                    Location location = new Location(file.uri, file.rangeForNode(ctx.currentNode));
                    String value = e.getValue().rightValue();
                    TclAstNode commentAstNode = null;
                    String commentText = null;
                    if (ctx.mostRecentComment != null) {
                      int commentEndLine = file.position(ctx.mostRecentComment.getEnd()).getLine();
                      int varStartLine = file.position(ctx.currentNode.getStart()).getLine();
                      if (commentEndLine == varStartLine) {
                        commentAstNode = ctx.mostRecentComment;
                        commentText =
                            ctx.mostRecentComment.getInternalText(file.contents.toCharArray());
                      }
                    }
                    VariableDefinition var =
                        new VariableDefinition(
                            name, location, ctx.currentNode, value, commentAstNode, commentText);
                    variableDefinitions.add(var);
                    this.variableDefinitions.put(var.name, var);
                    this.variableRetrievals.put(var, new ArrayList<>());
                  }

                  currentVariables = newVariables;

                  // Add diagnostics for any "soft" exceptions that were thrown and caught but not
                  // propagated up.
                  for (SoftTclInterpreterException e :
                      agent.getInterpreter().getExceptionsManager().getExceptions()) {
                    Range range = file.rangeForNode(ctx.currentNode);
                    diagnosticList.add(
                        new Diagnostic(
                            range, e.getMessage().trim(), DiagnosticSeverity.Error, "soar"));
                  }
                  agent.getInterpreter().getExceptionsManager().clearExceptions();
                }
              case TclAstNode.COMMAND_WORD:
                {
                  TclAstNode firstChild = node.getChild(TclAstNode.NORMAL_WORD);
                  if (firstChild != null) {
                    String name = file.getNodeInternalText(firstChild);
                    Location location = new Location(file.uri, file.rangeForNode(node));
                    ProcedureCall procedureCall =
                        new ProcedureCall(
                            location, firstChild, this.procedureDefinitions.get(name));

                    procedureCalls.put(firstChild, procedureCall);
                    procedureCall.definition.ifPresent(
                        def -> {
                          this.procedureCalls.get(def).add(procedureCall);
                        });
                  }
                }
                break;
              case TclAstNode.VARIABLE:
                {
                  TclAstNode nameNode = node.getChild(TclAstNode.VARIABLE_NAME);
                  if (nameNode != null) {
                    String name = file.getNodeInternalText(nameNode);
                    Location location = new Location(file.uri, file.rangeForNode(node));
                    VariableDefinition definition = this.variableDefinitions.get(name);
                    VariableRetrieval retrieval = new VariableRetrieval(location, node, definition);

                    variableRetrievals.put(node, retrieval);
                    retrieval.definition.ifPresent(
                        def -> {
                          this.variableRetrievals.get(def).add(retrieval);
                        });
                  }
                }
                break;
            }
          });

      FileAnalysis analysis =
          new FileAnalysis(
              file,
              procedureCalls,
              variableRetrievals,
              procedureDefinitions,
              variableDefinitions,
              filesSourced,
              productions,
              diagnosticList);
      this.files.put(file.uri, analysis);
    } finally {
      // Restore original commands
      for (Map.Entry<String, SoarCommand> cmd : originalCommands.entrySet()) {
        agent.getInterpreter().addCommand(cmd.getKey(), cmd.getValue());
      }
    }
  }

  private String printAstTree(SoarFile file) {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(baos, true)) {
      file.ast.printTree(ps, file.contents.toCharArray(), 4);
    }
    String data = new String(baos.toByteArray());
    return data;
  }

  interface SoarCommandExecute {
    String execute(SoarCommandContext context, String[] args) throws SoarException;
  }

  /**
   * A convenience function for implementing the SoarCommand interface by passing a lambda instead.
   */
  private void addCommand(String commandName, SoarCommandExecute implementation) {
    agent
        .getInterpreter()
        .addCommand(
            commandName,
            new SoarCommand() {
              @Override
              public String execute(SoarCommandContext context, String[] args)
                  throws SoarException {
                LOG.trace("Executing {}", Arrays.toString(args));
                return implementation.execute(context, args);
              }

              @Override
              public Object getCommand() {
                return this;
              }
            });
  }

  /** Evaluate the given command, swallowing the SoarException if it occurs. */
  String evalCommand(String command) {
    try {
      return agent.getInterpreter().eval(command);
    } catch (SoarException e) {
      LOG.trace("Evaluating command: {}", command, e);
      return "";
    }
  }

  /**
   * Get the values of all variables in global scope, where the keys are the variable names and the
   * values and the variable values.
   */
  ImmutableMap<String, String> getCurrentVariables() {
    String[] variableNames = evalCommand("info globals").split("\\s+");

    return Arrays.stream(variableNames)
        .collect(
            toImmutableMap(
                var -> var,
                var -> {
                  String result;
                  try {
                    result = tclInterp.getVar(var, TCL.GLOBAL_ONLY).toString();
                  } catch (TclException e) {
                    // If the var doesn't exist somehow, then return the empty string (seems
                    // to happen for some internal vars).
                    result = "";
                  }
                  return result;
                }));
  }
}
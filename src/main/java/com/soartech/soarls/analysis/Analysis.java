package com.soartech.soarls.analysis;

import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.soartech.soarls.Documents;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.tcl.TclAstNode;
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
import java.util.Stack;
import org.eclipse.lsp4j.Location;
import org.jsoar.kernel.Agent;
import org.jsoar.kernel.SoarException;
import org.jsoar.util.commands.SoarCommand;
import org.jsoar.util.commands.SoarCommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An analyser for Soar code bases. Run via the analyse() static method. */
public class Analysis {
  private static final Logger LOG = LoggerFactory.getLogger(Analysis.class);

  /**
   * The document manager may be shared with other analyses which are running concurrently. It is
   * safe for concurrent access.
   */
  private final Documents documents;

  private Stack<Path> directoryStack = new Stack<>();

  private final Agent agent = new Agent();

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

  private Analysis(Documents documents, String entryPointUri) {
    this.documents = documents;
    this.entryPointUri = entryPointUri;

    try {
      this.directoryStack.push(Paths.get(new URI(entryPointUri)).getParent());
    } catch (Exception e) {
      LOG.error("failed to initialize directory stack", e);
    }

    try {
      agent.getInterpreter().eval("rename proc proc_internal");
      agent.getInterpreter().eval("rename set set_internal");
    } catch (SoarException e) {
      LOG.error("initializing agent", e);
    }
  }

  /** Perform a full analysis of a project starting from the given entry point. */
  public static ProjectAnalysis analyse(Documents documents, String entryPointUri) {
    Analysis analysis = new Analysis(documents, entryPointUri);

    try {
      analysis.analyseFile(entryPointUri);
    } catch (SoarException e) {
      LOG.error("running analysis", e);
    }

    return analysis.toProjectAnalysis();
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

  private void analyseFile(String uri) throws SoarException {
    final SoarFile file = documents.get(uri);
    LOG.trace("Retrieved file for {} :: {}", uri, file);
    if (file == null) {
      return;
    }

    // Initialize the collections needed to make a FileAnalysis.
    Map<TclAstNode, ProcedureCall> procedureCalls = new HashMap<>();
    Map<TclAstNode, VariableRetrieval> variableRetrievals = new HashMap<>();
    List<ProcedureDefinition> procedureDefinitions = new ArrayList<>();
    List<VariableDefinition> variableDefinitions = new ArrayList<>();
    List<String> filesSourced = new ArrayList<>();
    List<Production> productions = new ArrayList<>();

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
    for (String cmd : Arrays.asList("source", "sp", "proc", "set")) {
      try {
        originalCommands.put(cmd, this.agent.getInterpreter().getCommand(cmd, null));
      } catch (SoarException e) {
      }
    }

    try {
      agent
          .getInterpreter()
          .addCommand(
              "source",
              soarCommand(
                  args -> {
                    try {
                      Path currentDirectory = this.directoryStack.peek();
                      Path pathToSource = currentDirectory.resolve(args[1]);
                      Path newDirectory = pathToSource.getParent();
                      this.directoryStack.push(newDirectory);

                      String path = pathToSource.toUri().toString();
                      filesSourced.add(path);
                      analyseFile(path);
                    } catch (Exception e) {
                      LOG.error("exception while tracing source", e);
                    } finally {
                      this.directoryStack.pop();
                    }
                    return "";
                  }));

      agent
          .getInterpreter()
          .addCommand(
              "pushd",
              soarCommand(
                  args -> {
                    Path newDirectory = this.directoryStack.peek().resolve(args[1]);
                    this.directoryStack.push(newDirectory);
                    return "";
                  }));

      agent
          .getInterpreter()
          .addCommand(
              "popd",
              soarCommand(
                  args -> {
                    this.directoryStack.pop();
                    return "";
                  }));

      agent
          .getInterpreter()
          .addCommand(
              "sp",
              soarCommand(
                  args -> {
                    Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                    productions.add(new Production(args[1], location));
                    return "";
                  }));

      agent
          .getInterpreter()
          .addCommand(
              "proc",
              soarCommand(
                  args -> {
                    String name = args[1];
                    Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
                    List<String> arguments = Arrays.asList(args[2].trim().split("\\s+"));
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
                        commentText =
                            ctx.mostRecentComment.getInternalText(file.contents.toCharArray());
                      }
                    }
                    ProcedureDefinition proc =
                        new ProcedureDefinition(
                            name,
                            location,
                            arguments,
                            ctx.currentNode,
                            commentAstNode,
                            commentText);
                    procedureDefinitions.add(proc);
                    this.procedureDefinitions.put(proc.name, proc);
                    this.procedureCalls.put(proc, new ArrayList<>());

                    // The args arrays has stripped away the
                    // braces, so we need to add them back in
                    // before we evaluate the command, but using
                    // the real proc command instead.
                    args[0] = "proc_internal";
                    return agent.getInterpreter().eval("{" + Joiner.on("} {").join(args) + "}");
                  }));

      agent
          .getInterpreter()
          .addCommand(
              "set",
              soarCommand(
                  args -> {
                    String name = args[1];
                    Location location = new Location(uri, file.rangeForNode(ctx.currentNode));
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
                    // We need to call the true set command, not
                    // the one that we've registered here.
                    args[0] = "set_internal";
                    String value =
                        agent.getInterpreter().eval("{" + Joiner.on("} {").join(args) + "}");
                    VariableDefinition var =
                        new VariableDefinition(
                            name, location, ctx.currentNode, value, commentAstNode, commentText);
                    variableDefinitions.add(var);
                    this.variableDefinitions.put(var.name, var);
                    this.variableRetrievals.put(var, new ArrayList<>());

                    return var.value;
                  }));

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
              } catch (SoarException e) {
                // If anything goes wrong, we just bail out
                // early. The tree traversal will continue, so
                // we might still collect useful information.
                LOG.error("Error while evaluating Soar command", e);
                return;
              }
            }

            switch (node.getType()) {
              case TclAstNode.COMMAND:
                {
                  TclAstNode quoted_word = node.getChild(TclAstNode.QUOTED_WORD);
                  // if command is production
                  if (quoted_word != null) {
                    quoted_word.expanded = getExpandedCode(file, quoted_word);
                    TclAstNode command = node.getChild(TclAstNode.NORMAL_WORD);
                    command.expanded = file.getNodeInternalText(command);
                    node.expanded = command.expanded + " \"" + quoted_word.expanded + '"';
                  }
                }
              case TclAstNode.COMMAND_WORD:
                {
                  TclAstNode firstChild = node.getChild(TclAstNode.NORMAL_WORD);
                  if (firstChild != null) {
                    String name = file.getNodeInternalText(firstChild);
                    Location location = new Location(uri, file.rangeForNode(node));
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
                    Location location = new Location(uri, file.rangeForNode(node));
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

      // String expanded_file = getExpandedFile(file);
      // String buffer_uri = getBufferedUri(uri);
      // createFileWithContent(buffer_uri, expanded_file);
      // SoarFile soarFile = new SoarFile(buffer_uri, expanded_file);
      // documents.put(soarFile.uri, soarFile);

      FileAnalysis analysis =
          new FileAnalysis(
              file,
              procedureCalls,
              variableRetrievals,
              procedureDefinitions,
              variableDefinitions,
              filesSourced,
              productions);
      this.files.put(uri, analysis);
    } finally {
      // Restore original commands
      for (Map.Entry<String, SoarCommand> cmd : originalCommands.entrySet()) {
        agent.getInterpreter().addCommand(cmd.getKey(), cmd.getValue());
      }
    }
  }

  String getExpandedFile(SoarFile file) {
    return file.ast.getChildren().stream().map(child -> child.expanded).collect(joining("\n"));
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
      return agent.getInterpreter().eval(code.substring(1, code.length() - 2));
    } catch (SoarException e) {
      return code;
    }
  }

  interface SoarCommandExecute {
    String execute(String[] args) throws SoarException;
  }

  /**
   * A convenience function for implementing the SoarCommand interface by passing a lambda instead.
   */
  static SoarCommand soarCommand(SoarCommandExecute implementation) {
    return new SoarCommand() {
      @Override
      public String execute(SoarCommandContext context, String[] args) throws SoarException {
        LOG.trace("Executing {}", Arrays.toString(args));
        return implementation.execute(args);
      }

      @Override
      public Object getCommand() {
        return this;
      }
    };
  }
}

package com.soartech.soarls.analysis;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.lsp4j.Diagnostic;

/**
 * Complete analysis information for a single file.
 *
 * <p>This structure is populated by the document service.
 */
public class FileAnalysis {
  /** The URI of the file that was analised. */
  public final String uri;

  /** The state of the file that was analised. */
  public final SoarFile file;

  /**
   * All the Tcl procedure calls that were made in this file. The keys to this map are the AST
   * command nodes. Nodes which are not commansd, such as comments and words, do not make sense
   * here.
   */
  public final ImmutableMap<TclAstNode, ProcedureCall> procedureCalls;

  /**
   * All the Tcl variable reads that were made in this file. The keys to this map are the AST
   * VARIABLE nodes.
   */
  public final ImmutableMap<TclAstNode, VariableRetrieval> variableRetrievals;

  /** Tcl procedures that were defined while sourcing this file. */
  public final ImmutableList<ProcedureDefinition> procedureDefinitions;

  /** Tcl variables that were defined while sourcing this file. */
  public final ImmutableList<VariableDefinition> variableDefinitions;

  /**
   * The URIs of the files that were sourced by this one, in the order that they were sourced.
   *
   * <p>Note: sourcing a file may also count as a procedure call.
   */
  public final ImmutableList<String> filesSourced;

  /**
   * Productions that were defined while sourcing each command in the file. The map is indexed by
   * the AST node of the command that caused the productions to be defined.
   */
  public final ImmutableMap<TclAstNode, ImmutableList<Production>> productions;

  /** Errors and warnings that were detected in this file. */
  public final ImmutableList<Diagnostic> diagnostics;

  // Helpers

  /** Get the procedure call at the given node. */
  public Optional<ProcedureCall> procedureCall(TclAstNode node) {
    return Optional.ofNullable(procedureCalls.get(node));
  }

  /** Get the variable retrieval at the given node. */
  public Optional<VariableRetrieval> variableRetrieval(TclAstNode node) {
    // Retrievals are indexed by their VARIABLE node, but most of
    // the spans of those nodes are covered by their VARIABLE_NAME
    // child.
    if (node.getType() == TclAstNode.VARIABLE_NAME) {
      node = node.getParent();
    }
    return Optional.ofNullable(variableRetrievals.get(node));
  }

  public FileAnalysis(
      SoarFile file,
      Map<TclAstNode, ProcedureCall> procedureCalls,
      Map<TclAstNode, VariableRetrieval> variableRetrievals,
      List<ProcedureDefinition> procedureDefinitions,
      List<VariableDefinition> variableDefinitions,
      List<String> filesSourced,
      Map<TclAstNode, List<Production>> productions,
      List<Diagnostic> diagnostics) {
    this.uri = file.uri;
    this.file = file;
    this.procedureCalls = ImmutableMap.copyOf(procedureCalls);
    this.variableRetrievals = ImmutableMap.copyOf(variableRetrievals);
    this.procedureDefinitions = ImmutableList.copyOf(procedureDefinitions);
    this.variableDefinitions = ImmutableList.copyOf(variableDefinitions);
    this.filesSourced = ImmutableList.copyOf(filesSourced);
    this.productions =
        productions
            .entrySet()
            .stream()
            .collect(toImmutableMap(e -> e.getKey(), e -> ImmutableList.copyOf(e.getValue())));
    this.diagnostics = ImmutableList.copyOf(diagnostics);
  }
}

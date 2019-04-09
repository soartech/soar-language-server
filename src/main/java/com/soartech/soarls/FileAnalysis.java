package com.soartech.soarls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.soartech.soarls.ProcedureCall;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.tcl.TclAstNode;
import org.jsoar.kernel.Agent;

/** Complete analysis information for a single file.
 *
 * This structure is populated by the document service.
 */
class FileAnalysis {
    public FileAnalysis(String uri) {
        this.uri = uri;
    }

    /** The URI of the file that was analised. */
    final String uri;

    /** All the Tcl procedure calls that were made in this file. The
     * keys to this map are the AST command nodes. Nodes which are not
     * commansd, such as comments and words, do not make sense here.
     */
    final Map<TclAstNode, ProcedureCall> procedureCalls = new HashMap<>();

    /** All the Tcl variable reads that were made in this file. The
     * keys to this map are the AST VARIABLE nodes. */
    final Map<TclAstNode, VariableRetrieval> variableRetrievals = new HashMap<>();

    /** Tcl procedures that were defined while sourcing this file. */
    final List<ProcedureDefinition> procedureDefinitions = new ArrayList<>();

    /** Tcl variables that were defined while sourcing this file. */
    final List<VariableDefinition> variableDefinitions = new ArrayList<>();

    /** The URIs of the files that were sourced by this one, in the
     * order that they were sourced.
     *
     * Note: sourcing a file may also count as a procedure call.
     */
    final List<String> filesSourced = new ArrayList<>();

    /** Productions that were defined while sourcing this file. */
    final List<Production> productions = new ArrayList<>();

    // Helpers

    /** Get the procedure call at the given node. */
    Optional<ProcedureCall> procedureCall(TclAstNode node) {
        return Optional.ofNullable(procedureCalls.get(node));
    }

    /** Get the variable retrieval at the given node. */
    Optional<VariableRetrieval> variableRetrieval(TclAstNode node) {
        // Retrievals are indexed by their VARIABLE node, but most of
        // the spans of those nodes are covered by their VARIABLE_NAME
        // child.
        if (node.getType() == TclAstNode.VARIABLE_NAME) {
            node = node.getParent();
        }
        return Optional.ofNullable(variableRetrievals.get(node));
    }
}

package com.soartech.soarls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    Map<TclAstNode, ProcedureCall> procedureCalls = new HashMap<>();

    /** All the Tcl variable reads that were made in this file. The
     * keys to this map are the AST VARIABLE nodes. */
    Map<TclAstNode, VariableRetrieval> variableRetrievals = new HashMap<>();

    /** Tcl procedures that were defined while sourcing this file. */
    List<ProcedureDefinition> procedureDefinitions = new ArrayList<>();

    /** Tcl variables that were defined while sourcing this file. */
    List<VariableDefinition> variableDefinitions = new ArrayList<>();

    /** The URIs of the files that were sourced by this one, in the
     * order that they were sourced.
     *
     * Note: sourcing a file may also count as a procedure call.
     */
    List<String> filesSourced = new ArrayList<>();

    /** Productions that were defined while sourcing this file. */
    List<Production> productions = new ArrayList<>();
}

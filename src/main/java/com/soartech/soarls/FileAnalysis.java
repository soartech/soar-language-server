package com.soartech.soarls;

import java.util.Map;
import java.util.List;
import org.jsoar.kernel.Agent;
import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.SoarFile;
import com.soartech.soarls.ProcedureCall;

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
    Map<TclAstNode, ProcedureCall> procedureCalls;

    /** The URIs of the files that were sourced by this one, in the
     * order that they were sourced.
     *
     * Note: sourcing a file may also count as a procedure call.
     */
    List<String> filesSourced;

    /** Productions that were defined while sourcing this file. */
    List<Production> productions;
}

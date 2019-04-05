package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import org.eclipse.lsp4j.Location;

/** A record of a procedure being called and its associated
 * metadata. */
class ProcedureCall {
    /** The location where the call occurs. */
    final Location callSiteLocation;

    /** The AST node containing the call and its arguments. */
    final TclAstNode callSiteAst;

    /** Where and how the procedure was defined. */
    ProcedureDefinition definition;

    /** The result that was returned by this call. */
    String result;

    ProcedureCall(Location location, TclAstNode ast) {
        this.callSiteLocation = location;
        this.callSiteAst = ast;
    }
}

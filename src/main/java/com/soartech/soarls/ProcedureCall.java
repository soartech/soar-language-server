package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;

/** A record of a procedure being called and its associated
 * metadata. */
class ProcedureCall {
    /** The AST node containing the call and its arguments. */
    final TclAstNode callSite;

    /** Where and how the procedure was defined. */
    ProcedureDefinition definition;

    /** The result that was returned by this call. */
    String result;

    ProcedureCall(TclAstNode callSite) {
        this.callSite = callSite;
    }
}

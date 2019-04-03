package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;

/** A record of a procedure being called and its associated
 * metadata. */
class ProcedureCall {
    /** Where and how the procedure was defined. */
    ProcedureDefinition definition;

    /** The AST node containing the call and its arguments. */
    TclAstNode callSite;

    /** The result that was returned by this call. */
    String result;
}

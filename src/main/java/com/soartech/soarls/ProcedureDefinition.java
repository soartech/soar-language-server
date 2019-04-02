package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import org.eclipse.lsp4j.Range;

/**
 * A record of a Tcl procedure that was defined and its associated
 * metadata.
 */
class ProcedureDefinition {
    /** The name of the procedure. */
    public String name;

    /** The arguments to the procedure. */
    public List<String> arguments;

    /** The URI of the file in which the procedure was defined. */
    public String uri;

    /** The range within the file at which the prodecure was defined. */
    public Range range;

    /** The syntax tree of the proc command. */
    public TclAstNode ast;
}

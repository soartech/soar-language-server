package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import org.eclipse.lsp4j.Location;

/**
 * A record of a Tcl procedure that was defined and its associated
 * metadata.
 */
class ProcedureDefinition {
    /** The name of the procedure. */
    public final String name;

    /** The location where this production was defined. */
    public final Location location;

    /** The arguments to the procedure. */
    public List<String> arguments;

    /** The syntax tree of the proc command. */
    public TclAstNode ast;

    /** The AST node of an associated comment, or null if it doesn't
     * have one. */
    public TclAstNode commentAstNode;

    /** The contents of the comment text. At least for now, this shall
     * include the leading '#' comment character. */
    public String commentText = "";

    ProcedureDefinition(String name, Location location) {
        this.name = name;
        this.location = location;
    }
}

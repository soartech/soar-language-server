package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import org.eclipse.lsp4j.Location;

/**
 * A record of a Tcl variable that was defined and its associated
 * metadata.
 */
class VariableDefinition {
    /** The name of the procedure. */
    public final String name;

    /** The location where this production was defined. */
    public final Location location;

    /** The syntax tree of the proc command. */
    public TclAstNode ast;

    /** The AST node of an associated comment, or null if it doesn't
     * have one. */
    public TclAstNode commentAstNode;

    /** The contents of the comment text. At least for now, this shall
     * include the leading '#' comment character. */
    public String commentText = "";

    VariableDefinition(String name, Location location) {
        this.name = name;
        this.location = location;
    }
}

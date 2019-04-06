package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import org.eclipse.lsp4j.Location;

/** A record of a variable's value being retrieved and its associated
 * metadata. */
class VariableRetrieval {
    /** The location where the call occurs. */
    final Location readSiteLocation;

    /** The AST node containing the call and its arguments. */
    final TclAstNode readSiteAst;

    /** Where and how the procedure was defined. */
    VariableDefinition definition;

    VariableRetrieval(Location location, TclAstNode ast) {
        this.readSiteLocation = location;
        this.readSiteAst = ast;
    }
}

package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

/** A record of a variable's value being retrieved and its associated metadata. */
class VariableRetrieval {
  /** The location where the call occurs. */
  public final Location readSiteLocation;

  /** The AST node containing the call and its arguments. */
  public final TclAstNode readSiteAst;

  /** Where and how the procedure was defined. */
  public final Optional<VariableDefinition> definition;

  VariableRetrieval(Location location, TclAstNode ast, VariableDefinition definition) {
    this.readSiteLocation = location;
    this.readSiteAst = ast;
    this.definition = Optional.ofNullable(definition);
  }
}

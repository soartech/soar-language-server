package com.soartech.soarls.analysis;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

/** A record of a procedure being called and its associated metadata. */
public class ProcedureCall {
  /** The location where the call occurs. */
  public final Location callSiteLocation;

  /** The AST node containing the call and its arguments. */
  public final TclAstNode callSiteAst;

  /** Where and how the procedure was defined. */
  public final Optional<ProcedureDefinition> definition;

  /** The result that was returned by this call. */
  private String result;

  ProcedureCall(Location location, TclAstNode ast, ProcedureDefinition definition) {
    this.callSiteLocation = location;
    this.callSiteAst = ast;
    this.definition = Optional.ofNullable(definition);
  }
}

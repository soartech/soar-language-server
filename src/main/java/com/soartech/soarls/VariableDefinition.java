package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

/** A record of a Tcl variable that was defined and its associated metadata. */
class VariableDefinition {
  /** The name of the variable. */
  public final String name;

  /** The location where this variable was set. */
  public final Location location;

  /** The syntax tree of the set command. */
  public final TclAstNode ast;

  /** The value of this variable. */
  public final String value;

  /** The AST node of an associated comment, or null if it doesn't have one. */
  public final Optional<TclAstNode> commentAstNode;

  /**
   * The contents of the comment text. At least for now, this shall include the leading '#' comment
   * character.
   */
  public final Optional<String> commentText;

  VariableDefinition(
      String name,
      Location location,
      TclAstNode ast,
      String value,
      TclAstNode commentAstNode,
      String commentText) {
    this.name = name;
    this.location = location;
    this.ast = ast;
    this.value = value;
    this.commentAstNode = Optional.ofNullable(commentAstNode);
    this.commentText = Optional.ofNullable(commentText);
  }
}

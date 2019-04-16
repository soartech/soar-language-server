package com.soartech.soarls.analysis;

import com.google.common.collect.ImmutableList;
import com.soartech.soarls.tcl.TclAstNode;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

/** A record of a Tcl procedure that was defined and its associated metadata. */
public class ProcedureDefinition {
  /** The name of the procedure. */
  public final String name;

  /** The location where this production was defined. */
  public final Location location;

  /** The arguments to the procedure. */
  public final ImmutableList<Argument> arguments;

  /** The syntax tree of the proc command. */
  public final TclAstNode ast;

  /** The AST node of an associated comment, or null if it doesn't have one. */
  public final Optional<TclAstNode> commentAstNode;

  /**
   * The contents of the comment text. At least for now, this shall include the leading '#' comment
   * character.
   */
  public final Optional<String> commentText;

  ProcedureDefinition(
      String name,
      Location location,
      List<Argument> arguments,
      TclAstNode ast,
      TclAstNode commentAstNode,
      String commentText) {
    this.name = name;
    this.location = location;
    this.arguments = ImmutableList.copyOf(arguments);
    this.ast = ast;
    this.commentAstNode = Optional.ofNullable(commentAstNode);
    this.commentText = Optional.ofNullable(commentText);
  }

  /** A record of an argument to a procedure definition. */
  public static class Argument {
    /** The name of the argument. */
    public final String name;

    /** The default value of the argument, if applicable. */
    public final Optional<String> defaultValue;

    public Argument(String name, String defaultValue) {
      this.name = name;
      this.defaultValue = Optional.ofNullable(defaultValue);
    }
  }
}

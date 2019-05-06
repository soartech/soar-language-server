package com.soartech.soarls;

/**
 * This structure describes the configuration that clients send to the server via the
 * workspace/didChangeConfiguration notification.
 */
class Configuration {
  /** How long in milliseconds to wait for changes to stop before an analysis is begun. */
  public Integer debounceTime = 1000;

  /** Whether hover tooltips should show full comment text or just the first line. */
  public Boolean fullCommentHover = true;

  /**
   * The name of the file to use for Tcl expansions, relative to the workspace root. This file will
   * be frequently modified by the server.
   */
  public String tclExpansionFile = "~tcl-expansion.soar";

  /**
   * If true, then commands which result in the creation of productions will be hyperlinked to the
   * Tcl expansion file. This is disabled by default because the act of updating the expansion file
   * causes most clients to open it automatically.
   */
  public Boolean hyperlinkExpansionFile = false;

  /**
   * If true, then hover text will be prepended with four leading spaces such that a markdown
   * renderer will render thet text verbatim.
   */
  public Boolean renderHoverVerbatim = false;
}

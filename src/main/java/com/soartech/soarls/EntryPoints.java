package com.soartech.soarls;

import java.util.List;

public class EntryPoints {

  /** List of Soar agent start files. */
  public List<EntryPoint> entryPoints;

  /**
   * The entryPoint to use by default (e.g., for determining things like Tcl variable values). Must
   * match the name field in one of the EntryPoints. Can be null.
   */
  public String active;

  public EntryPoints() {}

  public static class EntryPoint {

    /**
     * Path to the start file, relative to the location of the file these were read from (i.e., the
     * workspace root).
     */
    public String path;

    /** Name of this agent (can be null). */
    public String name;

    public EntryPoint() {}
  }
}

package com.soartech.soarls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * This class contains configuration options for a single project. It defines the structure of the
 * soarAgents.json manifest file which is required by the language server.
 */
public class ProjectConfiguration {

  /** List of Soar agent start files. */
  private final List<EntryPoint> entryPoints = new ArrayList<>();

  /**
   * The entryPoint to use by default (e.g., for determining things like Tcl variable values). Must
   * match the name field in one of the EntryPoints. Can be null.
   */
  public final String active = null;

  /**
   * A list of right hand side functions to treat as valid, even if they are not defined in JSoar.
   */
  public final List<String> rhsFunctions = new ArrayList<>();

  public ProjectConfiguration() {}

  /**
   * An entry point describes the file that the language server should source first when it is
   * analysing a codebase.
   */
  public static class EntryPoint {

    /**
     * Path to the start file, relative to the location of the file these were read from (i.e., the
     * workspace root).
     */
    public String path;

    /** Name of this agent (can be null). */
    public String name;

    /** Whether this agent should be analysed. */
    public boolean enabled = true;

    public EntryPoint() {}
  }

  /**
   * Retrieve the active entry point, which is the one specified by the "active" field, or the first
   * one in the list if unspecified.
   */
  public Optional<EntryPoint> activeEntryPoint() {
    Optional<EntryPoint> firstEntryPoint =
        entryPoints.stream().filter(ep -> ep.enabled).findFirst();
    if (active == null) {
      return firstEntryPoint;
    } else {
      return entryPoints.stream().filter(entryPoint -> entryPoint.name.equals(active)).findAny();
    }
  }

  /** Create a stream of all the entry points, with the active one first. */
  public Stream<EntryPoint> entryPoints() {
    EntryPoint active = activeEntryPoint().orElse(null);
    Stream<EntryPoint> others = entryPoints.stream().filter(ep -> ep.enabled && ep != active);
    return Stream.concat(active != null ? Stream.of(active) : Stream.empty(), others);
  }
}

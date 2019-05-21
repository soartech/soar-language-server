package com.soartech.soarls;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ProjectConfiguration {

  /** List of Soar agent start files. */
  public final List<EntryPoint> entryPoints = new ArrayList<>();

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

  /**
   * Retrieve the active entry point, which is the one specified by the "active" field, or the first
   * one in the list if unspecified.
   */
  public EntryPoint activeEntryPoint() {
    EntryPoint firstEntryPoint = entryPoints.get(0);
    if (active == null) {
      return firstEntryPoint;
    } else {
      return entryPoints
          .stream()
          .filter(entryPoint -> entryPoint.name.equals(active))
          .findAny()
          .orElse(null);
    }
  }

  /** Create a stream of all the entry points, with the active one first. */
  public Stream<EntryPoint> entryPoints() {
    EntryPoint active = activeEntryPoint();
    Stream<EntryPoint> others = entryPoints.stream().filter(ep -> ep != active);
    return Stream.concat(Stream.of(active), others);
  }
}

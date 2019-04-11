package com.soartech.soarls.analysis;

import org.eclipse.lsp4j.Location;

/** A record of a production having been sourced. */
public class Production {
  public final String name;

  public final Location location;

  public final String body;

  Production(String body, Location location) {
    // The name of a production is the first word of its body.
    String productionName = body.split("\\s+")[0];

    this.name = productionName;
    this.location = location;
    this.body = body;
  }
}

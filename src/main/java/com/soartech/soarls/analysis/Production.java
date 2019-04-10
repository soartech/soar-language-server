package com.soartech.soarls.analysis;

import org.eclipse.lsp4j.Location;

/** A record of a production having been sourced. */
public class Production {
  public final String name;

  public final Location location;

  public final String body;

  Production(String name, Location location) {
    // This is a hacky way to extract the name of a production,
    // because what should be the name argument is actually the
    // entire argument to the sp command.
    //
    // We'll come back to this later.
    String productionName = name.split("\\s+")[0];

    String body = name.substring(name.indexOf(productionName) + productionName.length());

    this.name = productionName;
    this.location = location;
    this.body = body.trim();
  }
}

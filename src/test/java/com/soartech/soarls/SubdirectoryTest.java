package com.soartech.soarls;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** These tests are focused on making sure we can source agents with subdirectories correctly. */
public class SubdirectoryTest extends LanguageServerTestFixture {
  public SubdirectoryTest() throws Exception {
    super("subdirectory");

    // Opening any file in the project should trigger diagnostics
    // for the entire project.
    open("load.soar");
  }

  // Tests for load.soar

  @Test
  public void analyzesLoadFile() {
    assertNotNull(diagnosticsForFile("production.soar"));
  }
}

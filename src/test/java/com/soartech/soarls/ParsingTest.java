package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class ParsingTest extends SingleFileTestFixture {

  public ParsingTest() throws Exception {
    super("parsing", "test.soar");

    waitForAnalysis("test.soar");
  }

  /** Retrieve just the diagnostics from parsing the file. This does not include static analysis. */
  @Test
  public void test() {
    SoarFile file = retrieveFile("test.soar");
    assertEquals(0, file.diagnostics.size());
  }
}

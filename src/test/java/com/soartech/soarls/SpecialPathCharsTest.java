package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class SpecialPathCharsTest extends SingleFileTestFixture {

  public SpecialPathCharsTest() throws Exception {
    super("special path(chars)!~'+ű", "test.soar");

    waitForAnalysis("test.soar");
  }

  @Test
  public void computesDiagnostics() {
    assertNotNull(this.diagnosticsForFile("test.soar"));
  }
}

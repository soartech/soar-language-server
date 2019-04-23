package com.soartech.soarls;

import org.junit.Test;
import static org.junit.Assert.*;

import org.junit.Ignore;

public class SpecialPathCharsTest extends SingleFileTestFixture {

  public SpecialPathCharsTest() throws Exception {
    super("special path(chars)!~'+ű", "test.soar");

    waitForAnalysis("test.soar");
  }

  @Ignore
  @Test
  public void test() {
      assertEquals(0, this.diagnosticsForFile("test.soar").size());
  }
}

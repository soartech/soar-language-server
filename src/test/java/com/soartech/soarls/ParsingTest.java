package com.soartech.soarls;

import static org.junit.Assert.*;

import org.junit.Ignore;
import org.junit.Test;

public class ParsingTest extends SingleFileTestFixture {

  public ParsingTest() throws Exception {
    super("parsing", "test.soar");

    waitForAnalysis("test.soar");
  }

  @Ignore
  @Test(timeout = 100)
  public void test() {
    assertEquals(0, this.diagnosticsForFile("test.soar").size());
  }
}

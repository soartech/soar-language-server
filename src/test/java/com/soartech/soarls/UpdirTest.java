package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for a project that involves navigating up to a parent directory. */
public class UpdirTest extends LanguageServerTestFixture {
  public UpdirTest() throws Exception {
    super("updir");
    waitForAnalysis("dir1/load.soar");
  }

  /** The hover range covers the entire invocation of the procedure, including its arguments. */
  @Test
  public void filesAreAnalysed() throws Exception {
    assertNotNull(diagnosticsForFile("dir1/load.soar"));
    assertNotNull(diagnosticsForFile("dir2/load.soar"));
  }
}

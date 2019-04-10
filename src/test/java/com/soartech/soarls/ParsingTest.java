package com.soartech.soarls;

import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ParsingTest extends LanguageServerTestFixture {

  public ParsingTest() throws Exception {
    super("parsing");

    open("test.soar");
  }

  @Test(timeout=100)
  public void test() {
      // nothing specifically to do, just want to ensure that we don't get stuck in an infinite loop
  }

}

package com.soartech.soarls;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

public class SoarAgentsTest extends LanguageServerTestFixture {

  public SoarAgentsTest() throws Exception {
    super("");
  }

  @Test
  public void readJson() throws IOException {

    String soarAgentsJson =
        new String(Files.readAllBytes(workspaceRoot.resolve("soarAgents.json")));
    EntryPoints soarAgents = new Gson().fromJson(soarAgentsJson, EntryPoints.class);

    // just spot checking parts of the structure
    assertEquals(8, soarAgents.entryPoints.size());
    assertEquals("project", soarAgents.active);
    assertEquals("completion", soarAgents.entryPoints.get(0).name);
    assertEquals("completion/test.soar", soarAgents.entryPoints.get(0).path);
  }
}

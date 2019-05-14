package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class SoarAgentsTest extends LanguageServerTestFixture {

  public SoarAgentsTest() throws Exception {
    super("");
  }

  @Test
  public void readJson() throws IOException {

    String soarAgentsJson =
        new String(Files.readAllBytes(Paths.get(workspaceRoot.resolve("soarAgents.json"))));
    ProjectConfiguration soarAgents =
        new Gson().fromJson(soarAgentsJson, ProjectConfiguration.class);

    // just spot checking parts of the structure
    assertEquals(8, soarAgents.entryPoints.size());
    assertEquals("project", soarAgents.active);
    assertEquals("completion", soarAgents.entryPoints.get(0).name);
    assertEquals("completion/test.soar", soarAgents.entryPoints.get(0).path);
    assertEquals(Arrays.asList("custom-rhs-1", "custom-rhs-2"), soarAgents.rhsFunctions);
  }
}

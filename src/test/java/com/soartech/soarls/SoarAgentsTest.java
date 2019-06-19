package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.soartech.soarls.ProjectConfiguration.EntryPoint;
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
    assertEquals(9, soarAgents.entryPoints().count());
    assertEquals("project", soarAgents.active);
    // The first entry is the active one, so we skip one to start at the top of the list.
    EntryPoint firstEntry = soarAgents.entryPoints().skip(1).iterator().next();
    assertEquals("completion", firstEntry.name);
    assertEquals("completion/test.soar", firstEntry.path);
    assertEquals(Arrays.asList("custom-rhs-1", "custom-rhs-2"), soarAgents.rhsFunctions);
  }
}

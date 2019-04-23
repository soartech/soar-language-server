package com.soartech.soarls;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.soartech.soarls.EntryPoints.EntryPoint;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SoarWorkspaceService implements WorkspaceService {

  private static final Logger LOG = LoggerFactory.getLogger(SoarWorkspaceService.class);

  private static final String SOAR_AGENTS_FILE = "soarAgents.json";

  private final SoarDocumentService documentService;
  private String workspaceRootUri;
  private EntryPoints soarAgentEntryPoints;

  SoarWorkspaceService(SoarDocumentService documentService) {
    this.documentService = documentService;
  }

  public void setWorkspaceRoot(String workspaceRootUri) {
    LOG.info("Setting workspace URI: " + workspaceRootUri);
    this.workspaceRootUri = workspaceRootUri;

    processEntryPoints();
  }

  /**
   * Processes the SOAR_AGENTS_FILE and sets the entry point if possible. Note that setting the
   * entry point currently triggers other processing that requires a valid client connection.
   */
  public void processEntryPoints() {
    LOG.info("Processing entry points: workspace URI: " + workspaceRootUri);
    Path workspaceRootPath = Paths.get(URI.create(workspaceRootUri));

    Path soarAgentsPath = workspaceRootPath.resolve(SOAR_AGENTS_FILE);

    if (!Files.exists(soarAgentsPath)) {
      LOG.info("Not found: {} -- using default entry point", soarAgentsPath);
      return;
    }

    try {
      // read the SOAR_AGENTS_FILE into an EntryPoints object
      String soarAgentsJson = new String(Files.readAllBytes(soarAgentsPath));
      soarAgentEntryPoints = new Gson().fromJson(soarAgentsJson, EntryPoints.class);

      if (soarAgentEntryPoints.entryPoints.size() == 0)
        return; // bail out if there are no defined entry points

      // get the active entry point (default to the first one)

      EntryPoint activeEntryPoint = soarAgentEntryPoints.entryPoints.get(0);
      if (soarAgentEntryPoints.active != null) {

        activeEntryPoint =
            soarAgentEntryPoints
                .entryPoints
                .stream()
                .filter(entryPoint -> entryPoint.name.equals(soarAgentEntryPoints.active))
                .findAny()
                .orElse(null);
      }

      // set the entry point

      Path agentEntryPoint = workspaceRootPath.resolve(activeEntryPoint.path);
      documentService.setEntryPoint(agentEntryPoint.toUri());

    } catch (IOException | JsonSyntaxException e) {
      LOG.error("Error trying to read {}", soarAgentsPath, e);
    }
  }

  @Override
  public void didChangeConfiguration(DidChangeConfigurationParams params) {
    JsonObject settings = (JsonObject) params.getSettings();
    Configuration config = new Gson().fromJson(settings.get("soar"), Configuration.class);
    documentService.setConfiguration(config);
  }

  @Override
  public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

  @Override
  public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
    Gson gson = new Gson();
    if (params.getCommand().equals("set-entry-point")) {
      URI uri =
          SoarDocumentService.uri(
              gson.fromJson((JsonElement) params.getArguments().get(0), String.class));
      documentService.setEntryPoint(uri);
    }
    return CompletableFuture.completedFuture(null);
  }
}

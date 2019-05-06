package com.soartech.soarls;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SoarWorkspaceService implements WorkspaceService {

  private static final Logger LOG = LoggerFactory.getLogger(SoarWorkspaceService.class);

  private static final String SOAR_AGENTS_FILE = "soarAgents.json";

  private final SoarDocumentService documentService;
  private URI workspaceRootUri;
  private EntryPoints soarAgentEntryPoints;

  SoarWorkspaceService(SoarDocumentService documentService) {
    this.documentService = documentService;
  }

  public void setWorkspaceRoot(String workspaceRootUri) {
    LOG.info("Setting workspace root: {}", workspaceRootUri);
    this.workspaceRootUri = URI.create(workspaceRootUri);

    processEntryPoints();
  }

  /**
   * Processes the SOAR_AGENTS_FILE and sets the entry point if possible. Note that setting the
   * entry point currently triggers other processing that requires a valid client connection.
   */
  public void processEntryPoints() {
    LOG.info("Processing entry points: workspace path: " + workspaceRootUri);
    documentService.setWorkspaceRootUri(workspaceRootUri);

    Path soarAgentsPath = Paths.get(workspaceRootUri).resolve(SOAR_AGENTS_FILE);

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

      // set the entry point

      documentService.setProjectConfig(soarAgentEntryPoints);

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
  public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    for (FileEvent change : params.getChanges()) {
      if (URI.create(change.getUri()).equals(manifestUri())) {
        processEntryPoints();
      }
    }
  }

  @Override
  public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
    Gson gson = new Gson();
    if (params.getCommand().equals("log-source-tree")) {
      return documentService
          .getAnalysis()
          .thenAccept(
              analysis ->
                  documentService.printAnalysisTree(
                      analysis, System.err, analysis.entryPointUri, "    "))
          .thenApply(result -> result);
    } else {
      LOG.warn("Unsupported command: {}", params.getCommand());
    }
    return CompletableFuture.completedFuture(null);
  }

  /** Returns the URI of the project configuration file. */
  private URI manifestUri() {
    return workspaceRootUri.resolve(SOAR_AGENTS_FILE);
  }
}

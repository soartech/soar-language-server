package com.soartech.soarls;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.codehaus.janino.util.Producer;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SoarWorkspaceService implements WorkspaceService {

  private static final Logger LOG = LoggerFactory.getLogger(SoarWorkspaceService.class);

  private static final String SOAR_AGENTS_FILE = "soarAgents.json";

  private LanguageClient client = null;

  private final SoarDocumentService documentService;
  private URI workspaceRootUri = null;

  SoarWorkspaceService(SoarDocumentService documentService) {
    this.documentService = documentService;
  }

  public void connect(LanguageClient client) {
    this.client = client;
  }

  void setWorkspaceRoot(URI workspaceRootUri) {
    this.workspaceRootUri = workspaceRootUri;
  }

  void initialized() {
    // Here we register for changes to the manifest file, so that we trigger a new analysis when
    // configurations change.
    FileSystemWatcher watcher = new FileSystemWatcher("**/soarAgents.json");
    List<FileSystemWatcher> watchers = Arrays.asList(watcher);
    DidChangeWatchedFilesRegistrationOptions options =
        new DidChangeWatchedFilesRegistrationOptions(watchers);
    Registration registration =
        new Registration("changes", "workspace/didChangeWatchedFiles", options);
    List<Registration> registrations = Arrays.asList(registration);
    client.registerCapability(new RegistrationParams(registrations));

    processEntryPoints();
  }

  /**
   * Processes the SOAR_AGENTS_FILE and sets the entry point if possible. Note that setting the
   * entry point currently triggers other processing that requires a valid client connection.
   */
  public void processEntryPoints() {
    LOG.info("Processing entry points: workspace path: " + workspaceRootUri);

    Function<String, Diagnostic> makeError =
        message ->
            new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 0)),
                message,
                DiagnosticSeverity.Error,
                "workspace service");

    Producer<List<Diagnostic>> readEntryPoints =
        () -> {
          Path soarAgentsPath = Paths.get(workspaceRootUri).resolve(SOAR_AGENTS_FILE);

          if (!Files.exists(soarAgentsPath)) {
            LOG.info("Not found: {} -- using default entry point", soarAgentsPath);
            return Arrays.asList(makeError.apply("soarAgents.json was not found"));
          }

          String soarAgentsJson;
          try {
            soarAgentsJson = new String(Files.readAllBytes(soarAgentsPath));
          } catch (IOException e) {
            return Arrays.asList(makeError.apply("Failed to read file"));
          }

          try {
            ProjectConfiguration configuration =
                new Gson().fromJson(soarAgentsJson, ProjectConfiguration.class);

            if (configuration.entryPoints().count() == 0) {
              return Arrays.asList(makeError.apply("No entry points were specified"));
            }
            if (configuration.activeEntryPoint() == null) {
              return Arrays.asList(makeError.apply("Active entry point is invalid or missing"));
            }

            // set the entry point
            documentService.setProjectConfig(configuration);
          } catch (JsonSyntaxException e) {
            LOG.error("Error trying to read {}", soarAgentsPath, e);
            return Arrays.asList(makeError.apply(e.getMessage()));
          }

          // On success, there are no diagnostics
          return Arrays.asList();
        };

    List<Diagnostic> diagnostics = readEntryPoints.produce();
    client.publishDiagnostics(new PublishDiagnosticsParams(manifestUri().toString(), diagnostics));
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

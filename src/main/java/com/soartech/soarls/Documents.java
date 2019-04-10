package com.soartech.soarls;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class owns and maintains the SoarFiles that are open in the workspace, and provides safe
 * concurrent access to them.
 */
public class Documents {
  private static final Logger LOG = LoggerFactory.getLogger(Documents.class);

  private ConcurrentMap<String, SoarFile> documents = new ConcurrentHashMap<>();

  private Set<String> openDocuments = new HashSet<>();

  /** Retrieve the file with the given URI, reading it from the filesystem if necessary. */
  public SoarFile get(String uri) {
    return documents.computeIfAbsent(uri, Documents::readFile);
  }

  /**
   * Get the set of currently open URIs. While the document manager may hold files in memory even if
   * the client does not have them open, this set will only contain the URIs of the files which are
   * open in the client.
   */
  public ImmutableSet<String> openUris() {
    return ImmutableSet.copyOf(openDocuments);
  }

  /** Add a document that was received via a textDocument/didOpen notification. */
  public SoarFile open(TextDocumentItem doc) {
    SoarFile soarFile = new SoarFile(doc.getUri(), doc.getText());
    documents.put(soarFile.uri, soarFile);
    openDocuments.add(soarFile.uri);
    return soarFile;
  }

  /** Remove a URI from the set of currently open files. */
  public void close(String uri) {
    openDocuments.remove(uri);
  }

  /** Apply a sequence of changes that were received via a textDocument/didChange notification. */
  public void applyChanges(DidChangeTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    documents.compute(uri, (k, file) -> file.withChanges(params.getContentChanges()));
  }

  private static SoarFile readFile(String uri) {
    try {
      Path path = Paths.get(new URI(uri));
      List<String> lines = Files.readAllLines(path);
      String contents = Joiner.on("\n").join(lines);
      return new SoarFile(uri, contents);
    } catch (Exception e) {
      LOG.error("Failed to open file", e);
      return null;
    }
  }
}

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

  /**
   * The current state of the documents in the workspace. The documents may or may not be open in
   * the client. If they are, then the state comes from the client; if they are not, then the state
   * comes from the filesystem.
   */
  private final ConcurrentHashMap<URI, SoarFile> documents = new ConcurrentHashMap<>();

  /**
   * The set of URIs that point to currently open documents. This is a subset of the keys of the
   * documents hash map.
   */
  private final Set<URI> openDocuments = new HashSet<>();

  /** Retrieve the file with the given URI, reading it from the filesystem if necessary. */
  public SoarFile get(URI uri) {
    return documents.computeIfAbsent(uri, Documents::readFile);
  }

  /**
   * Get the set of currently open URIs. While the document manager may hold files in memory even if
   * the client does not have them open, this set will only contain the URIs of the files which are
   * open in the client.
   */
  public ImmutableSet<URI> openUris() {
    return ImmutableSet.copyOf(openDocuments);
  }

  /** Add a document that was received via a textDocument/didOpen notification. */
  public SoarFile open(TextDocumentItem doc) {
    URI uri = SoarDocumentService.uri(doc.getUri());
    SoarFile soarFile = new SoarFile(uri, doc.getText());
    documents.put(soarFile.uri, soarFile);
    openDocuments.add(soarFile.uri);
    return soarFile;
  }

  /** Remove a URI from the set of currently open files. */
  public void close(URI uri) {
    openDocuments.remove(uri);
  }

  /** Apply a sequence of changes that were received via a textDocument/didChange notification. */
  public void applyChanges(DidChangeTextDocumentParams params) {
    URI uri = SoarDocumentService.uri(params.getTextDocument().getUri());
    documents.compute(uri, (k, file) -> file.withChanges(params.getContentChanges()));
  }

  private static SoarFile readFile(URI uri) {
    try {
      Path path = Paths.get(uri);
      List<String> lines = Files.readAllLines(path);
      String contents = Joiner.on("\n").join(lines);
      return new SoarFile(uri, contents);
    } catch (Exception e) {
      LOG.error("Failed to open file", e);
      return null;
    }
  }
}

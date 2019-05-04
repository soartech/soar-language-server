package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;

/**
 * Although we use JSoar's Tcl interpreter to evaluate source files, we had to implement the AST
 * traversal ourselves in order to get all the information we need. This also means that we had to
 * wrap the source command, and therefore pushd and popd.
 *
 * <p>This class checks that we have implemented directory operations by checking some simple
 * queries against a project that uses some unusal directory structures.
 */
public class DirectoriesTest extends LanguageServerTestFixture {
  public DirectoriesTest() throws Exception {
    super("directories");
  }

  Location definition(String relativePath, int line, int character) throws Exception {
    return languageServer
        .getTextDocumentService()
        .definition(textDocumentPosition(relativePath, line, character))
        .get()
        .getLeft()
        .get(0);
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toString();
  }

  @Test
  public void pushdOnce() throws Exception {
    Location def = definition("load.soar", 11, 6);
    assertEquals(def.getUri(), resolve("first-dir/first-file.soar"));
  }

  @Test
  public void pushdTwice() throws Exception {
    Location def = definition("load.soar", 12, 6);
    assertEquals(def.getUri(), resolve("first-dir/second-dir/second-file.soar"));
  }

  @Test
  public void pushdThenPopd() throws Exception {
    Location def = definition("load.soar", 13, 6);
    assertEquals(def.getUri(), resolve("third-file.soar"));
  }

  @Test
  public void rootFile() throws Exception {
    Location def = definition("load.soar", 16, 6);
    assertEquals(def.getUri(), resolve("load.soar"));
  }
}

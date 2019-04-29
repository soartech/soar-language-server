package com.soartech.soarls;

import static org.junit.Assert.*;

import java.net.URI;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.Test;

/** Tcl expansions are implementad by creating and modifying the contents of a special file. */
public class TclExpansionTest extends LanguageServerTestFixture {
  public TclExpansionTest() throws Exception {
    super("project");

    URI uri = workspaceRoot.resolve("~tcl-expansion.soar").toUri();
    DidOpenTextDocumentParams params =
        new DidOpenTextDocumentParams(new TextDocumentItem(uri.toString(), "soar", 0, ""));
    languageServer.getTextDocumentService().didOpen(params);
  }

  CodeActionParams params(String relativePath, Range range) {
    return new CodeActionParams(fileId(relativePath), range, new CodeActionContext());
  }

  /**
   * It seems unlikely that users will configure the expansion file, but the option is there. More
   * likely, it is possible that some clients might override it for their own special purposes.
   */
  @Test
  @org.junit.Ignore
  public void configureExpansionFile() {
    fail("unimplemented");
  }

  /**
   * The textDocument/codeAction request is used to tell us where the client's cursor or selected
   * range is. This triggers changes to the expansion buffer.
   */
  @Test
  public void expansionForPoint() throws Exception {
    languageServer
        .getTextDocumentService()
        .codeAction(params("productions.soar", range(0, 0, 0, 0)))
        .get();

    SoarFile file = retrieveFile("~tcl-expansion.soar");

    assertEquals(
        file.contents,
        "sp {elaborate*top-state\n"
            + "    (state <s> ^superstate nil)\n"
            + "-->\n"
            + "    (<s> ^top-state *YES*)\n"
            + "}\n");
  }

  /** If a range is selected, show expansions for all overlapping commands. */
  @Test
  public void expansionForRange() throws Exception {
    languageServer
        .getTextDocumentService()
        .codeAction(params("productions.soar", range(0, 0, 10, 0)))
        .get();

    SoarFile file = retrieveFile("~tcl-expansion.soar");

    assertEquals(
        file.contents,
        "sp {elaborate*top-state\n"
            + "    (state <s> ^superstate nil)\n"
            + "-->\n"
            + "    (<s> ^top-state *YES*)\n"
            + "}\n"
            + "\n"
            + "sp {proc-not-defined\n"
            + "    (state <s> ^superstate nil)\n"
            + "    \n"
            + "-->\n"
            + "    (<s> ^object-exists *YES*)\n"
            + "}\n");
  }

  /**
   * Moving the cursor to a file that is not part of the project should not clear or otherwise
   * modify the expansion buffer.
   */
  @Test
  public void noChangesForFileOutsideProject() throws Exception {
    languageServer
        .getTextDocumentService()
        .codeAction(params("~tcl-expansion.soar", range(0, 0, 0, 0)))
        .get();

    assertTrue(this.edits.isEmpty());
  }
}

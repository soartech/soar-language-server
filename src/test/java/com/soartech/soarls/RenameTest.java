package com.soartech.soarls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;

/** Tests for finding references to tcl procedures and variables. */
public class RenameTest extends LanguageServerTestFixture {
  public RenameTest() throws Exception {
    super("rename");
    open("test.soar");
    waitForAnalysis("test.soar");
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toString();
  }

  private WorkspaceEdit rename(String relativePath, int line, int character, String newName)
      throws ExecutionException, InterruptedException {
    RenameParams params = new RenameParams();
    params.setTextDocument(fileId(relativePath));
    params.setNewName(newName);
    params.setPosition(new Position(line, character));

    return languageServer.getTextDocumentService().rename(params).get();
  }

  @Test
  public void renameTest() throws Exception {
    String fileName = "test.soar";
    String newVarName = "NGS_YES_RENAMED";
    WorkspaceEdit workspaceEdit = rename(fileName, 10, 21, newVarName);

    List<TextEdit> allEdits = new ArrayList<>();
    for (List<TextEdit> textEditList : workspaceEdit.getChanges().values()) {
      allEdits.addAll(textEditList);
    }

    Range range1 = new Range(new Position(5, 4), new Position(5, 11));
    Range range2 = new Range(new Position(10, 21), new Position(10, 28));

    assertTrue(allEdits.stream().anyMatch(item -> item.getRange().equals(range1)));
    assertTrue(allEdits.stream().anyMatch(item -> item.getRange().equals(range2)));
  }

  @Test
  public void multipleFilesRenameTest() throws Exception {
    String fileName = "secondFile.soar";
    String newVarName = "NGS_YES_RENAMED";

    WorkspaceEdit workspaceEdit = rename(fileName, 3, 21, newVarName);

    List<TextEdit> allEdits = new ArrayList<>();
    for (List<TextEdit> textEditList : workspaceEdit.getChanges().values()) {
      allEdits.addAll(textEditList);
    }
    assertEquals(3, allEdits.size());

    Range range = new Range(new Position(3, 21), new Position(3, 28));

    assertTrue(allEdits.stream().anyMatch(item -> item.getRange().equals(range)));
  }
}

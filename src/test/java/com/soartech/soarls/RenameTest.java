package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import com.sun.corba.se.spi.orbutil.threadpool.Work;
import org.eclipse.lsp4j.*;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

/** Tests for finding references to tcl procedures and variables. */
public class RenameTest extends LanguageServerTestFixture {
  public RenameTest() throws Exception {
    super("rename");
    open("test.soar");
    waitForAnalysis("test.soar");
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toUri().toString();
  }

  private TclAstNode getVariableNode(TclAstNode rootNode) {
    if (rootNode.getType() == TclAstNode.VARIABLE || rootNode.getType() == TclAstNode.VARIABLE_NAME) {
      return rootNode;
    }
    if (rootNode.getChildren() != null) {
      for (TclAstNode child : rootNode.getChildren()) {
        TclAstNode recursedNode = getVariableNode(child);
        if (recursedNode != null) {
          return recursedNode;
        }
      }
    }
    return null;
  }

  private WorkspaceEdit rename(String relativePath, int line, int character, String newName) throws ExecutionException, InterruptedException {
    RenameParams params = new RenameParams();
    params.setTextDocument(fileId(relativePath));
    params.setNewName(newName);
    params.setPosition(new Position(line, character));

    return languageServer.getTextDocumentService().rename(params).get();
  }

  @Test
  public void renameTest() throws InterruptedException, ExecutionException {
    String fileName = "test.soar";
    URI fileUri = workspaceRoot.resolve(fileName).toUri();
    String newVarName = "NGS_YES_RENAMED";
    WorkspaceEdit workspaceEdit = rename(fileName, 8, 21, newVarName);

    List<TextEdit> allEdits = new ArrayList<>();
    for (List<TextEdit> textEditList : workspaceEdit.getChanges().values()) {
      allEdits.addAll(textEditList);
    }

    Range range1 = new Range(new Position(3, 4), new Position(3, 11));
    Range range2 = new Range(new Position(8, 20), new Position(8, 28));

    //assertTrue(allEdits.stream().anyMatch(item -> item.getRange().equals(range1)));
    assertTrue(allEdits.stream().anyMatch(item -> item.getRange().equals(range2)));
  }
}

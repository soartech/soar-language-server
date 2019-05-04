package com.soartech.soarls;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.junit.jupiter.api.Test;

/**
 * Tcl expansions are implementad by creating and modifying the contents of a special file. We
 * (ab)use the go to definition feature as a mechanism to get the client to navigate to this file.
 * This is because such behaviour should always be initiated by a user action.
 *
 * <p>It is possible that in the future the LSP will add a mechanism by which the server can request
 * that the client navigate to a location in a document. See this issue for details:
 *
 * <p>https://github.com/Microsoft/language-server-protocol/issues/612
 */
public class GoToDefinitionTest extends LanguageServerTestFixture {
  public GoToDefinitionTest() throws Exception {
    super("definition");
  }

  List<Location> definitionsForPosition(String relativePath, int line, int character)
      throws Exception {
    TextDocumentPositionParams params = textDocumentPosition(relativePath, line, character);
    return languageServer
        .getTextDocumentService()
        .definition(params)
        .get()
        .getLeft()
        .stream()
        .collect(toList());
  }

  @Test
  public void sameFileDefinition() throws Exception {
    List<Location> contents = definitionsForPosition("productions.soar", 19, 13);

    Location location = contents.get(0);
    assertNotNull(location);

    assertEquals(resolve("productions.soar"), location.getUri());

    Position start = location.getRange().getStart();
    assertNotNull(start);
    assertEquals(0, start.getLine());
    assertEquals(0, start.getCharacter());

    Position end = location.getRange().getEnd();
    assertNotNull(end);
    assertEquals(2, end.getLine());
    assertEquals(1, end.getCharacter());
  }

  @Test
  public void otherFileDefinition() throws Exception {
    List<Location> contents = definitionsForPosition("productions.soar", 12, 13);

    Location location = contents.get(0);
    assertNotNull(location);

    assertEquals(resolve("micro-ngs.tcl"), location.getUri());

    Position start = location.getRange().getStart();
    assertNotNull(start);
    assertEquals(6, start.getLine());
    assertEquals(0, start.getCharacter());

    Position end = location.getRange().getEnd();
    assertNotNull(end);
    assertEquals(8, end.getLine());
    assertEquals(1, end.getCharacter());
  }

  @Test
  public void variableDefinition() throws Exception {
    List<Location> locations = definitionsForPosition("productions.soar", 7, 40);

    Location location = locations.get(0);
    assertEquals(location.getUri(), resolve("micro-ngs.tcl"));
    assertEquals(location.getRange(), range(2, 0, 2, 17));
  }

  @Test
  public void variableRedefinition() throws Exception {
    List<Location> locations = definitionsForPosition("productions.soar", 29, 42);

    Location location = locations.get(0);
    assertEquals(location.getUri(), resolve("productions.soar"));
    assertEquals(location.getRange(), range(24, 0, 24, 33));
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toString();
  }

  @Test
  public void definitionForTopLevelProc() throws Exception {
    List<Location> locations = definitionsForPosition("templates.soar", 18, 0);

    Location location = locations.get(0);
    assertEquals(location.getUri(), resolve("templates.soar"));
    assertEquals(location.getRange(), range(1, 0, 16, 1));
  }
}

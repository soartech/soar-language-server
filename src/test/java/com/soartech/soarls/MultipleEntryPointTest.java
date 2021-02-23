package com.soartech.soarls;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.junit.jupiter.api.Test;

public class MultipleEntryPointTest extends LanguageServerTestFixture {
  public MultipleEntryPointTest() throws Exception {
    super("multiple-entry-point");
  }

  @Test
  void hoverVariable() throws Exception {
    // The $agent_name variable
    HoverParams params = hoverParams("common.soar", 5, 35);
    Hover hover = languageServer.getTextDocumentService().hover(params).get();

    MarkupContent content = hover.getContents().getRight();
    assertEquals(content.getValue(), "primary: primary\nsecondary: secondary");
  }

  List<Location> getReferences(String relativePath, int line, int character) throws Exception {
    ReferenceParams params = new ReferenceParams();
    params.setTextDocument(fileId(relativePath));
    params.setPosition(new Position(line, character));
    params.setContext(new ReferenceContext(false));
    return languageServer.getTextDocumentService().references(params).get().stream()
        .collect(toList());
  }

  @Test
  void variableReferences() throws Exception {
    // Definition of agent_name variable
    List<Location> primaryReferences = getReferences("primary.soar", 0, 10);
    assertLocation(primaryReferences, "common.soar", range(5, 30, 5, 41));

    List<Location> secondaryReferences = getReferences("secondary.soar", 0, 10);
    assertLocation(secondaryReferences, "common.soar", range(5, 30, 5, 41));
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toString();
  }

  @Test
  void variableDefinitions() throws Exception {
    // Usage of $agent_name variable
    DefinitionParams params = definitionParams("common.soar", 3, 30);
    List<Location> definitions =
        languageServer.getTextDocumentService().definition(params).get().getLeft().stream()
            .collect(toList());
    System.out.println(definitions);
    assertLocation(definitions, "primary.soar", range(0, 0, 0, 22));
    assertLocation(definitions, "secondary.soar", range(0, 0, 0, 24));
  }

  /** Check that the entry point with "disabled": true is never analysed. */
  @Test
  void disabledEntryPoint() throws Exception {
    waitForAnalysis("primary.soar");
    waitForAnalysis("secondary.soar");
    assertThrows(NullPointerException.class, () -> waitForAnalysis("common.soar"));
  }

  /** Assert that the list of locations includes the given URI and range. */
  void assertLocation(List<Location> locations, String relativePath, Range range) {
    String uri = resolve(relativePath);
    locations.stream()
        .filter(l -> l.getUri().equals(uri))
        .filter(l -> l.getRange().equals(range))
        .findAny()
        .get();
  }
}

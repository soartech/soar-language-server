package com.soartech.soarls;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.*;

import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.junit.Test;

/** Tests for finding references to tcl procedures and variables. */
public class ReferencesTest extends LanguageServerTestFixture {
  public ReferencesTest() throws Exception {
    super("project");
  }

  @Test
  public void checkCapabilities() {
    assertEquals(capabilities.getReferencesProvider(), true);
  }

  String resolve(String relativePath) {
    return workspaceRoot.resolve(relativePath).toString();
  }

  List<Location> referencesForPoint(String relativePath, int line, int character) throws Exception {
    ReferenceParams params = new ReferenceParams();
    params.setTextDocument(fileId(relativePath));
    params.setPosition(new Position(line, character));
    params.setContext(new ReferenceContext(false));

    List<Location> references =
        languageServer.getTextDocumentService().references(params).get().stream().collect(toList());
    System.out.println(references);
    return references;
  }

  @Test
  public void referencesToVariable() throws Exception {
    // set NGS_YES
    List<Location> references = referencesForPoint("micro-ngs/macros.tcl", 2, 4);
    assertReference(references, "productions.soar", range(3, 40, 3, 48));
    assertReference(references, "productions.soar", range(11, 44, 11, 52));
    assertEquals(references.size(), 2);
  }

  @Test
  public void referencesToRedefinedVariable() throws Exception {
    // set NGS_YES (redefined, in productions.soar)
    List<Location> references = referencesForPoint("productions.soar", 14, 0);
    assertReference(references, "productions.soar", range(19, 40, 19, 48));
    assertEquals(references.size(), 1);
  }

  @Test
  public void referencesFromVariableUsageAtStart() throws Exception {
    // On the '$' in $NGS_YES
    List<Location> references = referencesForPoint("productions.soar", 3, 40);
    assertReference(references, "productions.soar", range(3, 40, 3, 48));
    assertReference(references, "productions.soar", range(11, 44, 11, 52));
  }

  @Test
  public void referencesFromVariableUsageInMiddle() throws Exception {
    // On the 'G' in $NGS_YES
    List<Location> references = referencesForPoint("productions.soar", 3, 42);
    assertReference(references, "productions.soar", range(3, 40, 3, 48));
    assertReference(references, "productions.soar", range(11, 44, 11, 52));
  }

  @Test
  public void referencesToProcedure() throws Exception {
    // proc ngs-match-top-state
    List<Location> references = referencesForPoint("micro-ngs/macros.tcl", 6, 5);
    assertReference(references, "productions.soar", range(1, 4, 1, 29));
    assertReference(references, "productions.soar", range(8, 4, 8, 29));
  }

  @Test
  public void referencesFromProcedureUsage() throws Exception {
    // call ngs-match-top-state
    List<Location> references = referencesForPoint("productions.soar", 8, 5);
    assertReference(references, "productions.soar", range(1, 4, 1, 29));
    assertReference(references, "productions.soar", range(8, 4, 8, 29));
  }

  /** Assert that the list of locations includes the given URI and range. */
  void assertReference(List<Location> locations, String relativePath, Range range) {
    String uri = resolve(relativePath);
    locations
        .stream()
        .filter(l -> l.getUri().equals(uri))
        .filter(l -> l.getRange().equals(range))
        .findAny()
        .get();
  }
}

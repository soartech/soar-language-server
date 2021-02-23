package com.soartech.soarls;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.soartech.soarls.analysis.FileAnalysis;
import com.soartech.soarls.analysis.ProjectAnalysis;
import com.soartech.soarls.tcl.TclAstNode;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/**
 * A helper class for responding to the textDocument/documentSymbol request.
 *
 * <p>Even though the LSP spec tries to be as general as possible, there is still a bit of a
 * mismatch between idiomatic Soar/Tcl code and the type of code that the LSP spec seems to expect.
 * For example, the SymbolKind enum covers things like function declarations, but there's no variant
 * for function calls.
 *
 * <p>Additionally, the Tcl language has fewer constructs than a developer might assume. For
 * example, when we think of things like "defining a procedure" or "defining a production", all
 * we're really doing is calling the "proc" or "sp" functions (either directly or indirectly).
 * Because of this, there are a few heuristics that are applied in this implementation so that we
 * can report more structure than just function calls (which again, aren't actually represented in
 * the LSP spec, but make up a large part of some Tcl and Soar code).
 */
public class DocumentSymbolRequest {
  public static Stream<DocumentSymbol> symbols(ProjectAnalysis projectAnalysis, URI uri) {
    return projectAnalysis.file(uri).map(f -> procedureCalls(f)).orElseGet(Stream::empty);
  }

  static Stream<DocumentSymbol> procedureCalls(FileAnalysis analysis) {
    return analysis.file.ast.getChildren().stream()
        .map(node -> analysis.procedureCall(node))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(
            call -> {
              int callLine = call.callSiteLocation.getRange().getStart().getLine();
              String name =
                  call.callSiteAst.getChildren().stream()
                      .filter(
                          node -> analysis.file.rangeForNode(node).getEnd().getLine() == callLine)
                      .map(node -> analysis.file.getNodeInternalText(node))
                      .collect(joining(" "));
              Range range = call.callSiteLocation.getRange();
              List<DocumentSymbol> children =
                  Stream.of(
                          files(analysis, call.callSiteAst),
                          productions(analysis, call.callSiteAst),
                          procedures(analysis, call.callSiteAst))
                      .flatMap(s -> s)
                      .collect(toList());

              DocumentSymbol symbol =
                  new DocumentSymbol(name, SymbolKind.Event, range, range, null, children);

              // Here we handle two special cases.
              //
              // If there is only one child and we can't find the
              // procedure definition, then we simply return the
              // child. This covers the "builtin" commands, like proc
              // and sp, so that they don't appear in the symbol
              // hierarchy.
              //
              // If there's a single child but we CAN find the
              // definition, then we keep it in, because it's a call
              // to a procedure that the developer defined.
              return children.size() == 1
                  ? call.definition
                      .map(
                          def -> {
                            symbol.setName(def.name);
                            return symbol;
                          })
                      .orElse(children.get(0))
                  : symbol;
            });
  }

  static Stream<DocumentSymbol> files(FileAnalysis analysis, TclAstNode node) {
    return null;
  }

  static Stream<DocumentSymbol> productions(FileAnalysis analysis, TclAstNode node) {
    return analysis
        .productions(node)
        .map(
            prod -> {
              Range range = analysis.file.rangeForNode(node);
              Range selectionRange = range;
              return new DocumentSymbol(prod.name, SymbolKind.Object, range, selectionRange);
            });
  }

  static Stream<DocumentSymbol> procedures(FileAnalysis analysis, TclAstNode node) {
    return analysis.procedureDefinitions.stream()
        .filter(def -> def.ast == node)
        .map(
            def -> {
              Range range = analysis.file.rangeForNode(node);
              return new DocumentSymbol(def.name, SymbolKind.Function, range, range);
            });
  }
}

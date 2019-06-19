package com.soartech.soarls;

import com.soartech.soarls.analysis.ProjectAnalysis;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

/** A helper class for responding to the textDocument/completion request. */
public class CompletionRequest {
  public static Stream<CompletionItem> completeVariable(
      ProjectAnalysis analysis, String prefix, Range replacementRange) {
    return analysis
        .variableDefinitions
        .values()
        .stream()
        .filter(def -> def.name.startsWith(prefix))
        .map(
            def -> {
              CompletionItem item = new CompletionItem(def.name);
              item.setKind(CompletionItemKind.Constant);
              item.setTextEdit(new TextEdit(replacementRange, def.name));
              item.setDocumentation(def.commentText.orElse(null));
              return item;
            });
  }

  public static Stream<CompletionItem> completeProcedure(
      ProjectAnalysis analysis, String prefix, Range replacementRange) {
    return analysis
        .procedureDefinitions
        .values()
        .stream()
        .filter(def -> def.name.startsWith(prefix))
        .map(
            def -> {
              CompletionItem item = new CompletionItem(def.name);
              item.setKind(CompletionItemKind.Function);
              item.setTextEdit(new TextEdit(replacementRange, def.name));
              item.setDocumentation(def.commentText.orElse(null));
              return item;
            });
  }
}

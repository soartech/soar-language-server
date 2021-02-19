package com.soartech.soarls;

import com.soartech.soarls.analysis.ProcedureDefinition.Argument;
import com.soartech.soarls.analysis.ProjectAnalysis;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

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
              item.setTextEdit(Either.forLeft(new TextEdit(replacementRange, def.name)));
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

              String snippet = def.name;
              for (int i = 0; i != def.arguments.size(); ++i) {
                Argument arg = def.arguments.get(i);
                String value =
                    arg.defaultValue
                        .map(val -> "{ " + arg.name + " " + val + " \\}")
                        .orElse(arg.name);
                snippet += " ${" + (i + 1) + ":" + value + "}";
              }
              snippet += "$0";
              item.setTextEdit(Either.forLeft(new TextEdit(replacementRange, snippet)));

              item.setInsertTextFormat(InsertTextFormat.Snippet);
              item.setDocumentation(def.commentText.orElse(null));
              return item;
            });
  }
}

package com.soartech.soarls;

import com.soartech.soarls.tcl.TclAstNode;
import com.soartech.soarls.tcl.TclParser;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.jsoar.kernel.Agent;
import org.jsoar.kernel.SoarException;
import org.jsoar.kernel.exceptions.SoarInterpreterException;
import org.jsoar.kernel.exceptions.SoarParserException;
import org.jsoar.kernel.exceptions.SoftTclInterpreterException;
import org.jsoar.util.commands.DefaultInterpreterParser;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.ParserBuffer;
import org.jsoar.util.commands.SoarTclExceptionsManager;

/**
 * This class keeps track of the contents of a Soar source file along
 * with a (currently primitive) parse tree and some utilities for
 * converting between offsets and line/column positions.
 *
 * We are using incremental document sync, which means that when the
 * client makes changes we receive notifications about only what
 * changed. However, since we are representing the contents as a
 * String, we still have to re-allocate and re-parse the entire file
 * on every change. This is probably not going to be a bottleneck
 * early on, but it is a candidate for optimization later.
 *
 * That said, we can't simply replace the backing data structure with
 * something like a rope, because the Tcl parser still expects a char
 * array - we would have to copy the contents of any data structure
 * into a new buffer no matter what.
 */
class SoarFile {
    public String uri;

    public String contents;

    public List<ParsedCommand> commands = new ArrayList<>();

    public List<Diagnostic> diagnostics = new ArrayList<>();

    public TclAstNode ast = null;

    public SoarFile(String uri, String contents) {
        this.uri = uri;
        this.contents = fixLineEndings(contents);

        parseFile();
    }

    /** Apply the changes from a textDocument/didChange notification. */
    void applyChange(TextDocumentContentChangeEvent change) {
        // The parameters which are set depends on whether we are
        // using full or incremental updates.
        if (change.getRange() == null) {
            // We are using full document updates.
            this.contents = change.getText();
        } else {
            // We are using incremental updates.

            // This is not the most efficient way of modifying strings,
            // but it's definitely the most convenient.
            int start = offset(change.getRange().getStart());
            int end = offset(change.getRange().getEnd());
            String prefix = this.contents.substring(0, start);
            String suffix = this.contents.substring(end);
            this.contents = prefix + change.getText() + suffix;
        }

        parseFile();
    }

    /** Parse the contents of the file and pull out the following information:
     * - Soar commands, for which we use the JSoar interpreter.
     * - A Tcl AST, for which we use the Tcl parser borrowed from the SoarIDE.
     */
    void parseFile() {
        try {
            List<ParsedCommand> commands = new ArrayList<>();
            final DefaultInterpreterParser parser = new DefaultInterpreterParser();
            Reader reader = new StringReader(this.contents);
            final ParserBuffer pbReader = new ParserBuffer(new PushbackReader(reader));

            while (true) {
                try {
                    ParsedCommand parsedCommand = parser.parseCommand(pbReader);

                    if (parsedCommand.isEof()) {
                        break;
                    }
                    commands.add(parsedCommand);
                }catch (SoarParserException e) {
                    int start = position(e.getOffset()).getLine();
                    diagnostics.add(new Diagnostic(
                            new Range(new Position(start, 0), getEndofLinePosition(start)),
                            e.getMessage(),
                            DiagnosticSeverity.Error,
                            "soar"
                    ));
                } catch (SoarInterpreterException e) {
                    int start = e.getSourceLocation().getLine();
                    diagnostics.add(new Diagnostic(
                            new Range(new Position(start, 0), getEndofLinePosition(start)),
                            e.getMessage(),
                            DiagnosticSeverity.Error,
                            "soar"
                    ));
                }
            }
            this.commands = commands;
        } catch (Exception e) {
        }

        TclParser parser = new TclParser();
        parser.setInput(this.contents.toCharArray(), 0, this.contents.length());
        this.ast = parser.parse();
    }

    /** Get the Tcl AST node at the given position. */
    TclAstNode tclNode(Position position) {
        return tclNode(offset(position));
    }

    /** Get the Tcl AST node at the given offset. */
    TclAstNode tclNode(int offset) {
        TclAstNode node = this.ast;

        // Find a child that contains this position.
        while (true) {
            TclAstNode child = node
                .getChildren()
                .stream()
                .filter(c -> c.getStart() <= offset)
                .filter(c -> offset < c.getEnd())
                .findFirst()
                .orElse(null);
            if (child != null) {
                node = child;
            } else {
                return node;
            }
        }
    }

    /** Get a single line as a string. */
    String line(int lineNumber) {
        int start = offset(new Position(lineNumber, 0));
        int end = offset(new Position(lineNumber + 1, 0));
        // This avoids choking on the last line of the file.
        if (end < 0) {
            return contents.substring(start);
        } else {
            return contents.substring(start, end);
        }
    }

    /** Get the 0-based offset at the given position. */
    int offset(Position position) {
        int offset = 0;
        int lines = position.getLine();
        for (char ch: contents.toCharArray()) {
            if (lines == 0) {
                return offset + position.getCharacter();
            }
            if (ch == '\n') {
                lines -= 1;
            }
            offset += 1;
        }
        return contents.length();
    }

    /** Get the line/column of the given 0-based offset. */
    Position position(int offset) {
        int line = 0;
        int character = 0;
        for (int i = 0; i != offset; ++i) {
            if (contents.charAt(i) == '\n') {
                line += 1;
                character = 0;
            } else {
                character += 1;
            }
        }
        return new Position(line, character);
    }

    private String fixLineEndings(String contents) {
        contents = contents.replace("\r\n", "\n");
        contents = contents.replace("\r", "\n");
        return contents;
    }

    public String getExpandedCommand(Agent agent, TclAstNode node) throws SoarException {


        // compare children of node to ast root
        // if they are the same then assume that done on production "name" so expand the whole thing
        // otherwise return the unexpanded text

        // if not on quoted production return null
        if (node.getType() != TclAstNode.QUOTED_WORD) return null;

        // find production node
        TclAstNode parent = null;
        for (TclAstNode child : this.ast.getChildren()) {
            if (branchContainsNode(child, node)) {
                parent = child;
                break;
            }
        }

        if (parent == null) return node.getInternalText(contents.toCharArray());

        String parent_command = parent.getInternalText(contents.toCharArray());
        // strip beginning sp from command (up till first ")
        int first_quote_index = parent_command.indexOf('"');
        String beginning = parent_command.substring(0, first_quote_index + 1);
        parent_command = parent_command.substring(first_quote_index);

        try {
            String test = "return " + parent_command;
            return beginning + agent.getInterpreter().eval(test) + '"';
        } catch (SoarException e) {
            return parent_command;
        }
    }

    private boolean branchContainsNode(TclAstNode treeNode, TclAstNode searchNode) {
        if (treeNode == searchNode) return true;

        for (TclAstNode child : treeNode.getChildren()) {
            if (branchContainsNode(child, searchNode))
                return true;
        }

        return false;
    }

    private String getSubstringOfContents(int start, int length) {
        return contents.substring(start, start + length);
    }

    // returns offset location of specific char before the startOffset
    // if no previous occurrence is found then return 0
    // currently unused
    private int getPreviousChar(char ch, int startOffset) {
        for (int i = startOffset - 1; i > -1; --i) {
            char other = contents.charAt(i);
            if (other == ch) {
                return i;
            }
        }
        return 0;
    }

    // returns a Position of the last character on a given line
    private Position getEndofLinePosition(int line) {
        String[] lines = contents.split("\n");

        return new Position(line, lines[line].length());
    }

    // Returns a range for a diagnostic, highlighting all the text on given line number
    private Range getLineRange(int line) {
        return new Range(new Position(line, 0), getEndofLinePosition(line));
    }

    List<Diagnostic> getAllDiagnostics(SoarTclExceptionsManager exceptionsManager) {
        List<Diagnostic> allDiagnostics = new ArrayList<>();
        allDiagnostics.addAll(diagnostics);
        allDiagnostics.addAll(getDiagnosticsFromExceptionsManager(exceptionsManager));
        return allDiagnostics;
    }

    List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    List<Diagnostic> getDiagnosticsFromExceptionsManager(SoarTclExceptionsManager exceptionsManager) {
        List<Diagnostic> managerDiagnostics = new ArrayList<>();

        for (SoftTclInterpreterException e : exceptionsManager.getExceptions()) {
            managerDiagnostics.add(new Diagnostic(
                    getCommandRange(e.getCommand()),
                    e.getMessage().trim(),
                    DiagnosticSeverity.Error,
                    "soar"
            ));
        }

        return managerDiagnostics;
    }

    /** Get the range that encompasses the given Tcl AST node. */
    Range rangeForNode(TclAstNode node) {
        return new Range(position(node.getStart()), position(node.getEnd()));
    }

    private Range getCommandRange(String command) {
        int offset = contents.indexOf(command);
        if (offset < 0) offset = 0;

        return new Range(position(offset), position(offset + command.length()));
    }
}

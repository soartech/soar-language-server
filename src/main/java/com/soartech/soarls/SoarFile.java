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
import org.jsoar.kernel.exceptions.SoarInterpreterException;
import org.jsoar.kernel.exceptions.SoarParserException;
import org.jsoar.kernel.exceptions.SoftTclInterpreterException;
import org.jsoar.util.commands.DefaultInterpreterParser;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.ParserBuffer;
import org.jsoar.util.commands.SoarTclContextManager;

/**
 * This class keeps track of the contents of a Soar source file along
 * with a (currently primitive) parse tree and some utilities for
 * converting between offsets and line/column positions.
 *
 * We are currently using full document sync, which means that we
 * re-allocate and re-parse the entire file on every change. This is
 * probably not going to be a bottleneck early on, but it is a
 * candidate for optimization later.
 */
class SoarFile {
    public String uri;

    public String contents;

    public List<ParsedCommand> commands = new ArrayList<>();

    public List<Diagnostic> diagnostics = new ArrayList<>();

    public TclAstNode ast = null;
    
    public SoarFile(String uri, String contents) {
        this.uri = uri;
        this.contents = contents;

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
        return -1;
    }

    /** Get the line/column of the given 0-based offset. */
    Position position(int offset) {
        int line = 0;
        int character = 0;
        for (int i = 0; i != contents.length(); ++i) {
            if (i == offset) {
                return new Position(line, character);
            }

            char ch = contents.charAt(i);
            if (ch == '\n') {
                line += 1;
                character = 0;
            } else {
                character += 1;
            }
        }
        return null;
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

    List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    List<Diagnostic> getDiagnostics(SoarTclContextManager contextManager) {
        List<Diagnostic> allDiagnostics = new ArrayList<>(diagnostics);

        for (SoftTclInterpreterException e : contextManager.getExceptions()) {
            allDiagnostics.add(new Diagnostic(
                    getCommandRange(e.getCommand()),
                    e.getMessage(),
                    DiagnosticSeverity.Error,
                    "soar"
            ));
        }

        return allDiagnostics;
    }

    private Range getCommandRange(String command) {
        int offset = contents.indexOf(command);
        if (offset < 0) offset = 0;

        return new Range(position(offset), position(offset + command.length()));
    }
}

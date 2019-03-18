package com.soartech.soarls;

import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.jsoar.util.commands.DefaultInterpreterParser;
import org.jsoar.util.commands.ParsedCommand;
import org.jsoar.util.commands.ParserBuffer;

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

    public List<ParsedCommand> commands = new ArrayList();
    
    public SoarFile(String uri, String contents) {
        this.uri = uri;
        this.contents = contents;

        try {
            List<ParsedCommand> commands = new ArrayList();
            final DefaultInterpreterParser parser = new DefaultInterpreterParser();
            Reader reader = new StringReader(contents);
            final ParserBuffer pbReader = new ParserBuffer(new PushbackReader(reader));

            while (true) {
                ParsedCommand parsedCommand = parser.parseCommand(pbReader);
                if (parsedCommand.isEof()) {
                    break;
                }
                commands.add(parsedCommand);
            }
            this.commands = commands;
        } catch (Exception e) {
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
}

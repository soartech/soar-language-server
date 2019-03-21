package com.soartech.soarls;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.lsp4j.Position;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoarFileTest extends LanguageServerTestFixture {
    final SoarFile file;

    public SoarFileTest() throws Exception {
        super("file");

        Path path = workspaceRoot.resolve("test.soar");
        String content = new String(Files.readAllBytes(path));
        this.file = new SoarFile(path.toUri().toString(), content);
    }

    @Test
    public void beginningOffset() {
        int offset = file.offset(new Position(0, 0));
        assertEquals(offset, 0);
    }

    @Test
    public void beginningPosition() {
        Position position = file.position(0);
        assertEquals(position.getLine(), 0);
        assertEquals(position.getCharacter(), 0);
    }

    @Test
    public void offsetsRoundtrip() {
        for (int offset = 0; offset != file.contents.length(); ++offset) {
            int offsetRoundtrip = file.offset(file.position(offset));
            assertEquals(offset, offsetRoundtrip);
        }
    }

    @Test
    public void lines() {
        assertEquals(file.line(0), "# comment\n");
        assertEquals(file.line(1), "\n");
        assertEquals(file.line(2), "sp \"propose*init\n");
        // ...
        assertEquals(file.line(7), "\"\n");
    }
}

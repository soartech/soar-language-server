package com.soartech.soarls;

import org.eclipse.lsp4j.Position;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoarFileTest {
    final SoarFile file;

    /**
     * Defining test fixtures inline is fine for now, but soon we
     * should build a little infrastructure so we can read from the
     * file system. The Kotlin language server has a nice example of
     * how this can work.
     */
    public SoarFileTest() {
        String contents = ""
            + "# comment\n"
            + "\n"
            + "sp \"propose*init\n"
            + "    (state <s> ^superstate nil)\n"
            + "-->\n"
            + "    (<s> ^operator <o> + =)\n"
            + "    (<o> ^name init)\n"
            + "\"\n";
        file = new SoarFile("path", contents);
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
}

package org.jbm.cc.cpp;

import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.cpp.FileHeaders;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.cpp.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headers on disk: next to the includer for the quoted form, the search directories for both, then the bundled ones. */
class FileHeadersTest {

    @TempDir
    Path dir;

    private static List<String> expand(String source, HeaderProvider headers, String file) {
        return new Scanner().expand(CppTokenizer.tokenSet(source, headers, file)).tokens.stream()
                .filter(t -> t.token.type != TokenType.EOF)
                .map(t -> t.token.text)
                .toList();
    }

    @Test
    void quotedFormLooksNextToTheIncluderFirst() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path inc = Files.createDirectories(dir.resolve("inc"));
        Files.writeString(src.resolve("a.h"), "local");
        Files.writeString(inc.resolve("a.h"), "searched");
        FileHeaders headers = new FileHeaders(List.of(inc));
        String main = src.resolve("main.c").toString();
        assertEquals(List.of("local"), expand("#include \"a.h\"", headers, main));
        assertEquals(List.of("searched"), expand("#include <a.h>", headers, main));
        assertEquals(src.resolve("a.h").toString(), headers.find("a.h", true, main).orElseThrow().name());
    }

    @Test
    void nestedIncludesResolveRelativeToTheHeader() throws IOException {
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Files.writeString(sub.resolve("outer.h"), "#include \"inner.h\"\nouter");
        Files.writeString(sub.resolve("inner.h"), "inner");
        FileHeaders headers = new FileHeaders(List.of(dir));
        assertEquals(List.of("inner", "outer"), expand("#include <sub/outer.h>", headers, "main.c"));
    }

    @Test
    void missingFileIsEmpty() {
        assertTrue(new FileHeaders(List.of(dir)).find("nope.h", true, "main.c").isEmpty());
    }

    @Test
    void standardChainPrefersFilesOverBundledHeaders() throws IOException {
        Files.writeString(dir.resolve("stdio.h"), "mine");
        HeaderProvider headers = HeaderProvider.standard(List.of(dir));
        assertEquals(List.of("mine"), expand("#include <stdio.h>", headers, "main.c"));
        List<String> bundled = expand("#include <limits.h>\nCHAR_BIT", HeaderProvider.standard(List.of()), "main.c");
        assertEquals(List.of("8"), bundled);
    }
}

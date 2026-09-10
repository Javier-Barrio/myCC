package org.jbm.cc.cpp;

import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.LexException;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.cpp.Scanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code #include} through a provider: tokens in place, one macro table, nesting, guards, and the errors. */
class IncludeDirectiveTest {

    private static final HeaderProvider HEADERS = HeaderProvider.of(Map.of(
            "a.h", "int a;\n#define A 1\n",
            "b.h", "#include \"a.h\"\nint b = A;\n",
            "guarded.h", "#ifndef GUARDED_H\n#define GUARDED_H\nint g;\n#endif\n",
            "uses.h", "int u = FROM_MAIN;\n",
            "self.h", "#include \"self.h\"\n",
            "open.h", "#if 1\nint x;\n",
            "cond.h", "#ifdef WANT\nint want;\n#else\nint dont;\n#endif\n"));

    private static TokenSet tokens(String source) {
        return new Scanner().expand(CppTokenizer.tokenSet(source, HEADERS, "main.c"));
    }

    private static List<String> expand(String source) {
        return tokens(source).tokens.stream()
                .filter(t -> t.token.type != TokenType.EOF)
                .map(t -> t.token.text)
                .toList();
    }

    private static void assertExpandsTo(String source, String... expected) {
        assertEquals(List.of(expected), expand(source));
    }

    private static String fails(String source) {
        return assertThrows(LexException.class, () -> expand(source)).getMessage();
    }

    @Test
    void headerTokensAppearInPlaceInEitherForm() {
        assertExpandsTo("before;\n#include \"a.h\"\nafter;", "before", ";", "int", "a", ";", "after", ";");
        assertExpandsTo("#include <a.h>\nafter;", "int", "a", ";", "after", ";");
    }

    @Test
    void oneMacroTableAcrossFiles() {
        assertExpandsTo("#include \"a.h\"\nint x = A;", "int", "a", ";", "int", "x", "=", "1", ";");
        assertExpandsTo("#define FROM_MAIN 7\n#include \"uses.h\"", "int", "u", "=", "7", ";");
        assertExpandsTo("#include \"a.h\"\n#ifdef A\nyes\n#endif", "int", "a", ";", "yes");
    }

    @Test
    void nestedIncludes() {
        assertExpandsTo("#include \"b.h\"\n", "int", "a", ";", "int", "b", "=", "1", ";");
    }

    @Test
    void guardsAndConditionalsInsideHeaders() {
        assertExpandsTo("#include \"guarded.h\"\n#include \"guarded.h\"\n", "int", "g", ";");
        assertExpandsTo("#include \"cond.h\"\n", "int", "dont", ";");
        assertExpandsTo("#define WANT\n#include \"cond.h\"\n", "int", "want", ";");
    }

    @Test
    void includesInSkippedGroupsAreNotExecuted() {
        assertExpandsTo("#if 0\n#include \"missing.h\"\n#endif\nx", "x");
    }

    @Test
    void headerTokensCarryTheHeaderName() {
        TokenSet set = tokens("#include \"a.h\"\nint x;");
        assertEquals("a.h", set.tokens.get(0).token.file);
        assertEquals(1, set.tokens.get(0).token.line);
        assertEquals("main.c", set.tokens.get(3).token.file);
        assertEquals(2, set.tokens.get(3).token.line);
    }

    @Test
    void errors() {
        assertTrue(fails("#include \"missing.h\"").contains("header not found: \"missing.h\""));
        assertTrue(fails("#include <missing.h>").contains("header not found: <missing.h>"));
        assertTrue(fails("#include X").contains("expected \"file\" or <file> after #include"));
        assertTrue(fails("#include").contains("expected \"file\" or <file> after #include"));
        assertTrue(fails("#include \"a.h").contains("expected \"file\" or <file> after #include"));
        assertTrue(fails("#include \"self.h\"").contains("#include nested too deeply"));
        assertTrue(fails("#include \"open.h\"\nint y;").contains("unterminated #if"));
        String noProvider = assertThrows(LexException.class, () -> CppTokenizer.tokenSet("#include \"a.h\"")).getMessage();
        assertTrue(noProvider.contains("no headers are available"), noProvider);
    }
}

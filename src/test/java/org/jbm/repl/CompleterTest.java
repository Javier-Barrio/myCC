package org.jbm.repl;

import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.sema.types.Types;
import org.jbm.mycc.repl.Completer;
import org.jbm.mycc.repl.Repl;
import org.jbm.mycc.repl.ScriptConsole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each completion context, against a small session. */
class CompleterTest {

    static Repl session(String... lines) {
        Repl repl = new Repl(new ScriptConsole(List.of()), BundledHeaders.INSTANCE, new Types(X86_64SysV.INSTANCE));
        for (String line : lines) {
            repl.handle(line);
        }
        return repl;
    }

    static List<String> texts(Repl repl, String line) {
        return new Completer(repl).complete(line, line.length()).stream().map(Completer.Candidate::text).toList();
    }

    static String describe(Repl repl, String line, String text) {
        return new Completer(repl).complete(line, line.length()).stream()
                .filter(c -> c.text().equals(text)).findFirst().orElseThrow().description();
    }

    @Test
    void commands() {
        Repl repl = session();
        assertEquals(List.of("/list"), texts(repl, "/li"));
        assertEquals(Repl.commands(), texts(repl, "/"));
        assertEquals(List.of(), texts(repl, "/list x y"));
    }

    @Test
    void namesWithTheirTypes() {
        Repl repl = session("int sq(int x) { return x * x; }", "int value = 3; double ratio = 0.5;", "#define LIMIT 10",
                "typedef struct P { int x; int y; } P;", "sq(2)");
        assertEquals("sq", texts(repl, "s").get(0), "names before keywords");
        assertEquals("int (int)", describe(repl, "s", "sq"));
        assertEquals("int", describe(repl, "va", "value"));
        assertEquals("double", describe(repl, "ra", "ratio"));
        assertEquals("macro", describe(repl, "LI", "LIMIT"));
        assertEquals("typedef", describe(repl, "P", "P"));
        assertEquals("int", describe(repl, "$", "$1"));
        assertTrue(texts(repl, "value + r").contains("ratio"), "a prefix anywhere in the line");
        assertTrue(texts(repl, "re").contains("return"), "keywords");
        assertTrue(texts(repl, "re").contains("register"));
    }

    @Test
    void declaredNamesFromHeaders() {
        Repl repl = session("#include <stdio.h>");
        assertTrue(texts(repl, "prin").contains("printf"));
        assertEquals("function", describe(repl, "prin", "printf"));
    }

    @Test
    void headersAfterInclude() {
        Repl repl = session();
        assertEquals(List.of("stdio.h>", "stdint.h>"), texts(repl, "#include <stdi"));
        assertEquals(BundledHeaders.NAMES.size(), texts(repl, "#include <").size());
    }

    @Test
    void namesAfterNameCommands() {
        Repl repl = session("int alpha = 1; int beta = 2;", "int add(int a, int b) { return a + b; }");
        assertEquals(List.of("add", "alpha"), texts(repl, "/tac a"), "functions first, then objects");
        assertEquals(List.of("beta"), texts(repl, "/drop b"));
        assertEquals(List.of("add", "alpha", "beta"), texts(repl, "/list "));
    }

    @Test
    void membersAfterDotAndArrow() {
        Repl repl = session("struct P { int x; int y; };", "struct P p = { 1, 2 }; struct P *pp = &p;");
        assertEquals(List.of("p.x", "p.y"), texts(repl, "p."));
        assertEquals(List.of("p.y"), texts(repl, "1 + p.y"));
        assertEquals(List.of("pp->x", "pp->y"), texts(repl, "pp->"));
        assertEquals("int", describe(repl, "p.", "p.x"));
        assertEquals(List.of(), texts(repl, "p->"), "not a pointer");
        assertEquals(List.of(), texts(repl, "nothing."));
    }

    @Test
    void nothingToComplete() {
        Repl repl = session();
        assertEquals(List.of(), texts(repl, "1 + 2 "));
        assertEquals(List.of(), texts(repl, ""));
    }
}

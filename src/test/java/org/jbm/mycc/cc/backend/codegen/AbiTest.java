package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The calling convention against gcc's: structs of every shape the
 * SysV classification distinguishes pass between code we compiled and
 * code gcc compiled, in both directions, as arguments, results and
 * past the six integer registers. The program's output must match an
 * all-gcc build of the same sources.
 */
class AbiTest {

    static final String SHAPES = """
            struct S4 { int a; };
            struct S8 { int a, b; };
            struct S12 { int a, b, c; };
            struct S16 { long a, b; };
            struct S24 { long a, b, c; };
            struct F8 { float a, b; };
            struct D16 { double a, b; };
            struct M16 { int a; double b; };
            struct N16 { double a; int b; };
            struct X12 { float a; int b; float c; };
            struct C3 { char a, b, c; };
            struct C5 { char v[5]; };
            struct C7 { char v[7]; };
            struct C13 { char v[13]; };
            union U8 { double d; long l; };
            struct P { struct S8 in; float f; };
            """;

    // Every function: takes the shape and an int, returns the shape with
    // every field advanced by the int; a sum reads it back.
    static String functions(String prefix) {
        var sb = new StringBuilder(SHAPES);
        sb.append("long ").append(prefix).append("_seven(int a, int b, int c, int d, int e, int f, struct S8 s, struct C13 t, struct D16 u, int g)\n")
          .append("{ return a + b + c + d + e + f + g + s.a * 10 + s.b * 100 + t.v[0] + t.v[12] * 7 + (long) u.a + (long) u.b; }\n");
        String[][] shapes = {
                {"S4", "a"}, {"S8", "a b"}, {"S12", "a b c"}, {"S16", "a b"}, {"S24", "a b c"}, {"F8", "a b"}, {"D16", "a b"},
                {"M16", "a b"}, {"N16", "a b"}, {"X12", "a b c"}, {"C3", "a b c"}, {"C5", "v[0] v[4]"}, {"C7", "v[0] v[6]"},
                {"C13", "v[0] v[12]"}, {"P", "in.a in.b f"}};
        for (String[] shape : shapes) {
            String t = "struct " + shape[0];
            sb.append(t).append(' ').append(prefix).append("_bump_").append(shape[0]).append("(").append(t).append(" s, int by) {\n");
            for (String field : shape[1].split(" ")) {
                sb.append("  s.").append(field).append(" += by;\n");
            }
            sb.append("  return s;\n}\n");
            sb.append("double ").append(prefix).append("_sum_").append(shape[0]).append("(").append(t).append(" s) { return 0");
            for (String field : shape[1].split(" ")) {
                sb.append(" + s.").append(field);
            }
            sb.append("; }\n");
        }
        sb.append("union U8 ").append(prefix).append("_bump_U8(union U8 u, int by) { u.l += by; return u; }\n");
        sb.append("double ").append(prefix).append("_sum_U8(union U8 u) { return (double) u.l; }\n");
        return sb.toString();
    }

    static final String MAIN = """
            #include <stdio.h>
            #include <stdlib.h>
            """ + SHAPES + """
            #define DECL(T, F) struct T ours_bump_##T(struct T, int); double ours_sum_##T(struct T); struct T theirs_bump_##T(struct T, int); double theirs_sum_##T(struct T);
            DECL(S4, a) DECL(S8, a) DECL(S12, a) DECL(S16, a) DECL(S24, a) DECL(F8, a) DECL(D16, a) DECL(M16, a) DECL(N16, a) DECL(X12, a)
            DECL(C3, a) DECL(C5, v) DECL(C7, v) DECL(C13, v) DECL(P, f)
            union U8 ours_bump_U8(union U8, int); double ours_sum_U8(union U8); union U8 theirs_bump_U8(union U8, int); double theirs_sum_U8(union U8);
            long ours_seven(int, int, int, int, int, int, struct S8, struct C13, struct D16, int);
            long theirs_seven(int, int, int, int, int, int, struct S8, struct C13, struct D16, int);
            #define CHECK(T, ...) do { struct T v = __VA_ARGS__; \\
                printf(#T " %g %g %g %g\\n", ours_sum_##T(v), theirs_sum_##T(v), ours_sum_##T(theirs_bump_##T(v, 3)), theirs_sum_##T(ours_bump_##T(v, 5))); } while (0)
            int main(void) {
                CHECK(S4, {1});
                CHECK(S8, {1, 2});
                CHECK(S12, {1, 2, 3});
                CHECK(S16, {10, 20});
                CHECK(S24, {10, 20, 30});
                CHECK(F8, {1.5f, 2.5f});
                CHECK(D16, {1.25, 2.75});
                CHECK(M16, {7, 2.5});
                CHECK(N16, {2.5, 7});
                CHECK(X12, {1.5f, 2, 3.5f});
                CHECK(C3, {1, 2, 3});
                CHECK(C5, {{1, 2, 3, 4, 5}});
                CHECK(C7, {{1, 2, 3, 4, 5, 6, 7}});
                CHECK(C13, {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}});
                CHECK(P, {{1, 2}, 0.5f});
                union U8 u; u.l = 40;
                printf("U8 %g %g %g %g\\n", ours_sum_U8(u), theirs_sum_U8(u), ours_sum_U8(theirs_bump_U8(u, 1)), theirs_sum_U8(ours_bump_U8(u, 2)));
                struct S8 s = {1, 2}; struct C13 c = {{9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4}}; struct D16 d = {100.0, 200.0};
                printf("seven %ld %ld\\n", ours_seven(1, 2, 3, 4, 5, 6, s, c, d, 7), theirs_seven(1, 2, 3, 4, 5, 6, s, c, d, 7));
                div_t q = div(17, 5); ldiv_t lq = ldiv(-17L, 5L);
                printf("div %d %d %ld %ld\\n", q.quot, q.rem, lq.quot, lq.rem);
                return 0;
            }
            """;

    @Test
    void structsPassBetweenOurCodeAndGccsInBothDirections() throws Exception {
        assumeTrue(Native.gccAvailable(), "gcc is not available");
        Path dir = Files.createTempDirectory("mycc-abi");
        Path ours = dir.resolve("ours.c");
        Path theirs = dir.resolve("theirs.c");
        Path main = dir.resolve("main.c");
        Files.writeString(ours, functions("ours"));
        Files.writeString(theirs, functions("theirs"));
        Files.writeString(main, MAIN);
        Path reference = dir.resolve("reference");
        run(List.of("gcc", "-w", "-o", reference.toString(), main.toString(), ours.toString(), theirs.toString()), dir);
        String expected = run(List.of(reference.toString()), dir);

        var types = new Types(X86_64SysV.INSTANCE);
        var compiled = Compiler.compile(Files.readString(ours), HeaderProvider.system(List.of()), ours.toString(), types);
        Path o = dir.resolve("ours.o");
        Files.write(o, Codegen.object(compiled.tac()));
        Path exe = dir.resolve("mixed");
        run(List.of("gcc", "-w", "-o", exe.toString(), main.toString(), o.toString(), theirs.toString()), dir);
        assertEquals(expected, run(List.of(exe.toString()), dir));

        // and the other way: our main and theirs, gcc's ours
        var mainCompiled = Compiler.compile(MAIN, HeaderProvider.system(List.of()), main.toString(), types);
        Path mo = dir.resolve("main.o");
        Files.write(mo, Codegen.object(mainCompiled.tac()));
        var theirsCompiled = Compiler.compile(Files.readString(theirs), HeaderProvider.system(List.of()), theirs.toString(), types);
        Path to = dir.resolve("theirs.o");
        Files.write(to, Codegen.object(theirsCompiled.tac()));
        Path exe2 = dir.resolve("mixed2");
        run(List.of("gcc", "-w", "-o", exe2.toString(), mo.toString(), ours.toString(), to.toString()), dir);
        assertEquals(expected, run(List.of(exe2.toString()), dir));
    }

    private static String run(List<String> command, Path dir) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError(String.join(" ", command) + " failed:\n" + out);
        }
        return out;
    }
}

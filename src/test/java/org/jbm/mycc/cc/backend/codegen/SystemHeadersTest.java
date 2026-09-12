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
 * A native build against the system's C library headers: a program
 * that includes the common ones and prints what depends on their
 * layouts and constants, compiled by us and by gcc, must print the
 * same. Skipped without gcc or the headers.
 */
class SystemHeadersTest {

    static final String PROGRAM = """
            #include <assert.h>
            #include <ctype.h>
            #include <errno.h>
            #include <float.h>
            #include <inttypes.h>
            #include <limits.h>
            #include <locale.h>
            #include <math.h>
            #include <setjmp.h>
            #include <signal.h>
            #include <stdarg.h>
            #include <stdbool.h>
            #include <stddef.h>
            #include <stdint.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            #include <time.h>
            #include <wchar.h>
            static jmp_buf env;
            static void thrower(int n) { longjmp(env, n); }
            static int sum(int n, ...) { va_list ap; va_start(ap, n); int s = 0; for (int i = 0; i < n; i++) { s += va_arg(ap, int); } va_end(ap); return s; }
            int main(void) {
                printf("%zu %zu %zu %zu %zu %zu\\n", sizeof(struct tm), sizeof(jmp_buf), sizeof(struct lconv), sizeof(time_t), sizeof(clock_t), sizeof(FILE *));
                printf("%zu %zu %zu %zu %zu\\n", sizeof(size_t), sizeof(ptrdiff_t), sizeof(wchar_t), sizeof(intmax_t), sizeof(struct timespec));
                int caught = setjmp(env);
                if (caught == 0) { thrower(7); }
                printf("caught %d\\n", caught);
                errno = 0;
                strtol("99999999999999999999", NULL, 10);
                printf("erange %d\\n", errno == ERANGE);
                printf("%d %d %c %d\\n", isalpha('a') != 0, isdigit('x'), toupper('q'), isspace(' ') != 0);
                printf("%s %d %d\\n", localeconv()->decimal_point, CHAR_BIT, INT_MAX);
                printf("%.3f %d %d %d\\n", strtod("2.5e1", NULL), FLT_DIG, DBL_MAX_EXP, DBL_MAX > 1e300);
                printf("%" PRId64 " %d %u\\n", INT64_C(1) << 40, (int) sizeof(int64_t), UINT8_MAX);
                struct tm t = {0};
                t.tm_year = 100;
                t.tm_mday = 1;
                printf("%d %d\\n", t.tm_year, (int) sizeof t.tm_sec);
                printf("%d %d %d %.1f\\n", (int) floor(2.7), abs(-3), sum(3, 1, 2, 3), fabs(-1.5));
                char buf[32];
                snprintf(buf, sizeof buf, "%s", "sys");
                printf("%s %zu %d\\n", buf, strlen(buf), strcmp(buf, "sys"));
                assert(1 == 1);
                printf("%d %d %d\\n", SIGINT > 0, EOF, (int) sizeof(bool));
                void *m = malloc(16);
                printf("%d\\n", m != NULL);
                free(m);
                return 0;
            }
            """;

    @Test
    void theSystemHeadersDescribeTheLibraryWeLinkWith() throws Exception {
        assumeTrue(Native.gccAvailable(), "gcc is not available");
        assumeTrue(Files.isRegularFile(Path.of("/usr/include/stdio.h")), "no system headers");
        Path dir = Files.createTempDirectory("mycc-system");
        Path source = dir.resolve("prog.c");
        Files.writeString(source, PROGRAM);
        Path reference = dir.resolve("reference");
        run(List.of("gcc", "-std=c2x", "-o", reference.toString(), source.toString(), "-lm"), dir);
        String expected = run(List.of(reference.toString()), dir);

        var types = new Types(X86_64SysV.INSTANCE);
        var compiled = Compiler.compile(PROGRAM, HeaderProvider.system(List.of()), source.toString(), types);
        Path o = dir.resolve("prog.o");
        Files.write(o, Codegen.object(compiled.tac()));
        Path exe = dir.resolve("prog");
        run(List.of("gcc", "-o", exe.toString(), o.toString(), "-lm"), dir);
        assertEquals(expected, run(List.of(exe.toString()), dir));
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

package org.jbm.mycc.repl.vm;

import org.jbm.mycc.repl.vm.builtins.Libc;
import org.jbm.mycc.repl.vm.builtins.Printf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.jbm.mycc.repl.vm.VmTest.module;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The C library builtins, through C programs: output captured, values returned. */
class LibcTest {

    record Run(long exit, String out) {
    }

    static Run run(String source) {
        VM vm = new VM();
        Libc.bind(vm);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        vm.out(new PrintStream(bytes, true, StandardCharsets.ISO_8859_1));
        vm.step(module(source));
        long exit;
        try {
            VM.Value v = vm.call("main", List.of());
            exit = v instanceof VM.IntValue i ? i.value() : -1;
        } catch (Libc.Exit e) {
            exit = 1000 + e.status;
        }
        return new Run(exit, bytes.toString(StandardCharsets.ISO_8859_1));
    }

    @Test
    void printfConversions() {
        Run r = run("""
                #include <stdio.h>
                int main(void) {
                    int n = printf("%d|%i|%u|%x|%X|%o|%c|%s|%%|%p\\n", -42, 7, -1, 255, 255, 8, 'q', "text", (void *) 4096);
                    printf("[%5d][%-5d][%05d][%+d][% d][%.3d][%5.3d]\\n", 42, 42, 42, 42, 42, 7, 7);
                    printf("[%8.3f][%-8.2f][%e][%E][%g][%g][%g][%#g]\\n", 3.14159, 2.5, 12345.678, 0.00012, 0.0001, 100000.0, 1e-5, 1.5);
                    printf("[%ld][%lld][%lu][%hhd][%hd][%zu]\\n", -1L, 1LL << 40, -1L, 300, 70000, sizeof(int));
                    printf("[%*d][%-*d][%.*f][%s][%.2s]\\n", 6, 1, 6, 1, 2, 3.14159, "", "abc");
                    printf("%c%c%c\\n", 'a', 'b', 'c');
                    return n;
                }
                """);
        assertEquals("""
                -42|7|4294967295|ff|FF|10|q|text|%|0x1000
                [   42][42   ][00042][+42][ 42][007][  007]
                [   3.142][2.50    ][1.234568e+04][1.200000E-04][0.0001][100000][1e-05][1.50000]
                [-1][1099511627776][18446744073709551615][44][4464][4]
                [     1][1     ][3.14][][ab]
                abc
                """, r.out());
        assertEquals(42, r.exit());
    }

    @Test
    void stringsAndMemory() {
        Run r = run("""
                #include <stdio.h>
                #include <string.h>
                int main(void) {
                    char buf[32];
                    strcpy(buf, "hello");
                    strcat(buf, ", world");
                    printf("%s %d %d %d\\n", buf, (int) strlen(buf), strcmp("a", "b") < 0, strcmp("same", "same"));
                    char *comma = strchr(buf, ',');
                    printf("%s|%s|%d\\n", comma, strstr(buf, "wor"), strncmp("abc", "abd", 2));
                    memset(buf, 'x', 3);
                    memcpy(buf + 3, "YZ", 2);
                    printf("%s %d\\n", buf, memcmp("ab", "ac", 2));
                    char *dup = strdup("copy");
                    dup[0] = 'C';
                    printf("%s\\n", dup);
                    return (int) strlen(dup);
                }
                """);
        assertEquals("hello, world 12 1 0\n, world|world|0\nxxxYZ, world -1\nCopy\n", r.out());
        assertEquals(4, r.exit());
    }

    @Test
    void heapAndNumbers() {
        Run r = run("""
                #include <stdlib.h>
                #include <stdio.h>
                #include <math.h>
                int main(void) {
                    int *a = malloc(4 * sizeof(int));
                    int *z = calloc(4, sizeof(int));
                    for (int i = 0; i < 4; i++) { a[i] = i * i; }
                    a = realloc(a, 8 * sizeof(int));
                    a[7] = 49;
                    free(z);
                    printf("%d %d %d %d\\n", a[3], a[7], z[3], abs(-5) + atoi(" -12") + (int) labs(-3L));
                    printf("%.3f %.1f %.1f %.1f %.1f\\n", sqrt(2.0), pow(2, 10), floor(-1.5), ceil(1.2), fmod(7.5, 2));
                    return (int) fabs(-3.0) + (int) hypot(3, 4);
                }
                """);
        assertEquals("9 49 0 -4\n1.414 1024.0 -2.0 2.0 1.5\n", r.out());
        assertEquals(8, r.exit());
    }

    @Test
    void sprintfAndExit() {
        Run r = run("""
                #include <stdio.h>
                #include <stdlib.h>
                int main(void) {
                    char buf[32];
                    int n = sprintf(buf, "%d-%s", 7, "x");
                    char small[4];
                    int m = snprintf(small, 4, "%d", 123456);
                    printf("%s %d %s %d\\n", buf, n, small, m);
                    exit(9);
                    return 0;
                }
                """);
        assertEquals("7-x 3 123 6\n", r.out());
        assertEquals(1009, r.exit(), "exit(9) unwound before main returned");
    }

    @Test
    void filesAndStreams(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        String path = dir.resolve("fred.txt").toString().replace("\\", "/");
        Run r = run("""
                #include <stdio.h>
                #include <string.h>
                int main(void) {
                    FILE *f = fopen("%s", "w");
                    fwrite("hello\\nworld\\n", 1, 12, f);
                    fputs("tail", f);
                    fputc('!', f);
                    fprintf(f, "%%d", 42);
                    fclose(f);
                    char buf[8];
                    f = fopen("%s", "r");
                    int n = fread(buf, 1, 5, f);
                    buf[n] = 0;
                    printf("%%d %%s\\n", n, buf);
                    int c;
                    int count = 0;
                    while ((c = fgetc(f)) != EOF) { count++; }
                    fclose(f);
                    f = fopen("%s", "r");
                    while (fgets(buf, sizeof buf, f) != NULL) { printf("[%%s]", buf); }
                    printf("\\n%%d %%d\\n", count, feof(f));
                    fclose(f);
                    fprintf(stdout, "to %%s\\n", "stdout");
                    fprintf(stderr, "to %%s\\n", "stderr");
                    int (*fp)(FILE *, const char *, ...) = &fprintf;
                    fp(stdout, "%%d\\n", 7);
                    return fopen("%s", "r") == NULL;
                }
                """.formatted(path, path, path, dir.resolve("missing.txt").toString().replace("\\", "/")));
        assertEquals("5 hello\n[hello\n][world\n][tail!42]\n14 1\nto stdout\nto stderr\n7\n", r.out());
        assertEquals(1, r.exit());
    }

    @Test
    void strrchrFindsTheLastOccurrence() {
        Run r = run("""
                #include <stdio.h>
                #include <string.h>
                int main(void) {
                    char a[] = "hello world";
                    printf("%s|%d|%s\\n", strrchr(a, 'l'), strrchr(a, 'x') == NULL, strrchr(a, 0));
                    return 0;
                }
                """);
        assertEquals("ld|1|\n", r.out());
    }

    @Test
    void thePrintfFamilyTakesAList() {
        Run r = run("""
                #include <stdarg.h>
                #include <stdio.h>
                int fmt(char *out, const char *f, ...) { va_list ap; va_start(ap, f); int n = vsnprintf(out, 16, f, ap); va_end(ap); return n; }
                int log_(const char *f, ...) { va_list ap; va_start(ap, f); int n = vprintf(f, ap); va_end(ap); return n; }
                int main(void) {
                    char buf[16];
                    int n = fmt(buf, "%d %s %.1f %c", 7, "x", 2.5, 'y');
                    printf("%s|%d\\n", buf, n);
                    n = fmt(buf, "%*d|%-*d|%s", 4, 1, 3, 2, "0123456789abcdef");
                    printf("%s|%d\\n", buf, n);
                    log_("%5.2f|%d|%s\\n", 3.14159, 7, "z");
                    return 0;
                }
                """);
        assertEquals("7 x 2.5 y|9\n   1|2  |012345|25\n 3.14|7|z\n", r.out());
    }

    @Test
    void faultsInTheLibrary() {
        assertThrows(IllegalStateException.class, () -> run("#include <stdio.h>\nint main(void) { return printf(\"%d\"); }"));
        assertThrows(IllegalStateException.class, () -> run("#include <stdio.h>\nint main(void) { return printf(\"%d\", 1.5); }"));
        assertEquals("%q", run("#include <stdio.h>\nint main(void) { return printf(\"%q\", 1); }").out(), "an unknown conversion is printed as it is");
    }

    @Test
    void formatterDirectly() {
        Memory m = new Memory();
        assertEquals("a-1", Printf.format(m, "a%d", List.of(new VM.IntValue(-1))));
        assertEquals("0.5", Printf.format(m, "%g", List.of(new VM.FloatValue(0.5))));
        assertEquals("1e+100", Printf.format(m, "%g", List.of(new VM.FloatValue(1e100))));
        assertEquals("nan inf -inf", Printf.format(m, "%f %f %f", List.of(new VM.FloatValue(Double.NaN), new VM.FloatValue(Double.POSITIVE_INFINITY), new VM.FloatValue(Double.NEGATIVE_INFINITY))));
    }
}

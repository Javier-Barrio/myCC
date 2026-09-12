package org.jbm.mycc;

import org.jbm.mycc.cc.sema.types.Std;
import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.codegen.Codegen;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.parse.ast.AstPrinter;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.backend.lower.tac.TacWriter;
import org.jbm.mycc.cc.sema.tast.TypedPrinter;
import org.jbm.mycc.cc.sema.types.Types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {

    // Minimal C program driving development of the parser and compiler:
    // globals, function definitions/calls, locals, while/if, array
    // indexing, and arithmetic - with every preprocessor feature in play
    // (object and function-like macros, #, ##, and the STR/XSTR
    // rescanning idiom).
    public static final String SOURCE = """
            #define BUFFER_SIZE 8
            #define SQUARE(x) ((x) * (x))
            #define MAX(a, b) ((a) > (b) ? (a) : (b))
            #define STR(x) #x
            #define XSTR(x) STR(x)
            #define GETTER(field) get_##field

            int values[BUFFER_SIZE];
            const char* size_str = XSTR(BUFFER_SIZE);

            int GETTER(count)(void) {
                return BUFFER_SIZE;
            }

            int main(void) {
                int total = 0;
                int i = 0;
                while (i < get_count()) {
                    values[i] = SQUARE(i);
                    total = MAX(total, values[i]);
                    i = i + 1;
                }
                if (total > BUFFER_SIZE) {
                    return total;
                }
                return 0;
            }
            """;

    // With a path argument compiles that file, with `-I dir` search
    // directories for its includes; without one, SOURCE.
    // `-S` prints x86-64 assembly instead, `-a` with the TAC in comments;
    // `-c` writes an object file, `-o name` names it; `-std=c17` compiles
    // as C17, where `()` declares no prototype.
    public static void main(String[] args) throws IOException {
        List<Path> searchDirs = new ArrayList<>();
        String file = "<source>";
        String source = SOURCE;
        boolean assembly = false;
        boolean annotate = false;
        boolean object = false;
        String output = null;
        Std std = Std.C23;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-std=")) {
                std = Std.of(args[i].substring("-std=".length()));
                continue;
            }
            if (args[i].equals("-I") && i + 1 < args.length) {
                searchDirs.add(Path.of(args[i + 1]));
                i++;
                continue;
            }
            if (args[i].equals("-S")) {
                assembly = true;
                continue;
            }
            if (args[i].equals("-a")) {
                annotate = true;
                continue;
            }
            if (args[i].equals("-c")) {
                object = true;
                continue;
            }
            if (args[i].equals("-o") && i + 1 < args.length) {
                output = args[i + 1];
                i++;
                continue;
            }
            file = args[i];
            source = Files.readString(Path.of(file));
        }
        // A native build declares the C library from the system's headers;
        // the VM's view uses the bundled subset it implements.
        HeaderProvider headers = assembly || object ? HeaderProvider.system(searchDirs) : HeaderProvider.standard(searchDirs);
        var types = new Types(X86_64SysV.INSTANCE, std);
        Compiler.Compiled compiled = Compiler.compile(source, headers, file, types);
        if (object) {
            String name = output != null ? output : file.replaceAll("\\.c$", "") + ".o";
            Files.write(Path.of(name), Codegen.object(compiled.tac()));
            return;
        }
        if (assembly) {
            System.out.print(Codegen.emit(compiled.tac(), annotate));
            return;
        }

        // The three stages a reader may want to see: the syntax tree, the
        // typed tree, and the TAC that the VM and code generation consume.
        System.out.println(AstPrinter.print(compiled.ast()));
        System.out.println();
        System.out.println(TypedPrinter.print(compiled.typed()));
        System.out.println();
        System.out.print(TacWriter.print(compiled.tac()));
    }
}

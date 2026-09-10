package org.jbm;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.parse.ast.AstPrinter;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.lower.tac.TacWriter;
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
    public static void main(String[] args) throws IOException {
        List<Path> searchDirs = new ArrayList<>();
        String file = "<source>";
        String source = SOURCE;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-I") && i + 1 < args.length) {
                searchDirs.add(Path.of(args[i + 1]));
                i++;
                continue;
            }
            file = args[i];
            source = Files.readString(Path.of(file));
        }
        HeaderProvider headers = HeaderProvider.standard(searchDirs);
        var types = new Types(X86_64SysV.INSTANCE);
        Compiler.Compiled compiled = Compiler.compile(source, headers, file, types);

        // The three stages a reader may want to see: the syntax tree, the
        // typed tree, and the TAC that the VM and code generation consume.
        System.out.println(AstPrinter.print(compiled.ast()));
        System.out.println();
        System.out.println(TypedPrinter.print(compiled.typed()));
        System.out.println();
        System.out.print(TacWriter.print(compiled.tac()));
    }
}

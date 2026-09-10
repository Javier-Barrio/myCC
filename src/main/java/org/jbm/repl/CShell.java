package org.jbm.repl;

import org.jbm.cc.cpp.HeaderProvider;
import org.jbm.cc.lower.arch.X86_64SysV;
import org.jbm.cc.sema.types.Types;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The shell's entry: {@code cshell [-I dir]...}. */
public final class CShell {

    private CShell() {
    }

    public static void main(String[] args) {
        List<Path> searchDirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-I") && i + 1 < args.length) {
                searchDirs.add(Path.of(args[i + 1]));
                i++;
            }
        }
        Console console = new StdioConsole();
        console.print("|  Welcome to cshell. Type C declarations, statements or expressions; /exit ends.");
        new Repl(console, HeaderProvider.standard(searchDirs), new Types(X86_64SysV.INSTANCE)).run();
    }
}

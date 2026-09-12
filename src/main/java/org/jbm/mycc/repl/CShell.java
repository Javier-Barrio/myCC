package org.jbm.mycc.repl;

import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.sema.types.Types;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        // A terminal gets JLine: editing, history, completion. A pipe gets
        // plain stdio, so a scripted session prints exactly its lines. The
        // completer needs the shell and the shell its console, so the
        // console is chosen once the shell exists.
        Switchable console = new Switchable();
        Repl repl = new Repl(console, HeaderProvider.standard(searchDirs), new Types(X86_64SysV.INSTANCE));
        console.target = System.console() != null ? new JLineConsole(new Completer(repl)) : new StdioConsole();
        console.print("|  Welcome to cshell. Type C declarations, statements or expressions; /help lists the commands, /exit ends.");
        repl.run();
    }

    private static final class Switchable implements Console {
        Console target = new StdioConsole();

        @Override
        public Optional<String> readLine(String prompt) {
            return target.readLine(prompt);
        }

        @Override
        public void print(String line) {
            target.print(line);
        }

        @Override
        public void write(String text) {
            target.write(text);
        }
    }
}

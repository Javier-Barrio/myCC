package org.jbm.mycc;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.backend.codegen.Codegen;
import org.jbm.mycc.cc.cpp.CppToken;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.sema.types.Std;
import org.jbm.mycc.cc.sema.types.Types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A compiler driver that takes gcc's command line, so a build system
 * can be pointed at it with {@code CC=mycc}: {@code -c}, {@code -S},
 * {@code -E}, {@code -o}, {@code -I}, {@code -D}, {@code -U},
 * {@code -include}, {@code -std=}; the optimization, warning, debug and
 * dependency flags are accepted and ignored. Without {@code -c},
 * {@code -S} or {@code -E} it compiles the C files it is given and hands
 * the objects and the rest of the line to {@code gcc} to link, until
 * there is a linker of our own. Every diagnostic is one line on
 * standard error, and the exit status is 1 when anything failed.
 */
public final class Driver {

    private final List<Path> includeDirs = new ArrayList<>();
    private final StringBuilder preamble = new StringBuilder();
    private final List<String> sources = new ArrayList<>();
    private final List<String> others = new ArrayList<>();       // objects, libraries, linker flags
    private String output;
    private Std std = Std.C23;
    private boolean compileOnly;
    private boolean assemblyOnly;
    private boolean preprocessOnly;
    private boolean annotate;

    public static void main(String[] args) throws IOException, InterruptedException {
        System.exit(new Driver().run(args));
    }

    int run(String[] args) throws IOException, InterruptedException {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--version", "-v" -> {
                    System.out.println("mycc (myCc) 0.1, C23 and C17 for x86-64 Linux");
                    return 0;
                }
                case "-c" -> compileOnly = true;
                case "-S" -> assemblyOnly = true;
                case "-E" -> preprocessOnly = true;
                case "-a" -> annotate = true;
                case "-o" -> output = args[++i];
                case "-I", "-isystem", "-iquote" -> includeDirs.add(Path.of(args[++i]));
                case "-D" -> define(args[++i]);
                case "-U" -> preamble.append("#undef ").append(args[++i]).append('\n');
                case "-include" -> preamble.append("#include \"").append(args[++i]).append("\"\n");
                case "-MT", "-MQ", "-MF" -> i++;                         // dependency output: not produced
                case "-x" -> i++;                                        // the language: C is all there is
                case "-pthread", "-shared", "-static", "-rdynamic", "-pie", "-no-pie" -> others.add(a);
                default -> other(a);
            }
        }
        if (sources.isEmpty() && others.isEmpty()) {
            System.err.println("mycc: no input files");
            return 1;
        }
        if (output != null && sources.size() > 1 && (compileOnly || assemblyOnly || preprocessOnly)) {
            System.err.println("mycc: -o with several inputs and -c, -S or -E");
            return 1;
        }
        var types = new Types(X86_64SysV.INSTANCE, std);
        HeaderProvider headers = HeaderProvider.system(includeDirs);
        List<String> objects = new ArrayList<>();
        Path scratch = null;
        for (String source : sources) {
            String text = preamble + "#line 1 \"" + source + "\"\n" + Files.readString(Path.of(source));
            try {
                if (preprocessOnly) {
                    String out = preprocessed(Compiler.preprocess(text, headers, source, types));
                    write(output, out);
                    continue;
                }
                Compiler.Compiled compiled = Compiler.compile(text, headers, source, types);
                if (assemblyOnly) {
                    write(output != null ? output : replaceExtension(source, ".s"), Codegen.emit(compiled.tac(), annotate));
                    continue;
                }
                String object;
                if (compileOnly) {
                    object = output != null ? output : replaceExtension(Path.of(source).getFileName().toString(), ".o");
                } else {
                    if (scratch == null) {
                        scratch = Files.createTempDirectory("mycc");
                    }
                    object = scratch.resolve(replaceExtension(Path.of(source).getFileName().toString(), ".o")).toString();
                }
                Files.write(Path.of(object), Codegen.object(compiled.tac()));
                objects.add(object);
            } catch (RuntimeException e) {
                System.err.println("mycc: error: " + e.getMessage());
                return 1;
            }
        }
        if (compileOnly || assemblyOnly || preprocessOnly) {
            return 0;
        }
        return link(objects);
    }

    private void define(String spec) {
        int eq = spec.indexOf('=');
        String name = eq < 0 ? spec : spec.substring(0, eq);
        String value = eq < 0 ? "1" : spec.substring(eq + 1);
        preamble.append("#define ").append(name).append(' ').append(value).append('\n');
    }

    // A joined option, a source, or something for the linker.
    private void other(String a) {
        if (a.startsWith("-D")) {
            define(a.substring(2));
        } else if (a.startsWith("-U")) {
            preamble.append("#undef ").append(a.substring(2)).append('\n');
        } else if (a.startsWith("-I")) {
            includeDirs.add(Path.of(a.substring(2)));
        } else if (a.startsWith("-std=")) {
            std = Std.of(a.substring(5));
        } else if (a.startsWith("-O") || a.startsWith("-g") || a.startsWith("-W") || a.startsWith("-f") || a.startsWith("-m")
                || a.startsWith("-M") || a.equals("-pipe") || a.equals("-ansi") || a.equals("-pedantic")) {
            // nothing to optimize, warn about, debug or depend on
        } else if (a.startsWith("-l") || a.startsWith("-L") || a.startsWith("-Wl,") || a.endsWith(".o") || a.endsWith(".a")
                || a.endsWith(".so") || a.endsWith(".s") || a.endsWith(".S")) {
            others.add(a);
        } else if (a.endsWith(".c")) {
            sources.add(a);
        } else if (a.startsWith("-")) {
            // an option we do not know: gcc might, so keep it for the link
            others.add(a);
        } else {
            others.add(a);
        }
    }

    private int link(List<String> objects) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("gcc");
        if (output != null) {
            command.add("-o");
            command.add(output);
        }
        command.addAll(objects);
        command.addAll(others);
        Process gcc = new ProcessBuilder(command).inheritIO().start();
        return gcc.waitFor();
    }

    private static void write(String path, String text) throws IOException {
        if (path == null || path.equals("-")) {
            System.out.print(text);
        } else {
            Files.writeString(Path.of(path), text);
        }
    }

    private static String replaceExtension(String path, String extension) {
        int dot = path.lastIndexOf('.');
        return (dot < 0 ? path : path.substring(0, dot)) + extension;
    }

    // The tokens as text, a new line when the source line advances, so
    // what reads the output finds what it greps for. A token from a macro
    // body carries its definition's line, which is behind: it stays on
    // the line of its use.
    static String preprocessed(TokenSet tokens) {
        var out = new StringBuilder();
        int line = -1;
        for (CppToken t : tokens.tokens) {
            if (t.token.type == TokenType.EOF) {
                continue;
            }
            if (t.token.line > line) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                line = t.token.line;
            } else if (t.spaceBefore || t.token.line < line) {
                out.append(' ');
            }
            out.append(t.token.text);
        }
        return out.append('\n').toString();
    }
}

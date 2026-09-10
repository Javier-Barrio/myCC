package org.jbm.repl;

import org.jbm.cc.Compiler;
import org.jbm.cc.cpp.CppTokenizer.LexException;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jbm.cc.cpp.HeaderProvider;
import org.jbm.cc.cpp.TokenConversion.ConversionException;
import org.jbm.cc.parse.ParseException;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.parse.ast.Decl;
import org.jbm.cc.sema.SemaException;
import org.jbm.cc.sema.tast.TExpr;
import org.jbm.cc.sema.tast.TFunction;
import org.jbm.cc.sema.tast.TStmt;
import org.jbm.cc.sema.tast.TUnit;
import org.jbm.cc.sema.types.CType;
import org.jbm.cc.sema.types.Types;
import org.jbm.vm.VM;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The loop: each line is compiled together with every kept line, in
 * script mode, and the module goes to the VM, which binds what is new
 * and runs {@code .file} if the line had statements. Declarations are
 * kept for the next line; statements are not. An expression gets a
 * {@code $N} whose declaration is kept.
 */
public final class Repl {

    public static final String PROMPT = "cshell> ";
    public static final String MORE = "   ...> ";
    static final String FILE = "cshell";

    private final Console console;
    private final HeaderProvider headers;
    private final Types types;
    private final VM vm = new VM();
    private final ValuePrinter printer;
    private final List<String> kept = new ArrayList<>();
    private int results;

    public Repl(Console console, HeaderProvider headers, Types types) {
        this.console = console;
        this.headers = headers;
        this.types = types;
        this.printer = new ValuePrinter(vm, types);
        vm.step(Compiler.compileScript("", headers, FILE, types).tac());
    }

    public VM vm() {
        return vm;
    }

    /** The lines kept so far: directives, declarations, {@code $N} declarations. */
    public List<String> kept() {
        return List.copyOf(kept);
    }

    /** Reads and handles lines until the end of input or {@code /exit}. */
    public void run() {
        while (true) {
            Optional<String> line = console.readLine(PROMPT);
            if (line.isEmpty()) {
                return;
            }
            if (!handle(line.get())) {
                return;
            }
        }
    }

    /** Handles one line, asking for more when it is incomplete; false means exit. */
    public boolean handle(String line) {
        String text = line;
        while (true) {
            if (text.isBlank()) {
                return true;
            }
            if (text.strip().equals("/exit")) {
                return false;
            }
            if (text.strip().startsWith("/")) {
                console.print("|  unknown command: " + text.strip());
                return true;
            }
            if (!snippet(text)) {
                Optional<String> more = console.readLine(MORE);
                if (more.isEmpty()) {
                    console.print("|  error: incomplete input");
                    return true;
                }
                text = text + "\n" + more.get();
                continue;
            }
            return true;
        }
    }

    // ---- one snippet ---------------------------------------------------------------------------

    // A compile of the kept text plus a snippet: the result, or that the
    // snippet is incomplete, or an error already reported.
    private record Attempt(Optional<Compiler.Compiled> compiled, boolean incomplete) {
    }

    private String prefix() {
        StringBuilder sb = new StringBuilder();
        for (String k : kept) {
            sb.append(k).append('\n');
        }
        return sb.toString();
    }

    private int startLine() {
        return kept.stream().mapToInt(k -> (int) k.chars().filter(c -> c == '\n').count() + 1).sum() + 1;
    }

    private Attempt attempt(String text) {
        try {
            return new Attempt(Optional.of(Compiler.compileScript(prefix() + text, headers, FILE, types)), false);
        } catch (ParseException e) {
            if (e.token.type == TokenType.EOF) {
                return new Attempt(Optional.empty(), true);
            }
            console.print("|  error: " + relocate(e.getMessage()));
        } catch (SemaException | LexException | ConversionException e) {
            console.print("|  error: " + relocate(e.getMessage()));
        }
        return new Attempt(Optional.empty(), false);
    }

    // Locations in the concatenated text, `at cshell:L:C` or `at L:C`,
    // become the snippet's own line and column; a location inside the
    // kept text names the kept line.
    private static final Pattern LOCATION = Pattern.compile("at (?:" + FILE + ":)?(\\d+):(\\d+)");

    private String relocate(String message) {
        return relocate(message, 0, 0);
    }

    // `skipLines` and `skipColumns` are what a rewrite put before the
    // user's text: the expression line lives at line 2 of its rewrite,
    // after `$N = (`.
    private String relocate(String message, int skipLines, int skipColumns) {
        int start = startLine();
        Matcher m = LOCATION.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int line = Integer.parseInt(m.group(1));
            int column = Integer.parseInt(m.group(2));
            String replacement;
            if (line >= start) {
                int own = line - start + 1 - skipLines;
                int col = own == 1 ? column - skipColumns : column;
                replacement = "at " + Math.max(own, 1) + ":" + Math.max(col, 1);
            } else {
                replacement = "at kept line " + keptIndex(line) + ":" + column;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // The 1-based number of the kept entry holding a line of the concatenation.
    private int keptIndex(int line) {
        int at = 1;
        for (int i = 0; i < kept.size(); i++) {
            int lines = (int) kept.get(i).chars().filter(c -> c == '\n').count() + 1;
            if (line < at + lines) {
                return i + 1;
            }
            at += lines;
        }
        return kept.size();
    }

    /** Compiles and runs one snippet; false when it is incomplete. */
    private boolean snippet(String text) {
        Attempt a = attempt(text);
        if (a.incomplete()) {
            return false;
        }
        if (a.compiled().isEmpty()) {
            return true;
        }
        Compiler.Compiled compiled = a.compiled().get();
        Optional<TFunction> file = fileFunction(compiled.typed());
        if (file.isPresent() && declares(compiled, startLine())) {
            console.print("|  error: a line holds declarations or statements, not both");
            return true;
        }
        if (file.isPresent() && isExpression(file.get())) {
            if (expression(text, expressionType(file.get()))) {
                return true;
            }
        }
        if (!load(compiled, this::relocate)) {
            return true;
        }
        if (file.isEmpty()) {
            int start = startLine();
            kept.add(text);
            echoDeclarations(compiled, start);
        }
        return true;
    }

    // A non-void expression statement as the whole of .file: the line
    // was an expression, worth a $N. Compiled once more with the
    // declaration of $N kept and the assignment run.
    private boolean expression(String text, CType type) {
        int n = results + 1;
        String name = "$" + n;
        // An array cannot be assigned: $N is then a pointer to its first
        // element, and what is printed is the array it points into.
        boolean array = type instanceof CType.Array;
        String declaration = array
                ? "typeof_unqual((" + text + ")[0]) *" + name + ";"
                : "typeof_unqual((" + text + ")) " + name + ";";
        String assignment = name + " = (" + text + ");";
        Compiler.Compiled compiled;
        try {
            compiled = Compiler.compileScript(prefix() + declaration + "\n" + assignment, headers, FILE, types);
        } catch (RuntimeException e) {
            return false;
        }
        int skip = (name + " = (").length();
        if (!load(compiled, message -> relocate(message, 1, skip))) {
            return true;
        }
        results = n;
        kept.add(declaration);
        if (array) {
            long address = vm.memory().loadInt(vm.addressOf(name), (int) types.size(typeOf(compiled.typed(), name)) * 8, false);
            console.print(name + " ==> " + printer.printAt(address, type));
        } else {
            console.print(name + " ==> " + printer.print(name, typeOf(compiled.typed(), name)));
        }
        return true;
    }

    // The module into the VM; a fault is reported and nothing is kept.
    private boolean load(Compiler.Compiled compiled, java.util.function.UnaryOperator<String> relocator) {
        try {
            vm.step(compiled.tac());
            return true;
        } catch (IllegalStateException e) {
            console.print("|  fault: " + relocator.apply(e.getMessage()));
            return false;
        }
    }

    // ---- what a snippet declared -------------------------------------------------------------------

    private static Optional<TFunction> fileFunction(TUnit unit) {
        return unit.functions().stream().filter(f -> f.symbol().name.equals(Parser.FILE_FUNCTION)).findFirst();
    }

    private static boolean isExpression(TFunction file) {
        List<TStmt> items = file.body().items();
        if (items.size() != 1) {
            return false;
        }
        return items.get(0) instanceof TStmt.ExprStmt e && !(e.expr().type() instanceof CType.Void);
    }

    // The expression's type; an array that decayed to a pointer reads as
    // the array, which is what a user typing `v` wants to see.
    private static CType expressionType(TFunction file) {
        TExpr expr = ((TStmt.ExprStmt) file.body().items().get(0)).expr();
        if (expr instanceof TExpr.ArrayDecay decay) {
            return decay.operand().type();
        }
        return expr.type();
    }

    // The declarations from the snippet: those at or after its first line.
    private static List<Decl> declarations(Compiler.Compiled compiled, int startLine) {
        List<Decl> out = new ArrayList<>();
        for (Decl d : compiled.ast()) {
            if (d instanceof Decl.FunctionDefinition f && !f.name().text.equals(Parser.FILE_FUNCTION) && inSnippet(f.name(), startLine)) {
                out.add(d);
            } else if (d instanceof Decl.Declaration decl && inSnippet(decl.specifiers().token(), startLine)) {
                out.add(d);
            }
        }
        return out;
    }

    // Tokens from an included header carry the header's name and lines.
    private static boolean inSnippet(org.jbm.cc.cpp.CppTokenizer.Token t, int startLine) {
        return t.file.equals(FILE) && t.line >= startLine;
    }

    private static boolean declares(Compiler.Compiled compiled, int startLine) {
        return !declarations(compiled, startLine).isEmpty();
    }

    // `|  defined f` for a function; `x ==> value` for an object with storage.
    private void echoDeclarations(Compiler.Compiled compiled, int startLine) {
        for (Decl d : declarations(compiled, startLine)) {
            if (d instanceof Decl.FunctionDefinition f) {
                console.print("|  defined " + f.name().text);
            } else if (d instanceof Decl.Declaration decl) {
                boolean typedef = decl.specifiers().storageClasses().stream().anyMatch(t -> t.text.equals("typedef"));
                if (typedef) {
                    continue;
                }
                for (Decl.InitDeclarator id : decl.declarators()) {
                    String name = id.name().text;
                    Optional<TUnit.Global> g = compiled.typed().globals().stream()
                            .filter(x -> x.symbol().name.equals(name) && x.isDefinition()).findFirst();
                    if (g.isPresent()) {
                        console.print(name + " ==> " + printer.print(name, g.get().symbol().type()));
                    }
                }
            }
        }
    }

    private static CType typeOf(TUnit unit, String name) {
        return unit.globals().stream().filter(g -> g.symbol().name.equals(name)).findFirst().orElseThrow().symbol().type();
    }
}

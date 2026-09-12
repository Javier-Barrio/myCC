package org.jbm.mycc.repl;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.codegen.Codegen;
import org.jbm.mycc.cc.cpp.CppTokenizer.LexException;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.cpp.TokenConversion.ConversionException;
import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.Global;
import org.jbm.mycc.cc.backend.lower.tac.TacWriter;
import org.jbm.mycc.cc.parse.ParseException;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.parse.ast.Type;
import org.jbm.mycc.cc.sema.SemaException;
import org.jbm.mycc.cc.sema.tast.TExpr;
import org.jbm.mycc.cc.sema.tast.TFunction;
import org.jbm.mycc.cc.sema.tast.TStmt;
import org.jbm.mycc.cc.sema.tast.TUnit;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Types;
import org.jbm.mycc.repl.vm.Builtin;
import org.jbm.mycc.repl.vm.VM;
import org.jbm.mycc.repl.vm.builtins.Libc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The loop: each line is compiled together with every kept line, in
 * script mode, and the module goes to the VM, which binds what is new
 * and runs {@code .file} if the line had statements. Declarations are
 * kept for the next line; statements are not. An expression gets a
 * {@code $N} whose declaration is kept. A declaration of a name an
 * earlier kept line declared replaces that line.
 */
public final class Repl {

    public static final String PROMPT = "cshell> ";
    public static final String MORE = "   ...> ";
    static final String FILE = "cshell";

    /** A kept line and the file-scope names it declares. */
    public record Kept(String text, Set<String> names) {
    }

    private final Console console;
    private final HeaderProvider headers;
    private final Types types;
    private VM vm;
    private ValuePrinter printer;
    private final List<Kept> kept = new ArrayList<>();
    private int results;
    private Compiler.Compiled last;

    public Repl(Console console, HeaderProvider headers, Types types) {
        this.console = console;
        this.headers = headers;
        this.types = types;
        reset();
    }

    private void reset() {
        vm = new VM();
        bound.clear();
        boundObjects.clear();
        vm.out(new PrintStream(new ConsoleStream(), true, StandardCharsets.ISO_8859_1));
        printer = new ValuePrinter(vm, types);
        kept.clear();
        results = 0;
        last = Compiler.compileScript("", headers, FILE, types);
        vm.step(last.tac());
    }

    public VM vm() {
        return vm;
    }

    // The library is bound one name at a time, exactly while a header or
    // a prototype declares it: nothing is provided that the program has
    // not asked for, and dropping the include withdraws it again.
    private final Map<String, Builtin> library = Libc.all();
    private final Set<String> bound = new HashSet<>();
    private final Set<String> boundObjects = new HashSet<>();

    private void bindDeclared(Compiler.Compiled compiled) {
        Set<String> declared = new HashSet<>();
        for (Decl d : compiled.ast()) {
            if (d instanceof Decl.Declaration decl) {
                for (Decl.InitDeclarator id : decl.declarators()) {
                    if (id.type().isPresent() && id.type().get() instanceof Type.Function) {
                        declared.add(id.name().text);
                    }
                }
            }
        }
        for (String name : declared) {
            if (library.containsKey(name) && bound.add(name)) {
                vm.bind(name, library.get(name));
            }
        }
        for (Decl d : compiled.ast()) {
            if (d instanceof Decl.Declaration decl) {
                for (Decl.InitDeclarator id : decl.declarators()) {
                    byte[] bytes = Libc.objects().get(id.name().text);
                    if (bytes != null && boundObjects.add(id.name().text)) {
                        vm.bindObject(id.name().text, bytes);
                    }
                }
            }
        }
        for (String name : new ArrayList<>(bound)) {
            if (!declared.contains(name)) {
                vm.unbind(name);
                bound.remove(name);
            }
        }
    }

    // The program's output, byte by byte from the builtins, to the
    // console; whether it left the cursor in the middle of a line.
    private boolean midLine;

    private final class ConsoleStream extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            pending.write(b);
        }

        @Override
        public void flush() {
            if (pending.size() > 0) {
                String text = pending.toString(StandardCharsets.ISO_8859_1);
                console.write(text);
                midLine = !text.endsWith("\n");
                pending.reset();
            }
        }
    }

    // The shell's own lines start at a line start: a program's unfinished
    // line is ended first.
    private void say(String line) {
        if (midLine) {
            console.write("\n");
            midLine = false;
        }
        console.print(line);
    }

    /** The last successful compile: the typed tree and syntax tree completion reads. */
    public Compiler.Compiled last() {
        return last;
    }

    /** The kept lines with the names each declares. */
    public List<Kept> keptLines() {
        return List.copyOf(kept);
    }

    /** The names of the commands, for completion and help. */
    public static List<String> commands() {
        return List.of("/list", "/vars", "/funcs", "/types", "/macros", "/tac", "/asm", "/drop", "/load", "/save", "/reset", "/help", "/exit");
    }

    /** The lines kept so far: directives, declarations, {@code $N} declarations. */
    public List<String> kept() {
        return kept.stream().map(Kept::text).toList();
    }

    /** Reads and handles lines until the end of input or {@code /exit}. */
    public void run() {
        while (true) {
            if (midLine) {
                console.write("\n");
                midLine = false;
            }
            Optional<String> line = console.readLine(PROMPT);
            if (line.isEmpty()) {
                return;
            }
            if (!handle(line.get())) {
                return;
            }
        }
    }

    /** Handles one line, asking the console for more when it is incomplete; false means exit. */
    public boolean handle(String line) {
        return handle(line, () -> console.readLine(MORE));
    }

    // `more` supplies the continuation lines: the console, or the rest of a loaded file.
    private boolean handle(String line, Supplier<Optional<String>> more) {
        String text = line;
        while (true) {
            if (text.isBlank()) {
                return true;
            }
            if (text.strip().startsWith("/")) {
                return command(text.strip());
            }
            if (!snippet(text)) {
                Optional<String> next = more.get();
                if (next.isEmpty()) {
                    say("|  error: incomplete input");
                    return true;
                }
                text = text + "\n" + next.get();
                continue;
            }
            return true;
        }
    }

    // ---- the kept text -------------------------------------------------------------------------

    private static String text(List<Kept> entries) {
        StringBuilder sb = new StringBuilder();
        for (Kept k : entries) {
            sb.append(k.text()).append('\n');
        }
        return sb.toString();
    }

    private String prefix() {
        return text(kept);
    }

    private static int lines(String text) {
        return (int) text.chars().filter(c -> c == '\n').count() + 1;
    }

    // The line of the concatenation at which entry `index` starts.
    private static int startLine(List<Kept> entries, int index) {
        return entries.subList(0, index).stream().mapToInt(k -> lines(k.text())).sum() + 1;
    }

    private int startLine() {
        return startLine(kept, kept.size());
    }

    // The 1-based number of the entry holding a line of the concatenation.
    private static int indexAt(List<Kept> entries, int line) {
        int at = 1;
        for (int i = 0; i < entries.size(); i++) {
            int n = lines(entries.get(i).text());
            if (line < at + n) {
                return i + 1;
            }
            at += n;
        }
        return entries.size();
    }

    // ---- one snippet ---------------------------------------------------------------------------

    private record Attempt(Optional<Compiler.Compiled> compiled, boolean incomplete) {
    }

    // The kept lines with the snippet at `index`: what is compiled, and
    // what the kept lines become if it succeeds. A declaration that
    // replaces an earlier line takes its place, since in C a use must
    // follow its declaration.
    private record Trial(List<Kept> entries, int index, int start, int lines) {
        String text() {
            return Repl.text(entries);
        }

        boolean inSnippet(int line) {
            return line >= start && line < start + lines;
        }
    }

    private Trial trial(List<Kept> base, int index, String text, Set<String> names) {
        List<Kept> entries = new ArrayList<>(base);
        entries.add(index, new Kept(text, names));
        return new Trial(entries, index, startLine(base, index), lines(text));
    }

    private Attempt attempt(Trial trial) {
        try {
            return new Attempt(Optional.of(Compiler.compileScript(trial.text(), headers, FILE, types)), false);
        } catch (ParseException e) {
            if (e.token.type == TokenType.EOF) {
                return new Attempt(Optional.empty(), true);
            }
            report("error", e.getMessage(), trial, 0, 0, typed(trial));
        } catch (SemaException | LexException | ConversionException e) {
            report("error", e.getMessage(), trial, 0, 0, typed(trial));
        }
        return new Attempt(Optional.empty(), false);
    }

    // The message with its locations relocated, then the line the first
    // location is on and a caret under its column. `typed` is what the
    // user wrote when the snippet is a rewrite of it.
    private void report(String kind, String message, Trial trial, int skipLines, int skipColumns, String typed) {
        say("|  " + kind + ": " + relocate(message, trial, skipLines, skipColumns));
        Matcher m = LOCATION.matcher(message);
        if (!m.find()) {
            return;
        }
        int line = Integer.parseInt(m.group(1));
        int column = Integer.parseInt(m.group(2));
        String[] all = trial.text().split("\n", -1);
        if (line < 1 || line > all.length) {
            return;
        }
        String source = all[line - 1];
        if (trial.inSnippet(line)) {
            int own = line - trial.start() + 1 - skipLines;
            String[] lines = typed.split("\n", -1);
            if (own < 1 || own > lines.length) {
                return;
            }
            source = lines[own - 1];
            if (own == 1) {
                column -= skipColumns;
            }
        }
        say("|  " + source);
        say("|  " + " ".repeat(Math.max(column - 1, 0)) + "^");
    }

    private static String typed(Trial trial) {
        return trial.lines() == 0 ? "" : trial.entries().get(trial.index()).text();
    }

    /** Compiles and runs one snippet; false when it is incomplete. */
    private boolean snippet(String text) {
        // A name declared again replaces the first line that declared it,
        // in place; the other lines declaring any of the names go.
        Set<String> names = declaredNames(text);
        List<Kept> base = new ArrayList<>(kept);
        List<Kept> replaced = new ArrayList<>();
        int index = base.size();
        for (Kept k : new ArrayList<>(base)) {
            if (k.names().stream().anyMatch(names::contains)) {
                if (replaced.isEmpty()) {
                    index = base.indexOf(k);
                }
                replaced.add(k);
                base.remove(k);
            }
        }
        Trial trial = trial(base, index, text, names);
        Attempt a = attempt(trial);
        if (a.incomplete() || a.compiled().isEmpty()) {
            return !a.incomplete();
        }
        Compiler.Compiled compiled = a.compiled().get();
        Optional<TFunction> file = fileFunction(compiled.typed());
        if (file.isPresent() && !declarations(compiled, trial).isEmpty()) {
            say("|  error: a line holds declarations or statements, not both");
            return true;
        }
        if (file.isPresent() && isExpression(file.get()) && expression(text, expressionType(file.get()))) {
            return true;
        }
        // a replaced object is initialized afresh, as jshell does
        for (Kept k : replaced) {
            for (String n : k.names()) {
                vm.drop(n);
            }
        }
        if (!load(compiled, trial, 0, 0, text)) {
            return true;
        }
        last = compiled;
        if (file.isEmpty()) {
            kept.clear();
            kept.addAll(trial.entries());
            for (Kept k : replaced) {
                say("|  replaced " + String.join(", ", k.names()));
            }
            echoDeclarations(compiled, trial);
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
        Trial trial = trial(kept, kept.size(), declaration + "\n" + assignment, Set.of(name));
        Compiler.Compiled compiled;
        try {
            compiled = Compiler.compileScript(trial.text(), headers, FILE, types);
        } catch (RuntimeException e) {
            return false;
        }
        int skip = (name + " = (").length();
        if (!load(compiled, trial, 1, skip, text)) {
            return true;
        }
        results = n;
        last = compiled;
        kept.add(new Kept(declaration, Set.of(name)));
        if (array) {
            long address = vm.memory().loadInt(vm.addressOf(name), (int) types.size(typeOf(compiled.typed(), name)) * 8, false);
            say(name + " ==> " + printer.printAt(address, type));
        } else {
            say(name + " ==> " + printer.print(name, typeOf(compiled.typed(), name)));
        }
        return true;
    }

    // The module into the VM; a fault is reported and nothing is kept.
    private boolean load(Compiler.Compiled compiled, Trial trial, int skipLines, int skipColumns, String typed) {
        bindDeclared(compiled);
        try {
            vm.step(compiled.tac());
            return true;
        } catch (Libc.Exit e) {
            say("|  " + e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            report("fault", e.getMessage(), trial, skipLines, skipColumns, typed);
            return false;
        }
    }

    // ---- locations -----------------------------------------------------------------------------

    // Locations in the concatenated text, `at cshell:L:C` or `at L:C`,
    // become the snippet's own line and column; a location inside the
    // kept text names the kept line.
    private static final Pattern LOCATION = Pattern.compile("at (?:" + FILE + ":)?(\\d+):(\\d+)");

    // Over the kept lines alone: for a command's compile.
    private String relocate(String message) {
        return relocate(message, new Trial(kept, kept.size(), startLine(), 0), 0, 0);
    }

    // `skipLines` and `skipColumns` are what a rewrite put before the
    // user's text: the expression line lives at line 2 of its rewrite,
    // after `$N = (`.
    private static String relocate(String message, Trial trial, int skipLines, int skipColumns) {
        Matcher m = LOCATION.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int line = Integer.parseInt(m.group(1));
            int column = Integer.parseInt(m.group(2));
            String replacement;
            if (trial.inSnippet(line)) {
                int own = line - trial.start() + 1 - skipLines;
                int col = own == 1 ? column - skipColumns : column;
                replacement = "at " + Math.max(own, 1) + ":" + Math.max(col, 1);
            } else {
                int entry = indexAt(trial.entries(), line);
                if (entry > trial.index() && trial.lines() > 0) {
                    entry--;   // numbered as kept, without the snippet
                }
                replacement = "at kept line " + entry + ":" + column;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Locations at or past `from` moved down by `count` lines.
    private static String shiftLines(String message, int from, int count) {
        Matcher m = LOCATION.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int line = Integer.parseInt(m.group(1));
            int shifted = line >= from ? line + count : line;
            m.appendReplacement(sb, Matcher.quoteReplacement("at " + FILE + ":" + shifted + ":" + m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- what a snippet declares -----------------------------------------------------------------

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

    // Tokens from an included header carry the header's name and lines.
    private static boolean inSnippet(Token t, Trial trial) {
        return t.file.equals(FILE) && trial.inSnippet(t.line);
    }

    private static List<Decl> declarations(Compiler.Compiled compiled, Trial trial) {
        return declarations(compiled.ast(), trial);
    }

    // The declarations from the snippet: those on its lines.
    private static List<Decl> declarations(List<Decl> ast, Trial trial) {
        List<Decl> out = new ArrayList<>();
        for (Decl d : ast) {
            if (d instanceof Decl.FunctionDefinition f && !f.name().text.equals(Parser.FILE_FUNCTION) && inSnippet(f.name(), trial)) {
                out.add(d);
            } else if (d instanceof Decl.Declaration decl && inSnippet(decl.specifiers().token(), trial)) {
                out.add(d);
            }
        }
        return out;
    }

    private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    // The names a snippet declares, before compiling it: what a
    // redefinition replaces. A parse is enough, and unlike a compile it
    // does not object to the redefinition itself.
    private Set<String> declaredNames(String text) {
        Trial atEnd = trial(kept, kept.size(), text, Set.of());
        try {
            Compiler.Parsed parsed = Compiler.parseScript(atEnd.text(), headers, FILE, types.std());
            return names(parsed.ast(), atEnd, text);
        } catch (RuntimeException e) {
            return names(List.of(), atEnd, text);
        }
    }

    private Set<String> names(List<Decl> ast, Trial trial, String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = DEFINE.matcher(text);
        if (m.find()) {
            names.add(m.group(1));
        }
        for (Decl d : declarations(ast, trial)) {
            if (d instanceof Decl.FunctionDefinition f) {
                names.add(f.name().text);
            } else if (d instanceof Decl.Declaration decl) {
                for (Decl.InitDeclarator id : decl.declarators()) {
                    names.add(id.name().text);
                }
                tag(decl.specifiers().type()).ifPresent(names::add);
            }
        }
        return names;
    }

    private static Optional<String> tag(Optional<Type> type) {
        if (type.isPresent() && type.get() instanceof Type.Struct s && s.tag().isPresent() && s.members().isPresent()) {
            return Optional.of(s.keyword().text + " " + s.tag().get().text);
        }
        if (type.isPresent() && type.get() instanceof Type.Enum e && e.tag().isPresent() && e.enumerators().isPresent()) {
            return Optional.of("enum " + e.tag().get().text);
        }
        return Optional.empty();
    }

    private static boolean isTypedef(Decl.Declaration decl) {
        return decl.specifiers().storageClasses().stream().anyMatch(t -> t.text.equals("typedef"));
    }

    // `|  defined f` for a function; `x ==> value` for an object with storage.
    private void echoDeclarations(Compiler.Compiled compiled, Trial trial) {
        for (Decl d : declarations(compiled, trial)) {
            if (d instanceof Decl.FunctionDefinition f) {
                say("|  defined " + f.name().text);
            } else if (d instanceof Decl.Declaration decl && !isTypedef(decl)) {
                for (Decl.InitDeclarator id : decl.declarators()) {
                    String name = id.name().text;
                    Optional<TUnit.Global> g = definedGlobal(compiled.typed(), name);
                    if (g.isPresent()) {
                        say(name + " ==> " + printer.print(name, g.get().symbol().type()));
                    }
                }
            }
        }
    }

    private static Optional<TUnit.Global> definedGlobal(TUnit unit, String name) {
        return unit.globals().stream().filter(x -> x.symbol().name.equals(name) && x.isDefinition()).findFirst();
    }

    private static CType typeOf(TUnit unit, String name) {
        return unit.globals().stream().filter(g -> g.symbol().name.equals(name)).findFirst().orElseThrow().symbol().type();
    }

    // ---- commands ------------------------------------------------------------------------------

    private boolean command(String line) {
        String[] parts = line.split("\\s+", 2);
        String name = parts[0];
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (name) {
            case "/exit" -> {
                return false;
            }
            case "/help" -> help();
            case "/list" -> list(arg);
            case "/vars" -> vars();
            case "/funcs" -> funcs();
            case "/types" -> typesCommand();
            case "/macros" -> macros();
            case "/tac" -> tac(arg);
            case "/asm" -> asmCommand(arg);
            case "/drop" -> drop(arg);
            case "/load" -> loadFile(arg);
            case "/save" -> saveFile(arg);
            case "/reset" -> {
                reset();
                say("|  reset");
            }
            default -> say("|  unknown command: " + name);
        }
        return true;
    }

    private void help() {
        say("|  /list [name]   the kept lines, numbered; or the line declaring a name");
        say("|  /vars          the objects, with their values");
        say("|  /funcs         the functions, with their types");
        say("|  /types         the typedefs, structs, unions and enums");
        say("|  /macros        the #defines");
        say("|  /tac name      the TAC of a function or an object");
        say("|  /asm name      the x86-64 assembly of a function, with its TAC in comments");
        say("|  /drop name     forget the line that declares a name");
        say("|  /load file     run a file line by line, as if typed");
        say("|  /save file     write the kept lines to a file");
        say("|  /reset         forget everything");
        say("|  /exit          leave");
    }

    private void list(String name) {
        for (int i = 0; i < kept.size(); i++) {
            Kept k = kept.get(i);
            if (!name.isEmpty() && !k.names().contains(name)) {
                continue;
            }
            String[] lines = k.text().split("\n");
            say("|  " + (i + 1) + " : " + lines[0]);
            for (int j = 1; j < lines.length; j++) {
                say("|      " + lines[j]);
            }
        }
    }

    private void vars() {
        for (TUnit.Global g : last.typed().globals()) {
            if (g.isDefinition() && g.symbol().declaredAt.file.equals(FILE)) {
                String n = g.symbol().name;
                say("|  " + g.symbol().type().spelling() + " " + n + " = " + printer.print(n, g.symbol().type()));
            }
        }
    }

    private void funcs() {
        for (TFunction f : last.typed().functions()) {
            if (!f.symbol().name.equals(Parser.FILE_FUNCTION) && f.symbol().declaredAt.file.equals(FILE)) {
                say("|  " + f.symbol().name + " : " + f.symbol().type().spelling());
            }
        }
    }

    private void typesCommand() {
        for (Decl d : last.ast()) {
            if (d instanceof Decl.Declaration decl && decl.specifiers().token().file.equals(FILE)) {
                tag(decl.specifiers().type()).ifPresent(t -> say("|  " + t));
                if (isTypedef(decl)) {
                    for (Decl.InitDeclarator id : decl.declarators()) {
                        say("|  typedef " + id.name().text);
                    }
                }
            }
        }
    }

    private void macros() {
        for (Kept k : kept) {
            if (DEFINE.matcher(k.text()).find()) {
                say("|  " + k.text());
            }
        }
    }

    private void tac(String name) {
        if (name.isEmpty()) {
            say("|  usage: /tac name");
            return;
        }
        for (Function f : last.tac().functions) {
            if (f.name.equals(name)) {
                for (String line : TacWriter.print(f).split("\n")) {
                    say("|  " + line);
                }
                return;
            }
        }
        for (Global g : last.tac().globals) {
            if (g.name().equals(name)) {
                for (String line : TacWriter.print(last.tac()).split("\n")) {
                    if (line.startsWith("global ") && line.contains("@" + name + " ")) {
                        say("|  " + line);
                    }
                }
                return;
            }
        }
        say("|  no such name: " + name);
    }

    // A file's lines, each as if typed; continuation comes from the file.
    private void loadFile(String path) {
        if (path.isEmpty()) {
            say("|  usage: /load file");
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path));
        } catch (IOException e) {
            say("|  cannot read " + path + ": " + e.getMessage());
            return;
        }
        Deque<String> rest = new ArrayDeque<>(lines);
        while (!rest.isEmpty()) {
            String line = rest.poll();
            if (!handle(line, () -> Optional.ofNullable(rest.poll()))) {
                return;
            }
        }
    }

    private void saveFile(String path) {
        if (path.isEmpty()) {
            say("|  usage: /save file");
            return;
        }
        try {
            Files.writeString(Path.of(path), prefix());
            say("|  saved " + kept.size() + " lines to " + path);
        } catch (IOException e) {
            say("|  cannot write " + path + ": " + e.getMessage());
        }
    }

    private void asmCommand(String name) {
        if (name.isEmpty()) {
            say("|  usage: /asm name");
            return;
        }
        for (Function f : last.tac().functions) {
            if (f.name.equals(name)) {
                for (String line : Codegen.emit(last.tac(), f, true).split("\n")) {
                    say("|  " + line);
                }
                return;
            }
        }
        say("|  no such function: " + name);
    }

    // The kept line declaring the name goes; if the rest no longer
    // compiles, it comes back and the error says what needed it.
    private void drop(String name) {
        if (name.isEmpty()) {
            say("|  usage: /drop name");
            return;
        }
        Optional<Kept> line = kept.stream().filter(k -> k.names().contains(name)).findFirst();
        if (line.isEmpty()) {
            say("|  no kept line declares " + name);
            return;
        }
        int index = kept.indexOf(line.get());
        int removedAt = kept.subList(0, index).stream().mapToInt(k -> lines(k.text())).sum() + 1;
        int removedLines = lines(line.get().text());
        kept.remove(index);
        try {
            last = Compiler.compileScript(prefix(), headers, FILE, types);
        } catch (RuntimeException e) {
            kept.add(index, line.get());
            // the error's lines count without the removed line; put it back
            String message = shiftLines(e.getMessage(), removedAt, removedLines);
            say("|  cannot drop " + name + ": " + relocate(message));
            return;
        }
        for (String n : line.get().names()) {
            vm.drop(n);
        }
        bindDeclared(last);
        vm.step(last.tac());
        say("|  dropped " + String.join(", ", line.get().names()));
    }
}

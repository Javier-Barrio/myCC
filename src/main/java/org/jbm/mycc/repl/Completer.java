package org.jbm.mycc.repl;

import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.parse.ast.Type;
import org.jbm.mycc.cc.sema.tast.TFunction;
import org.jbm.mycc.cc.sema.tast.TUnit;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tab completion from what the shell knows: the commands, the names
 * the VM holds with their types, the macros, typedefs and tags of the
 * kept lines, the bundled headers after {@code #include <}, the
 * members of a struct after {@code .} or {@code ->}, and C's keywords.
 */
public final class Completer {

    /** A completion: the text that replaces the word, and what it is, shown beside it. */
    public record Candidate(String text, String description) {
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Pattern MEMBER = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)(->|\\.)([A-Za-z_$][A-Za-z0-9_$]*)?$");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*<([A-Za-z0-9_./]*)$");
    private static final Pattern NAME_COMMAND = Pattern.compile("^\\s*(/tac|/asm|/drop|/list)\\s+([A-Za-z_$][A-Za-z0-9_$]*)?$");

    private final Repl repl;

    public Completer(Repl repl) {
        this.repl = repl;
    }

    /** The candidates for the text before the cursor. */
    public List<Candidate> complete(String line, int cursor) {
        String before = line.substring(0, Math.min(cursor, line.length()));
        Matcher include = INCLUDE.matcher(before);
        if (include.find()) {
            return startingWith(include.group(1), headers());
        }
        Matcher named = NAME_COMMAND.matcher(before);
        if (named.find()) {
            String prefix = named.group(2) == null ? "" : named.group(2);
            return startingWith(prefix, names());
        }
        if (before.stripLeading().startsWith("/") && !before.contains(" ")) {
            return startingWith(before.stripLeading(), commands());
        }
        Matcher member = MEMBER.matcher(before);
        if (member.find()) {
            String prefix = member.group(3) == null ? "" : member.group(3);
            String owner = member.group(1) + member.group(2);
            List<Candidate> out = new ArrayList<>();
            for (Candidate c : startingWith(prefix, members(member.group(1), member.group(2)))) {
                out.add(new Candidate(owner + c.text(), c.description()));
            }
            return out;
        }
        Matcher word = WORD.matcher(before);
        if (word.find()) {
            List<Candidate> all = new ArrayList<>(names());
            all.addAll(keywords());
            return startingWith(word.group(), all);
        }
        return List.of();
    }

    private static List<Candidate> startingWith(String prefix, List<Candidate> all) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : all) {
            if (c.text().startsWith(prefix)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Candidate> commands() {
        List<Candidate> out = new ArrayList<>();
        for (String c : Repl.commands()) {
            out.add(new Candidate(c, "command"));
        }
        return out;
    }

    private static List<Candidate> headers() {
        List<Candidate> out = new ArrayList<>();
        for (String h : BundledHeaders.NAMES) {
            out.add(new Candidate(h + ">", "header"));
        }
        return out;
    }

    private static List<Candidate> keywords() {
        List<Candidate> out = new ArrayList<>();
        for (String k : CppTokenizer.keywords().stream().sorted().toList()) {
            out.add(new Candidate(k, "keyword"));
        }
        return out;
    }

    // Functions and objects with their types, macros, typedefs and tags,
    // in the order they were declared.
    private List<Candidate> names() {
        Map<String, String> found = new LinkedHashMap<>();
        TUnit typed = repl.last().typed();
        for (TFunction f : typed.functions()) {
            if (!f.symbol().name.equals(Parser.FILE_FUNCTION)) {
                found.put(f.symbol().name, f.symbol().type().spelling());
            }
        }
        for (TUnit.Global g : typed.globals()) {
            found.put(g.symbol().name, g.symbol().type().spelling());
        }
        for (Decl d : repl.last().ast()) {
            if (d instanceof Decl.Declaration decl) {
                boolean typedef = decl.specifiers().storageClasses().stream().anyMatch(t -> t.text.equals("typedef"));
                for (Decl.InitDeclarator id : decl.declarators()) {
                    if (typedef) {
                        found.put(id.name().text, "typedef");
                    } else if (id.type().isPresent() && id.type().get() instanceof Type.Function) {
                        // a prototype, from a header or typed in: the TAC declares
                        // only what is used, so it is found here
                        found.putIfAbsent(id.name().text, "function");
                    }
                }
                Optional<Type> type = decl.specifiers().type();
                if (type.isPresent() && type.get() instanceof Type.Struct s && s.tag().isPresent()) {
                    found.putIfAbsent(s.tag().get().text, s.keyword().text);
                }
                if (type.isPresent() && type.get() instanceof Type.Enum e && e.tag().isPresent()) {
                    found.putIfAbsent(e.tag().get().text, "enum");
                }
            }
        }
        for (Repl.Kept k : repl.keptLines()) {
            Matcher m = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(k.text());
            if (m.find()) {
                found.put(m.group(1), "macro");
            }
        }
        for (String name : repl.vm().symbols().keySet()) {
            if (!name.equals(Parser.FILE_FUNCTION)) {
                found.putIfAbsent(name, "declared");
            }
        }
        List<Candidate> out = new ArrayList<>();
        found.forEach((n, d) -> out.add(new Candidate(n, d)));
        return out;
    }

    // The members of the struct a global names, through `.` or, for a pointer, `->`.
    private List<Candidate> members(String owner, String operator) {
        Optional<CType> type = repl.last().typed().globals().stream()
                .filter(g -> g.symbol().name.equals(owner)).map(g -> g.symbol().type()).findFirst();
        if (type.isEmpty()) {
            return List.of();
        }
        CType t = type.get();
        if (operator.equals("->") && t instanceof CType.Pointer p) {
            t = p.target();
        } else if (operator.equals("->")) {
            return List.of();
        }
        if (!(t instanceof CType.Record r) || r.tag().layout().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (Layout.Member m : r.tag().layout().get().slots()) {
            if (!m.isAnonymous()) {
                out.add(new Candidate(m.name(), m.type().spelling()));
            }
        }
        return out;
    }
}

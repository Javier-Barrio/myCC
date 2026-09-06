package org.jbm.cc.sema;

import org.jbm.cc.ast.AstWalker;
import org.jbm.cc.ast.BlockItem;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.ast.Specifiers;
import org.jbm.cc.ast.Stmt;
import org.jbm.cc.ast.Type;
import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Name resolution (C2y 6.2.1 - 6.2.3): the first sema pass. Walks the AST
 * once, builds the block-structured symbol table with the ordinary and tag
 * namespaces, and records in {@link Bindings} which entity every
 * identifier use, declarator, parameter, enumerator, tag specifier, goto
 * and labeled break/continue refers to. Scopes follow the standard rather
 * than the parser: compound statements, selection and iteration
 * statements, function prototypes and function bodies.
 * <p>
 * No typing happens here. Struct member names, the types of expressions,
 * implicit conversions and constant evaluation belong to the typing pass,
 * which consumes these bindings.
 */
public final class Resolver extends AstWalker {

    private final SymbolTable table = new SymbolTable();
    private final Bindings bindings = new Bindings();
    private int nextId = 1;

    // Per-function state: the label namespace (6.2.1p3), gotos to resolve
    // once the whole body is seen, and the enclosing loops/switches that a
    // break or continue may target, innermost first. Reset per function;
    // labels and jumps cannot occur outside one.
    private Map<String, Stmt.Labeled> labels = new HashMap<>();
    private List<Stmt.Goto> gotos = new ArrayList<>();
    private final Deque<JumpTarget> jumpTargets = new ArrayDeque<>();

    private record JumpTarget(Stmt stmt, Set<String> labels, boolean isLoop) {
    }

    // Label names attached to the statement about to be walked, so a loop
    // or switch knows which `break L` / `continue L` name it (6.8.7).
    private Set<String> pendingTargetLabels = Set.of();

    // Set while walking a declaration with no declarators, where
    // `struct S;` declares a new tag in the current scope even if an outer
    // S exists (6.7.3.4p2).
    private boolean standaloneTagDeclaration;

    private Resolver() {
    }

    public static Bindings resolve(List<? extends Decl> translationUnit) {
        var resolver = new Resolver();
        resolver.walkUnit(translationUnit);
        return resolver.bindings;
    }

    // ---- declarations -----------------------------------------------------

    @Override
    public Void visit(Decl.Declaration d) {
        standaloneTagDeclaration = d.declarators().isEmpty();
        walkSpecifiers(d.specifiers());
        standaloneTagDeclaration = false;
        for (var id : d.declarators()) {
            // The declared type may contain array sizes and prototypes to
            // resolve; its struct/enum specifier is shared with the
            // declaration specifiers and is skipped on this second visit.
            id.type().ifPresent(this::walk);
            // The identifier's scope starts right after its declarator
            // (6.2.1p7), so it is declared before its initializer is walked.
            Symbol symbol = declare(id.name(), id.type(), d.specifiers(), id.initializer().isPresent());
            bindings.declarators.put(id, symbol);
            id.initializer().ifPresent(this::walk);
        }
        return null;
    }

    @Override
    public Void visit(Decl.FunctionDefinition d) {
        walkSpecifiers(d.specifiers());
        Symbol symbol = declare(d.name(), Optional.of(d.type()), d.specifiers(), true);
        bindings.functions.put(d, symbol);

        labels = new HashMap<>();
        gotos = new ArrayList<>();
        jumpTargets.clear();

        // Parameters have block scope in the body (6.2.1p4) - the body's
        // own scope, not a nested one - so the compound statement is
        // walked without pushing another.
        table.push();
        for (var p : d.type().parameters()) {
            declareParameter(p);
            walk(p.type());
        }
        walk(d.type().returnType());
        walkBlockItems(d.body().items());
        table.pop();

        for (var g : gotos) {
            Stmt.Labeled target = labels.get(g.label().text);
            if (target == null) throw new SemaException("label '" + g.label().text + "' used but not defined", g.label());
            bindings.gotos.put(g, target);
        }
        return null;
    }

    @Override
    protected void walkParameter(Type.Parameter p) {
        declareParameter(p);
        walk(p.type());
    }

    private void declareParameter(Type.Parameter p) {
        p.name().ifPresent(name -> {
            table.lookupHere(name.text).ifPresent(existing -> {
                throw redeclaration(name, existing);
            });
            Symbol symbol = new Symbol.Parameter(nextId++, name, p.type(), table.depth());
            table.declare(symbol);
            bindings.parameters.put(p, symbol);
        });
    }

    /**
     * Declares an ordinary identifier in the current scope - a typedef
     * name, a function or an object depending on the specifiers and the
     * declared type - applying the storage-duration and linkage rules of
     * 6.2.2 / 6.2.4 and the redeclaration rules that matter for
     * resolution: at file scope a name may be declared repeatedly
     * (tentative definitions, prototypes) and all declarations denote one
     * symbol; in a block only extern redeclarations are allowed.
     */
    private Symbol declare(Token name, Optional<Type> type, Specifiers specs, boolean defined) {
        int depth = table.depth();
        Class<? extends Symbol> kind = specs.isTypedef() ? Symbol.Typedef.class
                : type.filter(t -> t instanceof Type.Function).isPresent() ? Symbol.Function.class
                : Symbol.Variable.class;
        var found = table.lookupHere(name.text);
        if (found.isPresent()) {
            Symbol existing = found.get();
            boolean sameKind = existing.getClass() == kind;
            boolean fileScopeRedeclaration = depth == 0 && sameKind;
            boolean externRedeclaration = depth > 0 && sameKind && specs.has("extern")
                    && existing.linkage() == Symbol.Linkage.EXTERNAL;
            if (!fileScopeRedeclaration && !externRedeclaration) throw redeclaration(name, existing);
            if (defined) {
                if (existing.isDefined()) {
                    throw new SemaException("redefinition of '" + name.text + "' (previous definition at "
                            + existing.declaredAt.line + ":" + existing.declaredAt.column + ")", name);
                }
                existing.markDefined();
            }
            return existing;
        }

        boolean isStatic = specs.has("static");
        Symbol symbol;
        if (kind == Symbol.Typedef.class) {
            symbol = new Symbol.Typedef(nextId++, name, type.orElseThrow(), depth);
        } else if (kind == Symbol.Function.class) {
            symbol = new Symbol.Function(nextId++, name, type.orElseThrow(), depth,
                    isStatic ? Symbol.Linkage.INTERNAL : Symbol.Linkage.EXTERNAL, defined);
        } else {
            boolean isExtern = specs.has("extern");
            var storage = depth == 0 || isStatic || isExtern || specs.has("thread_local")
                    ? Symbol.Variable.Storage.STATIC : Symbol.Variable.Storage.AUTOMATIC;
            var linkage = depth == 0
                    ? (isStatic ? Symbol.Linkage.INTERNAL : Symbol.Linkage.EXTERNAL)
                    : (isExtern ? Symbol.Linkage.EXTERNAL : Symbol.Linkage.NONE);
            symbol = new Symbol.Variable(nextId++, name, type, depth, storage, linkage, defined);
        }
        table.declare(symbol);
        if (depth == 0) bindings.fileScope.add(symbol);
        return symbol;
    }

    private static SemaException redeclaration(Token name, Symbol existing) {
        return new SemaException("redeclaration of '" + name.text + "' (previously declared at "
                + existing.declaredAt.line + ":" + existing.declaredAt.column + ")", name);
    }

    // ---- types: tags, enumerators, prototypes ----------------------------------

    @Override
    public Void visit(Type.Struct t) {
        // A specifier node is shared between the declaration specifiers and
        // every declarator's type (`struct S {...} a, *b;`), so it is
        // resolved once.
        if (bindings.tags.containsKey(t)) return null;
        resolveTag(t, t.keyword(), t.tag(), t.keyword().text, t.members().isPresent());
        t.members().ifPresent(members -> {
            for (var m : members) walkMember(m);
        });
        return null;
    }

    @Override
    public Void visit(Type.Enum t) {
        if (bindings.tags.containsKey(t)) return null;
        resolveTag(t, t.keyword(), t.tag(), "enum", t.enumerators().isPresent());
        t.underlying().ifPresent(this::walk);
        t.enumerators().ifPresent(enumerators -> {
            for (var e : enumerators) {
                // An enumerator's scope begins just after its definition
                // (6.2.1p7), so `B = A + 1` sees A but not B.
                e.value().ifPresent(this::walk);
                table.lookupHere(e.name().text).ifPresent(existing -> {
                    throw redeclaration(e.name(), existing);
                });
                Symbol symbol = new Symbol.Enumerator(nextId++, e.name(), t, table.depth());
                table.declare(symbol);
                if (table.depth() == 0) bindings.fileScope.add(symbol);
                bindings.enumerators.put(e, symbol);
            }
        });
        return null;
    }

    // 6.7.3.4: a definition or a standalone `struct S;` declares the tag in
    // the current scope (completing a forward declaration made there); any
    // other mention refers to a visible tag, or declares an incomplete one
    // in the current scope if none is visible.
    private void resolveTag(Type node, Token keywordToken, Optional<Token> tag, String keyword, boolean isDefinition) {
        if (tag.isEmpty()) {
            var anonymous = new TagSymbol(Optional.empty(), keyword, keywordToken, table.depth());
            if (isDefinition) anonymous.define(node);
            bindings.tags.put(node, anonymous);
            return;
        }
        Token name = tag.get();
        Optional<TagSymbol> visible = isDefinition || standaloneTagDeclaration
                ? table.lookupTagHere(name.text)
                : table.lookupTag(name.text);
        TagSymbol symbol = visible.orElseGet(() -> {
            var declared = new TagSymbol(Optional.of(name.text), keyword, name, table.depth());
            table.declareTag(declared);
            return declared;
        });
        if (isDefinition && visible.isPresent() && symbol.isComplete()) {
            throw new SemaException("redefinition of '" + keyword + " " + name.text + "'", name);
        }
        if (!symbol.keyword.equals(keyword)) {
            throw new SemaException("'" + name.text + "' declared as " + symbol.keyword + " but used as " + keyword, name);
        }
        if (isDefinition) symbol.define(node);
        bindings.tags.put(node, symbol);
    }

    @Override
    public Void visit(Type.Function t) {
        // Function prototype scope (6.2.1p4): parameter names are visible
        // to later parameters (`int n, int a[n]`) and end at the ')'.
        walk(t.returnType());
        table.push();
        for (var p : t.parameters()) walkParameter(p);
        table.pop();
        return null;
    }

    // ---- expressions ---------------------------------------------------------------

    @Override
    public Void visit(Expr.Identifier e) {
        Symbol symbol = table.lookup(e.name().text).orElseThrow(
                () -> new SemaException("use of undeclared identifier '" + e.name().text + "'", e.name()));
        if (symbol instanceof Symbol.Typedef) {
            throw new SemaException("'" + e.name().text + "' names a type, not a value", e.name());
        }
        bindings.identifiers.put(e, symbol);
        return null;
    }

    // ---- statements ------------------------------------------------------------------

    @Override
    public Void visit(Stmt.Compound s) {
        table.push();
        walkBlockItems(s.items());
        table.pop();
        return null;
    }

    // Block items in order. A bare label (a block-item of its own, 6.8.3)
    // names the statement that follows it, which matters when that
    // statement is a loop or switch targeted by `break L`.
    private void walkBlockItems(List<BlockItem> items) {
        var names = new LinkedHashSet<String>();
        for (var item : items) {
            if (item instanceof Stmt.Labeled labeled && labeled.body().isEmpty()) {
                if (labeled.label() instanceof Stmt.NameLabel l) {
                    defineLabel(l.name(), labeled);
                    names.add(l.name().text);
                } else {
                    checkCaseLabel(labeled.label());
                    walkLabel(labeled.label());
                    names.clear();
                }
                continue;
            }
            if (item instanceof Stmt stmt) {
                walkTarget(stmt, names);
            } else {
                walk(item);
            }
            names = new LinkedHashSet<>();
        }
    }

    @Override
    public Void visit(Stmt.Labeled s) {
        // `a: b: while (...)` - collect every name down to the real
        // statement. Only bare labels (block-items) lack a body, and
        // walkBlockItems handles those before they get here.
        var names = new LinkedHashSet<String>();
        Stmt body = s;
        while (body instanceof Stmt.Labeled labeled) {
            if (labeled.label() instanceof Stmt.NameLabel l) {
                defineLabel(l.name(), labeled);
                names.add(l.name().text);
            } else {
                checkCaseLabel(labeled.label());
                walkLabel(labeled.label());
            }
            body = labeled.body().orElseThrow();
        }
        walkTarget(body, names);
        return null;
    }

    private void defineLabel(Token name, Stmt.Labeled labeled) {
        if (labels.putIfAbsent(name.text, labeled) != null) {
            throw new SemaException("duplicate label '" + name.text + "'", name);
        }
    }

    private void checkCaseLabel(Stmt.Label label) {
        Token at = label instanceof Stmt.CaseLabel c ? c.keyword() : ((Stmt.DefaultLabel) label).keyword();
        if (jumpTargets.stream().noneMatch(t -> t.stmt() instanceof Stmt.Switch)) {
            throw new SemaException("'" + at.text + "' label not within a switch statement", at);
        }
    }

    // Walks a statement, handing loops and switches the label names that
    // precede them so `break L` / `continue L` can find them.
    private void walkTarget(Stmt stmt, Set<String> names) {
        boolean isTarget = stmt instanceof Stmt.While || stmt instanceof Stmt.DoWhile
                || stmt instanceof Stmt.For || stmt instanceof Stmt.Switch;
        pendingTargetLabels = isTarget ? names : Set.of();
        walk(stmt);
        pendingTargetLabels = Set.of();
    }

    private void enterTarget(Stmt stmt, boolean isLoop) {
        jumpTargets.push(new JumpTarget(stmt, pendingTargetLabels, isLoop));
        pendingTargetLabels = Set.of();
    }

    @Override
    public Void visit(Stmt.If s) {
        table.push();
        walkHeader(s.header());
        walk(s.thenBranch());
        s.elseBranch().ifPresent(this::walk);
        table.pop();
        return null;
    }

    @Override
    public Void visit(Stmt.Switch s) {
        table.push();
        walkHeader(s.header());
        enterTarget(s, false);
        walk(s.body());
        jumpTargets.pop();
        table.pop();
        return null;
    }

    @Override
    public Void visit(Stmt.While s) {
        enterTarget(s, true);
        walk(s.condition());
        walk(s.body());
        jumpTargets.pop();
        return null;
    }

    @Override
    public Void visit(Stmt.DoWhile s) {
        enterTarget(s, true);
        walk(s.body());
        walk(s.condition());
        jumpTargets.pop();
        return null;
    }

    @Override
    public Void visit(Stmt.For s) {
        table.push();
        s.initDecl().ifPresent(this::walk);
        s.initExpr().ifPresent(this::walk);
        s.condition().ifPresent(this::walk);
        s.step().ifPresent(this::walk);
        enterTarget(s, true);
        walk(s.body());
        jumpTargets.pop();
        table.pop();
        return null;
    }

    @Override
    public Void visit(Stmt.Goto s) {
        gotos.add(s);
        return null;
    }

    @Override
    public Void visit(Stmt.Break s) {
        bindings.jumps.put(s, findJumpTarget(s.keyword(), s.label(), false));
        return null;
    }

    @Override
    public Void visit(Stmt.Continue s) {
        bindings.jumps.put(s, findJumpTarget(s.keyword(), s.label(), true));
        return null;
    }

    // break: innermost loop or switch, or the one labeled `label`;
    // continue: innermost loop, or the loop labeled `label` (6.8.7.2-3).
    private Stmt findJumpTarget(Token keyword, Optional<Token> label, boolean loopOnly) {
        for (var target : jumpTargets) {
            if (loopOnly && !target.isLoop()) continue;
            if (label.isEmpty() || target.labels().contains(label.get().text)) return target.stmt();
        }
        String what = loopOnly ? "loop" : "loop or switch";
        throw label.map(l -> new SemaException("'" + keyword.text + " " + l.text + "': no enclosing "
                        + what + " labeled '" + l.text + "'", l))
                .orElseGet(() -> new SemaException("'" + keyword.text + "' not within a " + what, keyword));
    }
}

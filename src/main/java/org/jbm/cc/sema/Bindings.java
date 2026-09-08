package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.ast.Stmt;
import org.jbm.cc.ast.Type;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the {@link Resolver} found out, as side tables keyed by AST node.
 * <p>
 * The maps are identity-keyed on purpose: AST records have structural
 * equality, and two distinct uses can be structurally equal - every
 * expansion of {@code #define N n} yields an identifier whose token
 * carries the position of the {@code #define} line, so {@code N + N} is
 * two equal {@code Identifier} records. The {@code *Of} accessors throw
 * on a missing entry, because "this node was never resolved" is a bug in
 * the pass, not a lookup miss.
 */
public final class Bindings {

    /** Every identifier use, bound to its object, function, parameter or enumerator. */
    public final Map<Expr.Identifier, Symbol> identifiers = new IdentityHashMap<>();

    /** Every declarator, bound to the symbol it declares (redeclarations share one). */
    public final Map<Decl.InitDeclarator, Symbol> declarators = new IdentityHashMap<>();

    public final Map<Decl.FunctionDefinition, Symbol> functions = new IdentityHashMap<>();

    public final Map<Type.Parameter, Symbol> parameters = new IdentityHashMap<>();

    public final Map<Type.Enumerator, Symbol> enumerators = new IdentityHashMap<>();

    /** Every typedef-name use in a type, bound to the typedef it names (a shared node is bound once). */
    public final Map<Type.TypedefName, Symbol.Typedef> typedefs = new IdentityHashMap<>();

    /** Every struct/union/enum specifier node, bound to its tag (anonymous ones get their own). */
    public final Map<Type, TagSymbol> tags = new IdentityHashMap<>();

    /** goto → the labeled statement it jumps to. */
    public final Map<Stmt.Goto, Stmt.Labeled> gotos = new IdentityHashMap<>();

    /** break / continue → the loop or switch statement it leaves or restarts. */
    public final Map<Stmt, Stmt> jumps = new IdentityHashMap<>();

    /** File-scope symbols in declaration order. Read by tests; the typed unit lists the globals lowering needs. */
    public final List<Symbol> fileScope = new ArrayList<>();

    /** Symbol ids used so far; later passes that create symbols continue from here. */
    public int symbolCount;

    public Symbol symbolOf(@NonNull Expr.Identifier id) {
        return require(identifiers.get(id), id.name().text);
    }

    public Symbol symbolOf(@NonNull Decl.InitDeclarator d) {
        return require(declarators.get(d), d.name().text);
    }

    public Symbol symbolOf(@NonNull Decl.FunctionDefinition f) {
        return require(functions.get(f), f.name().text);
    }

    public Symbol symbolOf(@NonNull Type.Parameter p) {
        return require(parameters.get(p), p.name().map(n -> n.text).orElse("<unnamed parameter>"));
    }

    public Symbol.Typedef typedefOf(@NonNull Type.TypedefName t) {
        return require(typedefs.get(t), t.name().text);
    }

    public TagSymbol tagOf(@NonNull Type t) {
        return require(tags.get(t), t.toString());
    }

    public Stmt.Labeled targetOf(@NonNull Stmt.Goto g) {
        return require(gotos.get(g), "goto " + g.label().text);
    }

    public Stmt targetOf(@NonNull Stmt jump) {
        return require(jumps.get(jump), jump.toString());
    }

    private static <T> T require(T value, String what) {
        if (value == null) throw new IllegalStateException("unresolved: " + what);
        return value;
    }
}

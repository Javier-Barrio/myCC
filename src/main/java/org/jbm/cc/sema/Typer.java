package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.ast.AstWalker;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.ast.Stmt;
import org.jbm.cc.ast.Type;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;
import org.jbm.cc.types.X86_64SysV;

import java.util.ArrayList;
import java.util.List;

/**
 * The typing pass: runs after {@link Resolver} over the same tree and
 * gives every symbol its semantic type. Declarations are typed in order,
 * composing redeclarations (6.2.7p3) and rejecting conflicting ones.
 * <p>
 * Expressions, statements and the typed tree are not built yet; this is
 * the declaration half of the pass (typer plan, step 5).
 */
public final class Typer extends AstWalker {

    private final Types types;
    private final Bindings bindings;
    private final TypeBuilder builder;
    private final ExprTyper exprs;

    // Expression statements typed so far, in order. Temporary: until the
    // statement tree exists this is how tests reach a typed expression.
    final List<TExpr> expressionStatements = new ArrayList<>();

    private Typer(Types types, Bindings bindings) {
        this.types = types;
        this.bindings = bindings;
        this.builder = new TypeBuilder(types, bindings);
        this.exprs = new ExprTyper(types, bindings);
    }

    public static void type(@NonNull List<? extends Decl> unit, @NonNull Bindings bindings) {
        type(unit, bindings, new Types(X86_64SysV.INSTANCE));
    }

    public static void type(@NonNull List<? extends Decl> unit, @NonNull Bindings bindings, @NonNull Types types) {
        run(unit, bindings, types);
    }

    static Typer run(List<? extends Decl> unit, Bindings bindings, Types types) {
        var typer = new Typer(types, bindings);
        typer.walkUnit(unit);
        return typer;
    }

    // ---- statements (expression statements only, for now) ----------------------------

    @Override
    public Void visit(Stmt.ExprStmt s) {
        s.expr().ifPresent(e -> expressionStatements.add(exprs.type(e)));
        return null;
    }

    // ---- declarations -------------------------------------------------------------

    @Override
    public Void visit(Decl.Declaration d) {
        walkSpecifiers(d.specifiers());
        for (var id : d.declarators()) {
            Type syntactic = id.type().orElseThrow(
                    () -> new SemaException("auto type inference is not supported yet", id.name()));
            declare(bindings.symbolOf(id), builder.build(syntactic), id.name());
            walk(syntactic);
            id.initializer().ifPresent(this::walk);
        }
        return null;
    }

    @Override
    public Void visit(Decl.FunctionDefinition d) {
        walkSpecifiers(d.specifiers());
        declare(bindings.symbolOf(d), builder.build(d.type()), d.name());
        for (var p : d.type().parameters()) walkParameter(p);
        walk(d.type().returnType());
        walk(d.body());
        return null;
    }

    // Named parameters, in a definition or a prototype, get their adjusted
    // type; the type is then walked for nested prototypes.
    @Override
    protected void walkParameter(Type.Parameter p) {
        if (p.name().isPresent()) bindings.symbolOf(p).setType(builder.parameter(p));
        walk(p.type());
    }

    /**
     * Sets or composes a symbol's type. A redeclaration must be compatible
     * with what was declared before (6.7p4), and the symbol then carries
     * the composite of the two (6.2.7p3): the known array size, the
     * prototype's parameter types.
     */
    private void declare(Symbol symbol, CType type, Token at) {
        if (!symbol.hasType()) {
            symbol.setType(type);
            return;
        }
        if (!types.compatible(symbol.type(), type)) {
            throw new SemaException("conflicting types for '" + symbol.name + "': '" + type.spelling()
                    + "' after '" + symbol.type().spelling() + "'", at);
        }
        symbol.setType(types.composite(symbol.type(), type));
    }
}

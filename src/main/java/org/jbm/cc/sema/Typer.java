package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.arch.X86_64SysV;
import org.jbm.cc.ast.BlockItem;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.ast.Stmt;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.tast.JumpTarget;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TExpr.Rvalue;
import org.jbm.cc.tast.TFunction;
import org.jbm.cc.tast.TInit;
import org.jbm.cc.tast.TStmt;
import org.jbm.cc.tast.TUnit;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The typing pass: runs after {@link Resolver} over the same tree and
 * produces the typed unit that lowering consumes. Declarations are typed
 * in order, composing redeclarations (6.2.7p3); expressions go through
 * {@link ExprTyper}; statements become {@link TStmt}s with {@code bool}
 * conditions, converted return values and {@link JumpTarget} references;
 * functions collect their automatic objects. This is the only consumer
 * of {@link Bindings}.
 */
public final class Typer {

    private final Types types;
    private final Bindings bindings;
    private final TypeBuilder builder;
    private final ConstEval constEval;
    private final ExprTyper exprs;
    private final Literals literals;
    private final Initializers initializers;

    // Static-storage objects in declaration order; a definition's
    // initializer replaces the absent one of an earlier declaration. A
    // symbol declared at least once without `extern` is a tentative
    // definition (6.9.2p2) and gets storage in this unit.
    private final Map<Symbol, Optional<TInit>> globals = new LinkedHashMap<>();
    private final Set<Symbol> tentative = new HashSet<>();
    private final List<TFunction> functions = new ArrayList<>();

    // Expression statements typed so far, unconverted, in order: the test
    // harness's way to reach a typed expression.
    final List<TExpr> expressionStatements = new ArrayList<>();

    private @Nullable FunctionState function;

    // What a function body needs while it is typed: its return type, the
    // automatic objects seen so far, the jump targets keyed by the AST
    // statement they stand for (loops, switches, labels), and the
    // enclosing switches for case labels.
    private static final class FunctionState {
        final CType returnType;
        final List<Symbol> locals = new ArrayList<>();
        final Map<Stmt, JumpTarget> targets = new IdentityHashMap<>();
        final Deque<SwitchState> switches = new ArrayDeque<>();

        FunctionState(CType returnType) {
            this.returnType = returnType;
        }
    }

    private static final class SwitchState {
        final CType type;
        final List<TStmt.CaseLabel> cases = new ArrayList<>();
        final Set<Long> values = new HashSet<>();
        final List<TStmt.CaseRange> ranges = new ArrayList<>();
        @Nullable JumpTarget defaultTarget;

        SwitchState(CType type) {
            this.type = type;
        }
    }

    private Typer(Types types, Bindings bindings) {
        this.types = types;
        this.bindings = bindings;
        this.builder = new TypeBuilder(types, bindings);
        this.constEval = new ConstEval(types);
        this.exprs = new ExprTyper(types, bindings, builder, constEval);
        this.literals = new Literals(types);
        this.initializers = new Initializers(types, exprs, constEval);
        exprs.setInitializers(initializers, (symbol, init) -> globals.put(symbol, Optional.of(init)));
        builder.setEvaluator(new TypeBuilder.Hooks() {
            @Override
            public TExpr.IntConst evaluate(Expr e, Token at, String what) {
                Rvalue v = exprs.rvalue(exprs.type(e));
                TExpr.Constant c = constEval.require(v, at, what);
                if (!(c instanceof TExpr.IntConst i)) {
                    throw new SemaException(what + " must be an integer constant expression", at);
                }
                return i;
            }

            @Override
            public Optional<TExpr.IntConst> tryEvaluate(Expr e) {
                Rvalue v = exprs.rvalue(exprs.type(e));
                if (!v.type().isInteger()) throw new SemaException("size is not an integer", v.token());
                return constEval.fold(v).map(c -> (TExpr.IntConst) c);
            }

            @Override
            public CType typeOf(Expr e) {
                return exprs.typeUnevaluated(e).type();
            }

            @Override
            public void staticAssertion(Expr.StaticAssertion s) {
                Typer.this.staticAssertion(s);
            }
        });
    }

    public static TUnit type(@NonNull List<? extends Decl> unit, @NonNull Bindings bindings) {
        return type(unit, bindings, new Types(X86_64SysV.INSTANCE));
    }

    public static TUnit type(@NonNull List<? extends Decl> unit, @NonNull Bindings bindings, @NonNull Types types) {
        return run(unit, bindings, types).unit();
    }

    static Typer run(List<? extends Decl> unit, Bindings bindings, Types types) {
        var typer = new Typer(types, bindings);
        for (Decl d : unit) typer.externalDeclaration(d);
        return typer;
    }

    List<org.jbm.cc.tast.StringData> strings() {
        return exprs.strings;
    }

    TUnit unit() {
        var g = new ArrayList<TUnit.Global>(globals.size());
        globals.forEach((symbol, init) -> {
            boolean isDefinition = init.isPresent() || tentative.contains(symbol);
            if (isDefinition && !symbol.type().isComplete()) {
                // A tentative definition of an incomplete array is an array of
                // one element at the end of the unit (6.9.2p5); any other
                // incomplete type has no storage size.
                if (symbol.type() instanceof CType.Array a) {
                    symbol.setType(types.array(a.element(), 1));
                } else {
                    throw new SemaException("storage size of '" + symbol.name + "' is not known ('"
                            + symbol.type().spelling() + "')", symbol.declaredAt);
                }
            }
            g.add(new TUnit.Global(symbol, isDefinition, init));
        });
        return new TUnit(g, functions, exprs.strings);
    }

    // ---- declarations -------------------------------------------------------------

    private void externalDeclaration(Decl d) {
        if (d instanceof Decl.Declaration decl) declaration(decl, null);
        else if (d instanceof Decl.FunctionDefinition f) functionDefinition(f);
        else if (d instanceof Expr.StaticAssertion s) staticAssertion(s);
        // Attribute declarations have no effect.
    }

    /**
     * Types a declaration's declarators. Automatic objects become
     * {@link TStmt.LocalDecl}s appended to {@code out}; static ones,
     * whatever their scope, go to the unit's globals; typedefs and
     * function declarations only get their type.
     */
    private void declaration(Decl.Declaration d, @Nullable List<TStmt> out) {
        // A struct/union/enum body in the specifiers is typed once here,
        // whether or not any declarator follows (`enum E { A, B };`).
        d.specifiers().type().ifPresent(builder::build);
        boolean isExtern = d.specifiers().has("extern");
        for (var id : d.declarators()) {
            Symbol symbol = bindings.symbolOf(id);
            if (id.type().isPresent()) {
                declare(symbol, builder.build(id.type().get()), id.name());
            } else {
                declare(symbol, inferred(d, id, symbol), id.name());
            }
            // Typedefs, functions, and objects whose type turned out to be a
            // function type (through a typedef or typeof) only get a type.
            if (!(symbol instanceof Symbol.Variable v) || v.type().isFunction()) {
                if (id.initializer().isPresent()) {
                    throw new SemaException("'" + symbol.name + "' cannot have an initializer", id.name());
                }
                continue;
            }
            Optional<TInit> init = Optional.empty();
            if (id.initializer().isPresent()) {
                if (v.type().isVoid()) throw new SemaException("variable '" + v.name + "' has incomplete type 'void'", id.name());
                var r = initializers.normalize(id.initializer().get(), v.type(), id.name());
                if (r.type() != v.type()) declare(v, r.type(), id.name());
                init = Optional.of(r.init());
            }
            if (v.storage == Symbol.Variable.Storage.STATIC) {
                // The initializer of an object with static storage duration
                // is made of constant expressions (6.7.11p4); each item is
                // folded to its constant here.
                init = init.map(i -> constantInit(i, id.name()));
                if (init.isPresent() || !globals.containsKey(v)) globals.put(v, init);
                if (!isExtern) tentative.add(v);
            } else {
                if (!v.type().isComplete()) {
                    throw new SemaException("variable '" + v.name + "' has incomplete type '" + v.type().spelling()
                            + "'", id.name());
                }
                function.locals.add(v);
                out.add(new TStmt.LocalDecl(v, init, id.name()));
            }
        }
    }

    // auto (6.7.10): the type is the initializer's after lvalue conversion
    // and decay; the initializer is a single expression.
    private CType inferred(Decl.Declaration d, Decl.InitDeclarator id, Symbol symbol) {
        if (!d.specifiers().has("auto")) throw new IllegalStateException("declarator without a type");
        if (symbol instanceof Symbol.Typedef) throw new SemaException("typedef cannot be declared with auto", id.name());
        if (!(id.initializer().orElse(null) instanceof org.jbm.cc.ast.Initializer.Expression e)) {
            throw new SemaException("'" + symbol.name + "' declared with auto needs an initializer that is an expression",
                    id.name());
        }
        Rvalue value = exprs.rvalue(exprs.type(e.expr()));
        if (value.type().isVoid()) throw new SemaException("cannot infer 'void' for '" + symbol.name + "'", id.name());
        return value.type();
    }

    private TInit constantInit(TInit init, Token at) {
        var items = new ArrayList<TInit.Item>(init.items().size());
        for (var item : init.items()) {
            TExpr.Constant c = constEval.require(item.value(), item.value().token(), "initializer element");
            items.add(new TInit.Item(item.offset(), c, item.bits()));
        }
        return new TInit(items);
    }

    private void functionDefinition(Decl.FunctionDefinition d) {
        var type = (CType.Function) builder.build(d.type());
        declare(bindings.symbolOf(d), type, d.name());
        if (!type.returnType().isVoid() && !type.returnType().isComplete()) {
            throw new SemaException("function returns an incomplete type '" + type.returnType().spelling() + "'",
                    d.name());
        }
        var parameters = new ArrayList<Symbol>();
        for (var p : d.type().parameters()) p.name().ifPresent(n -> parameters.add(bindings.symbolOf(p)));
        function = new FunctionState(type.returnType());
        exprs.setLocals(function.locals);
        TStmt.Block body = block(d.body());
        functions.add(new TFunction(bindings.symbolOf(d), parameters, function.locals, body));
        exprs.setLocals(null);
        function = null;
    }

    // A static assertion (6.7.2), as a declaration or a member: the
    // condition is an integer constant expression that must be nonzero.
    private void staticAssertion(Expr.StaticAssertion e) {
        Rvalue condition = exprs.rvalue(exprs.type(e.condition()));
        long value = constEval.requireInteger(condition, e.keyword(), "static assertion condition");
        if (value == 0) {
            String message = e.message().map(m -> ": " + Literals.text(literals.string(m.parts()))).orElse("");
            throw new SemaException("static assertion failed" + message, e.keyword());
        }
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

    // ---- statements ------------------------------------------------------------------

    private TStmt stmt(Stmt s) {
        if (s instanceof Stmt.Compound c) return block(c);
        if (s instanceof Stmt.ExprStmt e) return exprStmt(e);
        if (s instanceof Stmt.If i) return ifStmt(i);
        if (s instanceof Stmt.While w) return whileStmt(w);
        if (s instanceof Stmt.DoWhile d) return doWhile(d);
        if (s instanceof Stmt.For f) return forStmt(f);
        if (s instanceof Stmt.Switch sw) return switchStmt(sw);
        if (s instanceof Stmt.Labeled l) return labeled(l);
        if (s instanceof Stmt.Goto g) return new TStmt.Goto(target(bindings.targetOf(g), g.label().text), g.keyword());
        if (s instanceof Stmt.Break b) return new TStmt.Break(target(bindings.targetOf(b), null), b.keyword());
        if (s instanceof Stmt.Continue c) return new TStmt.Continue(target(bindings.targetOf(c), null), c.keyword());
        if (s instanceof Stmt.Return r) return returnStmt(r);
        throw new IllegalStateException(s.toString());
    }

    private TStmt.Block block(Stmt.Compound c) {
        var items = new ArrayList<TStmt>();
        for (BlockItem item : c.items()) blockItem(item, items);
        return new TStmt.Block(items, c.brace());
    }

    private void blockItem(BlockItem item, List<TStmt> out) {
        if (item instanceof Decl.Declaration d) declaration(d, out);
        else if (item instanceof Expr.StaticAssertion s) staticAssertion(s);
        else if (item instanceof Stmt s) out.add(stmt(s));
        // Attribute declarations have no effect.
    }

    private TStmt exprStmt(Stmt.ExprStmt s) {
        if (s.expr().isEmpty()) return new TStmt.Block(List.of(), s.token());
        TExpr typed = exprs.type(s.expr().get());
        expressionStatements.add(typed);
        return new TStmt.ExprStmt(exprs.rvalue(typed), s.token());
    }

    // The JumpTarget for a loop, switch or labeled statement of the AST,
    // created on first mention from either the statement or a jump to it.
    private JumpTarget target(Stmt s, @Nullable String name) {
        return function.targets.computeIfAbsent(s, k -> new JumpTarget(name != null ? name : keywordOf(s)));
    }

    private static String keywordOf(Stmt s) {
        if (s instanceof Stmt.While w) return w.keyword().text;
        if (s instanceof Stmt.DoWhile d) return d.keyword().text;
        if (s instanceof Stmt.For f) return f.keyword().text;
        if (s instanceof Stmt.Switch sw) return sw.keyword().text;
        if (s instanceof Stmt.Labeled l && l.label() instanceof Stmt.NameLabel n) return n.name().text;
        return "label";
    }

    /** A controlling expression (6.8.5p3, 6.8.6p2): any scalar, tested against zero. */
    private Rvalue condition(Expr e, Token at, String what) {
        Rvalue c = exprs.rvalue(exprs.type(e));
        if (!c.type().isScalar()) {
            throw new SemaException("controlling expression of " + what + " must be scalar ('" + c.type().spelling()
                    + "')", at);
        }
        return exprs.toBool(c);
    }

    // A selection header (6.8.5.1) may declare an object first; the
    // declared object is the controlling value when no expression follows.
    // The declarations go before the statement in a block of their own.
    private TStmt withHeader(Stmt.Header h, Token at, java.util.function.Function<Expr, TStmt> make) {
        if (h.declaration().isEmpty()) return make.apply(h.condition().orElseThrow());
        var out = new ArrayList<TStmt>();
        declaration(h.declaration().get(), out);
        Expr controlling = h.condition().orElseGet(() -> {
            var declarators = h.declaration().get().declarators();
            return new Expr.Identifier(declarators.get(declarators.size() - 1).name());
        });
        // A synthesized identifier is not in Bindings; bind it here.
        if (h.condition().isEmpty()) {
            var declarators = h.declaration().get().declarators();
            bindings.identifiers.put((Expr.Identifier) controlling, bindings.symbolOf(declarators.get(declarators.size() - 1)));
        }
        out.add(make.apply(controlling));
        return new TStmt.Block(out, at);
    }

    private TStmt ifStmt(Stmt.If s) {
        return withHeader(s.header(), s.keyword(), cond -> new TStmt.If(condition(cond, s.keyword(), "if"),
                stmt(s.thenBranch()), s.elseBranch().map(this::stmt), s.keyword()));
    }

    private TStmt whileStmt(Stmt.While s) {
        JumpTarget t = target(s, null);
        return new TStmt.While(condition(s.condition(), s.keyword(), "while"), stmt(s.body()), t, s.keyword());
    }

    private TStmt doWhile(Stmt.DoWhile s) {
        JumpTarget t = target(s, null);
        TStmt body = stmt(s.body());
        return new TStmt.DoWhile(body, condition(s.condition(), s.keyword(), "do"), t, s.keyword());
    }

    private TStmt forStmt(Stmt.For s) {
        JumpTarget t = target(s, null);
        var init = new ArrayList<TStmt>();
        s.initDecl().ifPresent(d -> declaration(d, init));
        s.initExpr().ifPresent(e -> init.add(new TStmt.ExprStmt(exprs.rvalue(exprs.type(e)), s.keyword())));
        Optional<Rvalue> cond = s.condition().map(c -> condition(c, s.keyword(), "for"));
        Optional<Rvalue> step = s.step().map(e -> exprs.rvalue(exprs.type(e)));
        return new TStmt.For(init, cond, step, stmt(s.body()), t, s.keyword());
    }

    // switch (6.8.5.3): an integer controlling expression, promoted; the
    // body's case labels register with this switch and are converted to
    // the promoted type.
    private TStmt switchStmt(Stmt.Switch s) {
        JumpTarget t = target(s, null);
        return withHeader(s.header(), s.keyword(), cond -> {
            Rvalue value = exprs.rvalue(exprs.type(cond));
            if (!value.type().isInteger()) {
                throw new SemaException("switch quantity is not an integer ('" + value.type().spelling() + "')",
                        s.keyword());
            }
            value = exprs.promote(value);
            var state = new SwitchState(value.type());
            function.switches.push(state);
            TStmt body = stmt(s.body());
            function.switches.pop();
            return new TStmt.Switch(value, state.cases, Optional.ofNullable(state.defaultTarget), body, t, s.keyword());
        });
    }

    private TStmt labeled(Stmt.Labeled s) {
        JumpTarget t;
        Token at;
        if (s.label() instanceof Stmt.NameLabel n) {
            t = target(s, n.name().text);
            at = n.name();
        } else if (s.label() instanceof Stmt.CaseLabel c) {
            t = caseLabel(c);
            at = c.keyword();
        } else {
            var d = (Stmt.DefaultLabel) s.label();
            SwitchState state = function.switches.peek();
            if (state.defaultTarget != null) throw new SemaException("multiple default labels in one switch", d.keyword());
            t = state.defaultTarget = new JumpTarget("default");
            at = d.keyword();
        }
        return new TStmt.Labeled(t, s.body().map(this::stmt), at);
    }

    private JumpTarget caseLabel(Stmt.CaseLabel c) {
        SwitchState state = function.switches.peek();
        long low = caseValue(c.low(), state, c.keyword());
        if (c.high().isEmpty()) {
            if (!state.values.add(low) || inRange(low, state)) throw new SemaException("duplicate case value " + low, c.keyword());
            var t = new JumpTarget("case " + low);
            state.cases.add(new TStmt.Case(low, t));
            return t;
        }
        long high = caseValue(c.high().get(), state, c.keyword());
        boolean unsigned = state.type instanceof CType.Int i && !types.isSigned(i);
        if (unsigned ? Long.compareUnsigned(low, high) > 0 : low > high) {
            throw new SemaException("empty case range " + low + " ... " + high, c.keyword());
        }
        var range = new TStmt.CaseRange(low, high, new JumpTarget("case " + low + "..." + high));
        for (long v : state.values) if (inRange(v, range, unsigned)) throw new SemaException("case range overlaps case " + v, c.keyword());
        for (var other : state.ranges) {
            if (inRange(other.low(), range, unsigned) || inRange(range.low(), other, unsigned)) {
                throw new SemaException("overlapping case ranges", c.keyword());
            }
        }
        state.ranges.add(range);
        state.cases.add(range);
        return range.target();
    }

    // The label's constant, converted to the promoted controlling type (6.8.5.3p3).
    private long caseValue(Expr e, SwitchState state, Token at) {
        Rvalue v = exprs.rvalue(exprs.type(e));
        if (!v.type().isInteger()) throw new SemaException("case label is not an integer", at);
        return constEval.requireInteger(exprs.convert(v, state.type), at, "case label");
    }

    private boolean inRange(long v, SwitchState state) {
        boolean unsigned = state.type instanceof CType.Int i && !types.isSigned(i);
        for (var r : state.ranges) if (inRange(v, r, unsigned)) return true;
        return false;
    }

    private static boolean inRange(long v, TStmt.CaseRange r, boolean unsigned) {
        return unsigned ? Long.compareUnsigned(v, r.low()) >= 0 && Long.compareUnsigned(v, r.high()) <= 0
                : v >= r.low() && v <= r.high();
    }

    // return (6.8.6.4): a value only in a non-void function, converted to
    // the return type as if by assignment.
    private TStmt returnStmt(Stmt.Return s) {
        CType returnType = function.returnType;
        if (s.value().isEmpty()) {
            if (!returnType.isVoid()) throw new SemaException("non-void function should return a value", s.keyword());
            return new TStmt.Return(Optional.empty(), s.keyword());
        }
        Rvalue value = exprs.rvalue(exprs.type(s.value().get()));
        if (returnType.isVoid()) {
            if (!value.type().isVoid()) throw new SemaException("void function should not return a value", s.keyword());
            return new TStmt.Return(Optional.of(value), s.keyword());
        }
        return new TStmt.Return(Optional.of(exprs.assignConvert(value, types.unqualified(returnType), s.keyword(),
                "returning")), s.keyword());
    }
}

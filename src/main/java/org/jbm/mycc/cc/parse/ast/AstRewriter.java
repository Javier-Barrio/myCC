package org.jbm.mycc.cc.parse.ast;

import lombok.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A tree-to-tree transformation. The default visits rebuild each node
 * from its rewritten children and return the node itself when none of
 * them changed, so an unmodified subtree keeps its identity. Passes
 * extend this and override the node kinds they transform, using the
 * {@code rewrite} methods to descend.
 * <p>
 * Type nodes are memoized by identity: the parser shares them (a struct
 * specifier appears in the declaration specifiers and in every
 * declarator's type; the members of {@code int a, b;} share one type),
 * and a shared node must map to one rewritten node, or later passes that
 * key on identity would see two. Expressions, statements, declarations
 * and initializers are never shared, so they are dispatched directly.
 */
public abstract class AstRewriter implements Visitor<Object> {

    private final Map<Type, Type> memo = new IdentityHashMap<>();

    // ---- entry points -------------------------------------------------------

    public List<Decl> rewriteUnit(@NonNull List<? extends Decl> unit) {
        return rewriteAll(List.copyOf(unit), this::rewrite);
    }

    public BlockItem rewrite(@NonNull BlockItem item) {
        if (item instanceof Decl d) return rewrite(d);
        return rewrite((Stmt) item);
    }

    public Decl rewrite(@NonNull Decl d) {
        return (Decl) d.accept(this);
    }

    public Stmt rewrite(@NonNull Stmt s) {
        return (Stmt) s.accept(this);
    }

    public Expr rewrite(@NonNull Expr e) {
        return (Expr) e.accept(this);
    }

    public Type rewrite(@NonNull Type t) {
        Type done = memo.get(t);
        if (done != null) return done;
        Type result = (Type) t.accept(this);
        memo.put(t, result);
        return result;
    }

    public Initializer rewrite(@NonNull Initializer i) {
        return (Initializer) i.accept(this);
    }

    /** Applies f to every element; returns the same list when no element changed. */
    protected static <T> List<T> rewriteAll(List<T> items, UnaryOperator<T> f) {
        List<T> out = null;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            T result = f.apply(item);
            if (out == null && result != item) {
                out = new ArrayList<>(items.subList(0, i));
            }
            if (out != null) out.add(result);
        }
        return out == null ? items : Collections.unmodifiableList(out);
    }

    /** Applies f to a present value; returns the same Optional when the value did not change. */
    protected static <T> Optional<T> rewriteOptional(Optional<T> value, UnaryOperator<T> f) {
        if (value.isEmpty()) return value;
        T result = f.apply(value.get());
        return result == value.get() ? value : Optional.of(result);
    }

    // ---- non-sealed helper records --------------------------------------------

    protected Specifiers rewriteSpecifiers(Specifiers s) {
        var type = rewriteOptional(s.type(), this::rewrite);
        var alignment = rewriteOptional(s.alignment(), a -> {
            var at = rewriteOptional(a.type(), this::rewrite);
            var ae = rewriteOptional(a.expr(), this::rewrite);
            return at == a.type() && ae == a.expr() ? a : new Specifiers.Alignas(a.keyword(), at, ae);
        });
        if (type == s.type() && alignment == s.alignment()) return s;
        return new Specifiers(s.token(), s.storageClasses(), s.functionSpecifiers(), alignment, type);
    }

    protected Decl.InitDeclarator rewriteInitDeclarator(Decl.InitDeclarator d) {
        var type = rewriteOptional(d.type(), this::rewrite);
        var init = rewriteOptional(d.initializer(), this::rewrite);
        if (type == d.type() && init == d.initializer()) return d;
        return new Decl.InitDeclarator(d.name(), type, d.attributes(), init);
    }

    protected Type.Parameter rewriteParameter(Type.Parameter p) {
        Type type = rewrite(p.type());
        return type == p.type() ? p : new Type.Parameter(p.attributes(), p.storageClasses(), type, p.name());
    }

    protected Type.MemberDecl rewriteMember(Type.MemberDecl m) {
        if (m instanceof Expr.StaticAssertion sa) return (Type.MemberDecl) rewrite((Expr) sa);
        var member = (Type.Member) m;
        Type type = rewrite(member.type());
        var width = rewriteOptional(member.bitWidth(), this::rewrite);
        if (type == member.type() && width == member.bitWidth()) return m;
        return new Type.Member(member.attributes(), type, member.name(), width);
    }

    protected Type.Enumerator rewriteEnumerator(Type.Enumerator e) {
        var value = rewriteOptional(e.value(), this::rewrite);
        return value == e.value() ? e : new Type.Enumerator(e.name(), e.attributes(), value);
    }

    protected Stmt.Label rewriteLabel(Stmt.Label label) {
        if (label instanceof Stmt.CaseLabel c) {
            Expr low = rewrite(c.low());
            var high = rewriteOptional(c.high(), this::rewrite);
            if (low != c.low() || high != c.high()) return new Stmt.CaseLabel(c.keyword(), low, high);
        }
        return label;
    }

    protected Stmt.Header rewriteHeader(Stmt.Header h) {
        var decl = rewriteOptional(h.declaration(), d -> (Decl.Declaration) rewrite(d));
        var cond = rewriteOptional(h.condition(), this::rewrite);
        if (decl == h.declaration() && cond == h.condition()) return h;
        return new Stmt.Header(decl, cond);
    }

    protected Initializer.Item rewriteItem(Initializer.Item item) {
        var designators = rewriteAll(item.designators(), d -> {
            if (d instanceof Initializer.ArrayDesignator a) {
                Expr index = rewrite(a.index());
                Optional<Expr> last = a.last().map(this::rewrite);
                boolean lastChanged = a.last().isPresent() && last.get() != a.last().get();
                if (index != a.index() || lastChanged) {
                    return new Initializer.ArrayDesignator(a.bracket(), index, last);
                }
            }
            return d;
        });
        Initializer init = rewrite(item.initializer());
        if (designators == item.designators() && init == item.initializer()) return item;
        return new Initializer.Item(designators, init);
    }

    // ---- expressions ---------------------------------------------------------------

    @Override
    public Object visit(Expr.Identifier e) {
        return e;
    }

    @Override
    public Object visit(Expr.Literal e) {
        return e;
    }

    @Override
    public Object visit(Expr.StringLiteral e) {
        return e;
    }

    @Override
    public Object visit(Expr.Generic e) {
        var ce = rewriteOptional(e.controllingExpr(), this::rewrite);
        var ct = rewriteOptional(e.controllingType(), this::rewrite);
        var assocs = rewriteAll(e.associations(), a -> {
            var t = rewriteOptional(a.type(), this::rewrite);
            Expr x = rewrite(a.expr());
            return t == a.type() && x == a.expr() ? a : new Expr.Generic.Association(t, x);
        });
        if (ce == e.controllingExpr() && ct == e.controllingType() && assocs == e.associations()) return e;
        return new Expr.Generic(e.keyword(), ce, ct, assocs);
    }

    @Override
    public Object visit(Expr.Index e) {
        Expr array = rewrite(e.array());
        Expr index = rewrite(e.index());
        return array == e.array() && index == e.index() ? e : new Expr.Index(e.bracket(), array, index);
    }

    @Override
    public Object visit(Expr.Call e) {
        Expr callee = rewrite(e.callee());
        var args = rewriteAll(e.arguments(), this::rewrite);
        return callee == e.callee() && args == e.arguments() ? e : new Expr.Call(e.paren(), callee, args);
    }

    @Override
    public Object visit(Expr.Member e) {
        Expr object = rewrite(e.object());
        return object == e.object() ? e : new Expr.Member(e.op(), object, e.name());
    }

    @Override
    public Object visit(Expr.Postfix e) {
        Expr operand = rewrite(e.operand());
        return operand == e.operand() ? e : new Expr.Postfix(e.op(), operand);
    }

    @Override
    public Object visit(Expr.CompoundLiteral e) {
        Type type = rewrite(e.type());
        var init = (Initializer.Braced) rewrite(e.initializer());
        if (type == e.type() && init == e.initializer()) return e;
        return new Expr.CompoundLiteral(e.paren(), e.storageClasses(), type, init);
    }

    @Override
    public Object visit(Expr.Unary e) {
        Expr operand = rewrite(e.operand());
        return operand == e.operand() ? e : new Expr.Unary(e.op(), operand);
    }

    @Override
    public Object visit(Expr.TypeOperator e) {
        Type type = rewrite(e.type());
        return type == e.type() ? e : new Expr.TypeOperator(e.op(), type);
    }

    @Override
    public Object visit(Expr.StaticAssertion e) {
        Expr cond = rewrite(e.condition());
        return cond == e.condition() ? e : new Expr.StaticAssertion(e.keyword(), cond, e.message());
    }

    @Override
    public Object visit(Expr.Cast e) {
        Type type = rewrite(e.type());
        Expr operand = rewrite(e.operand());
        return type == e.type() && operand == e.operand() ? e : new Expr.Cast(e.paren(), type, operand);
    }

    @Override
    public Object visit(Expr.Binary e) {
        Expr left = rewrite(e.left());
        Expr right = rewrite(e.right());
        return left == e.left() && right == e.right() ? e : new Expr.Binary(e.op(), left, right);
    }

    @Override
    public Object visit(Expr.Conditional e) {
        Expr cond = rewrite(e.condition());
        Expr thenExpr = rewrite(e.thenExpr());
        Expr elseExpr = rewrite(e.elseExpr());
        if (cond == e.condition() && thenExpr == e.thenExpr() && elseExpr == e.elseExpr()) return e;
        return new Expr.Conditional(e.question(), cond, thenExpr, elseExpr);
    }

    @Override
    public Object visit(Expr.Assign e) {
        Expr target = rewrite(e.target());
        Expr value = rewrite(e.value());
        return target == e.target() && value == e.value() ? e : new Expr.Assign(e.op(), target, value);
    }

    @Override
    public Object visit(Expr.Comma e) {
        Expr left = rewrite(e.left());
        Expr right = rewrite(e.right());
        return left == e.left() && right == e.right() ? e : new Expr.Comma(e.comma(), left, right);
    }

    @Override
    public Object visit(Expr.VaStart e) {
        Expr ap = rewrite(e.ap());
        Expr last = rewrite(e.last());
        return ap == e.ap() && last == e.last() ? e : new Expr.VaStart(e.token(), ap, last);
    }

    @Override
    public Object visit(Expr.VaArg e) {
        Expr ap = rewrite(e.ap());
        Type type = rewrite(e.type());
        return ap == e.ap() && type == e.type() ? e : new Expr.VaArg(e.token(), ap, type);
    }

    @Override
    public Object visit(Expr.StmtExpr e) {
        Stmt body = rewrite(e.body());
        return body == e.body() ? e : new Expr.StmtExpr(e.paren(), (Stmt.Compound) body);
    }

    // ---- statements --------------------------------------------------------------------

    @Override
    public Object visit(Stmt.Labeled s) {
        Stmt.Label label = rewriteLabel(s.label());
        var body = rewriteOptional(s.body(), this::rewrite);
        return label == s.label() && body == s.body() ? s : new Stmt.Labeled(label, body);
    }

    @Override
    public Object visit(Stmt.Compound s) {
        var items = rewriteAll(s.items(), this::rewrite);
        return items == s.items() ? s : new Stmt.Compound(s.brace(), items);
    }

    @Override
    public Object visit(Stmt.ExprStmt s) {
        var expr = rewriteOptional(s.expr(), this::rewrite);
        return expr == s.expr() ? s : new Stmt.ExprStmt(s.token(), expr);
    }

    @Override
    public Object visit(Stmt.If s) {
        Stmt.Header header = rewriteHeader(s.header());
        Stmt thenBranch = rewrite(s.thenBranch());
        var elseBranch = rewriteOptional(s.elseBranch(), this::rewrite);
        if (header == s.header() && thenBranch == s.thenBranch() && elseBranch == s.elseBranch()) return s;
        return new Stmt.If(s.keyword(), header, thenBranch, elseBranch);
    }

    @Override
    public Object visit(Stmt.Switch s) {
        Stmt.Header header = rewriteHeader(s.header());
        Stmt body = rewrite(s.body());
        return header == s.header() && body == s.body() ? s : new Stmt.Switch(s.keyword(), header, body);
    }

    @Override
    public Object visit(Stmt.While s) {
        Expr cond = rewrite(s.condition());
        Stmt body = rewrite(s.body());
        return cond == s.condition() && body == s.body() ? s : new Stmt.While(s.keyword(), cond, body);
    }

    @Override
    public Object visit(Stmt.DoWhile s) {
        Stmt body = rewrite(s.body());
        Expr cond = rewrite(s.condition());
        return body == s.body() && cond == s.condition() ? s : new Stmt.DoWhile(s.keyword(), body, cond);
    }

    @Override
    public Object visit(Stmt.For s) {
        var initDecl = rewriteOptional(s.initDecl(), d -> (Decl.Declaration) rewrite(d));
        var initExpr = rewriteOptional(s.initExpr(), this::rewrite);
        var cond = rewriteOptional(s.condition(), this::rewrite);
        var step = rewriteOptional(s.step(), this::rewrite);
        Stmt body = rewrite(s.body());
        if (initDecl == s.initDecl() && initExpr == s.initExpr() && cond == s.condition()
                && step == s.step() && body == s.body()) return s;
        return new Stmt.For(s.keyword(), initDecl, initExpr, cond, step, body);
    }

    @Override
    public Object visit(Stmt.Goto s) {
        return s;
    }

    @Override
    public Object visit(Stmt.Continue s) {
        return s;
    }

    @Override
    public Object visit(Stmt.Break s) {
        return s;
    }

    @Override
    public Object visit(Stmt.Return s) {
        var value = rewriteOptional(s.value(), this::rewrite);
        return value == s.value() ? s : new Stmt.Return(s.keyword(), value);
    }

    // ---- declarations ----------------------------------------------------------------------

    @Override
    public Object visit(Decl.Declaration d) {
        Specifiers specs = rewriteSpecifiers(d.specifiers());
        var declarators = rewriteAll(d.declarators(), this::rewriteInitDeclarator);
        if (specs == d.specifiers() && declarators == d.declarators()) return d;
        return new Decl.Declaration(d.attributes(), specs, declarators);
    }

    @Override
    public Object visit(Decl.FunctionDefinition d) {
        Specifiers specs = rewriteSpecifiers(d.specifiers());
        var type = (Type.Function) rewrite(d.type());
        var body = (Stmt.Compound) rewrite(d.body());
        if (specs == d.specifiers() && type == d.type() && body == d.body()) return d;
        return new Decl.FunctionDefinition(d.attributes(), specs, d.name(), type, body);
    }

    @Override
    public Object visit(Decl.AttributeDeclaration d) {
        return d;
    }

    // ---- types -----------------------------------------------------------------------------------

    @Override
    public Object visit(Type.Basic t) {
        return t;
    }

    @Override
    public Object visit(Type.BitInt t) {
        Expr width = rewrite(t.width());
        return width == t.width() ? t : new Type.BitInt(t.token(), t.isUnsigned(), width, t.quals());
    }

    @Override
    public Object visit(Type.Pointer t) {
        Type target = rewrite(t.target());
        return target == t.target() ? t : new Type.Pointer(t.star(), target, t.quals());
    }

    @Override
    public Object visit(Type.Array t) {
        Type element = rewrite(t.element());
        var size = rewriteOptional(t.size(), this::rewrite);
        if (element == t.element() && size == t.size()) return t;
        return new Type.Array(t.bracket(), element, size, t.isStar(), t.isStatic(), t.quals());
    }

    @Override
    public Object visit(Type.Function t) {
        Type ret = rewrite(t.returnType());
        var params = rewriteAll(t.parameters(), this::rewriteParameter);
        if (ret == t.returnType() && params == t.parameters()) return t;
        return new Type.Function(t.paren(), ret, params, t.isVariadic(), t.quals());
    }

    @Override
    public Object visit(Type.Struct t) {
        var members = rewriteOptional(t.members(), ms -> rewriteAll(ms, this::rewriteMember));
        return members == t.members() ? t : new Type.Struct(t.keyword(), t.tag(), members, t.quals());
    }

    @Override
    public Object visit(Type.Enum t) {
        var underlying = rewriteOptional(t.underlying(), this::rewrite);
        var enumerators = rewriteOptional(t.enumerators(), es -> rewriteAll(es, this::rewriteEnumerator));
        if (underlying == t.underlying() && enumerators == t.enumerators()) return t;
        return new Type.Enum(t.keyword(), t.tag(), underlying, enumerators, t.quals());
    }

    @Override
    public Object visit(Type.TypedefName t) {
        return t;
    }

    @Override
    public Object visit(Type.Typeof t) {
        var expr = rewriteOptional(t.expr(), this::rewrite);
        var type = rewriteOptional(t.type(), this::rewrite);
        return expr == t.expr() && type == t.type() ? t : new Type.Typeof(t.keyword(), expr, type, t.quals());
    }

    // ---- initializers --------------------------------------------------------------------------------

    @Override
    public Object visit(Initializer.Expression i) {
        Expr expr = rewrite(i.expr());
        return expr == i.expr() ? i : new Initializer.Expression(expr);
    }

    @Override
    public Object visit(Initializer.Braced i) {
        var items = rewriteAll(i.items(), this::rewriteItem);
        return items == i.items() ? i : new Initializer.Braced(i.brace(), items);
    }
}

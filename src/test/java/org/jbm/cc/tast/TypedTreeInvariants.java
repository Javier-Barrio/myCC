package org.jbm.cc.tast;

import org.jbm.cc.sema.Symbol;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Layout;
import org.jbm.cc.types.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structural rules every typed node must satisfy, checked over a
 * whole unit: operand types match what the node kind promises, every
 * conversion changes the type, jumps point at targets that exist in the
 * function, locals are listed, static initializers are constants, and
 * a {@code TargetValue} refers to its enclosing assignment's target
 * node. Run over the corpus so every construct is covered.
 */
public final class TypedTreeInvariants implements TVisitor<Void>, TStmtVisitor<Void> {

    private final Types types;
    private final Deque<TExpr.Lvalue> assignmentTargets = new ArrayDeque<>();
    private final Set<JumpTarget> declaredTargets = new HashSet<>();
    private final List<TStmt> jumps = new ArrayList<>();
    private Set<Symbol> locals = Set.of();
    private CType returnType = null;
    private int nodes;

    private TypedTreeInvariants(Types types) {
        this.types = types;
    }

    /** Checks the unit; returns the number of expression nodes visited. */
    public static int check(TUnit unit, Types types) {
        var v = new TypedTreeInvariants(types);
        for (var g : unit.globals()) {
            assertTrue(g.symbol() instanceof Symbol.Variable var && var.storage == Symbol.Variable.Storage.STATIC,
                    "global " + g.symbol().name + " has static storage");
            if (g.isDefinition()) assertTrue(g.symbol().type().isComplete(), "definition " + g.symbol().name + " is complete");
            g.init().ifPresent(init -> {
                assertTrue(g.isDefinition(), "an initialized global is a definition");
                for (var item : init.items()) {
                    assertTrue(item.value() instanceof TExpr.Constant, "static initializer item is a constant: "
                            + TypedPrinter.print(item.value()));
                    assertTrue(item.offset() >= 0 && item.offset() < Math.max(1, types.size(g.symbol().type())),
                            "item offset within the object");
                    item.value().accept(v);
                }
            });
        }
        for (var s : unit.strings()) {
            assertTrue(s.symbol().type() instanceof CType.Array a && a.size().getAsLong() == s.units().length,
                    "string type matches its units");
            assertEquals(0, s.units()[s.units().length - 1], "strings end in a null");
        }
        for (var f : unit.functions()) v.function(f);
        return v.nodes;
    }

    private void function(TFunction f) {
        var type = (CType.Function) f.symbol().type();
        assertTrue(f.parameters().size() <= type.parameters().size(), "no more named parameters than parameter types");
        for (var p : f.parameters()) assertTrue(p instanceof Symbol.Parameter, p.name + " is a parameter");
        locals = new HashSet<>(f.locals());
        for (var l : f.locals()) {
            assertTrue(l instanceof Symbol.Variable var && var.storage == Symbol.Variable.Storage.AUTOMATIC, l.name + " is automatic");
            assertTrue(l.type().isComplete(), l.name + " has a complete type");
        }
        returnType = type.returnType();
        declaredTargets.clear();
        jumps.clear();
        f.body().accept(this);
        for (TStmt j : jumps) {
            JumpTarget t = j instanceof TStmt.Goto g ? g.target() : j instanceof TStmt.Break b ? b.target()
                    : ((TStmt.Continue) j).target();
            assertTrue(declaredTargets.contains(t), "jump target " + t + " belongs to the function");
        }
        assertTrue(assignmentTargets.isEmpty());
    }

    // ---- helpers -------------------------------------------------------------------

    private void node(TExpr e) {
        nodes++;
        assertNotNull(e.type());
        assertNotNull(e.token());
    }

    private Void rvalue(TExpr.Rvalue x) {
        return x.accept(this);
    }

    private void conversion(TExpr.Conversion c, boolean fromOk, boolean toOk) {
        node(c);
        assertTrue(fromOk, "operand type of " + c.getClass().getSimpleName() + ": " + c.operand().type());
        assertTrue(toOk, "result type of " + c.getClass().getSimpleName() + ": " + c.type());
        c.operand().accept(this);
    }

    private void arithmetic(TExpr.Arithmetic e, boolean integerOnly) {
        node(e);
        assertSame(e.type(), e.left().type(), "left operand has the node's type");
        assertSame(e.type(), e.right().type(), "right operand has the node's type");
        assertTrue(integerOnly ? e.type().isInteger() : e.type().isArithmetic());
        assertSame(e.type(), types.unqualified(e.type()), "arithmetic results are unqualified");
        rvalue(e.left());
        rvalue(e.right());
    }

    private void comparison(TExpr.Comparison e) {
        node(e);
        assertSame(types.int_(), e.type());
        assertSame(e.left().type(), e.right().type(), "comparison operands share one type");
        rvalue(e.left());
        rvalue(e.right());
    }

    private void logical(TExpr.Logical e) {
        node(e);
        assertSame(types.int_(), e.type());
        assertSame(types.bool_(), e.left().type());
        assertSame(types.bool_(), e.right().type());
        rvalue(e.left());
        rvalue(e.right());
    }

    private void shift(TExpr.Shift e) {
        node(e);
        assertSame(e.type(), e.left().type());
        assertTrue(e.left().type().isInteger() && e.right().type().isInteger());
        assertSame(e.right().type(), types.promote(e.right().type()), "the shift amount is promoted");
        rvalue(e.left());
        rvalue(e.right());
    }

    private static boolean fitsWidth(long value, int width, boolean signed) {
        if (width >= 64) return true;
        return signed ? value == (value << (64 - width)) >> (64 - width) : value == (value & ((1L << width) - 1));
    }

    // ---- lvalues and designators ----------------------------------------------------

    @Override
    public Void visit(TExpr.VarRef e) {
        node(e);
        assertTrue(e.symbol() instanceof Symbol.Variable || e.symbol() instanceof Symbol.Parameter);
        assertSame(e.symbol().type(), e.type());
        if (e.symbol() instanceof Symbol.Variable v && v.storage == Symbol.Variable.Storage.AUTOMATIC) {
            assertTrue(locals.contains(e.symbol()), "automatic object " + e.symbol().name + " is among the function's locals");
        }
        return null;
    }

    @Override
    public Void visit(TExpr.FuncRef e) {
        node(e);
        assertTrue(e.symbol() instanceof Symbol.Function);
        assertTrue(e.type().isFunction());
        assertSame(e.symbol().type(), e.type());
        return null;
    }

    @Override
    public Void visit(TExpr.Deref e) {
        node(e);
        assertTrue(e.pointer().type() instanceof CType.Pointer p && p.target() == e.type());
        assertTrue(!e.type().isFunction() && !e.type().isVoid());
        return rvalue(e.pointer());
    }

    @Override
    public Void visit(TExpr.FuncDeref e) {
        node(e);
        assertTrue(e.pointer().type() instanceof CType.Pointer p && p.target() == e.type() && e.type().isFunction());
        return rvalue(e.pointer());
    }

    @Override
    public Void visit(TExpr.Member e) {
        node(e);
        assertTrue(e.base().type() instanceof CType.Record);
        Layout layout = ((CType.Record) e.base().type()).tag().layout().orElseThrow();
        Layout.Member found = layout.member(e.member().name()).orElseThrow();
        assertEquals(found.offset(), e.member().offset());
        assertSame(types.unqualified(e.member().type()), types.unqualified(e.type()));
        assertSame(types.plusQuals(e.member().type(), e.base().type().quals()), e.type(), "the base's qualifiers apply");
        return e.base().accept(this);
    }

    @Override
    public Void visit(TExpr.Materialize e) {
        node(e);
        assertTrue(e.value().type().isRecord());
        assertSame(types.unqualified(e.value().type()), e.type());
        assertSame(e.symbol().type(), e.type());
        return rvalue(e.value());
    }

    @Override
    public Void visit(TExpr.CompoundLit e) {
        node(e);
        assertSame(e.symbol().type(), e.type());
        assertTrue(e.type().isComplete());
        boolean isStatic = ((Symbol.Variable) e.symbol()).storage == Symbol.Variable.Storage.STATIC;
        for (var item : e.init().items()) {
            if (isStatic) assertTrue(item.value() instanceof TExpr.Constant);
            item.value().accept(this);
        }
        return null;
    }

    // ---- constants -----------------------------------------------------------------------

    @Override
    public Void visit(TExpr.IntConst e) {
        node(e);
        assertTrue(e.type().isInteger());
        assertTrue(fitsWidth(e.value(), types.width(e.type()), types.isSigned(e.type())),
                "constant " + e.value() + " fits " + e.type());
        return null;
    }

    @Override
    public Void visit(TExpr.FloatConst e) {
        node(e);
        assertTrue(e.type().isFloating());
        return null;
    }

    @Override
    public Void visit(TExpr.NullptrConst e) {
        node(e);
        assertTrue(e.type().isNullptr());
        return null;
    }

    @Override
    public Void visit(TExpr.AddrConst e) {
        node(e);
        assertTrue(e.type().isPointer() || e.type().isNullptr());
        e.base().ifPresent(b -> assertTrue(b instanceof Symbol.Function
                || b instanceof Symbol.Variable v && v.storage == Symbol.Variable.Storage.STATIC, "address of a static object"));
        return null;
    }

    // ---- conversions ------------------------------------------------------------------------

    @Override
    public Void visit(TExpr.LvalueToRvalue e) {
        conversion(e, !e.operand().type().isArray() && !e.operand().type().isFunction(),
                e.type() == types.unqualified(e.operand().type()));
        return null;
    }

    @Override
    public Void visit(TExpr.ArrayDecay e) {
        conversion(e, e.operand().type() instanceof CType.Array,
                e.type() == types.pointer(((CType.Array) e.operand().type()).element()));
        return null;
    }

    @Override
    public Void visit(TExpr.FunctionDecay e) {
        conversion(e, e.operand().type().isFunction(), e.type() == types.pointer(e.operand().type()));
        return null;
    }

    @Override
    public Void visit(TExpr.IntToInt e) {
        conversion(e, e.operand().type().isInteger(), e.type().isInteger() && e.type() != e.operand().type());
        return null;
    }

    @Override
    public Void visit(TExpr.IntToFloat e) {
        conversion(e, e.operand().type().isInteger(), e.type().isFloating());
        return null;
    }

    @Override
    public Void visit(TExpr.FloatToInt e) {
        conversion(e, e.operand().type().isFloating(), e.type().isInteger() && !e.type().isBool());
        return null;
    }

    @Override
    public Void visit(TExpr.FloatToFloat e) {
        conversion(e, e.operand().type().isFloating(), e.type().isFloating() && e.type() != e.operand().type());
        return null;
    }

    @Override
    public Void visit(TExpr.ToBool e) {
        conversion(e, e.operand().type().isScalar() && !e.operand().type().isBool(), e.type().isBool());
        return null;
    }

    @Override
    public Void visit(TExpr.ToVoid e) {
        conversion(e, !e.operand().type().isVoid(), e.type().isVoid());
        return null;
    }

    @Override
    public Void visit(TExpr.PtrToPtr e) {
        conversion(e, e.operand().type().isPointer(), e.type().isPointer() && e.type() != e.operand().type());
        return null;
    }

    @Override
    public Void visit(TExpr.IntToPtr e) {
        conversion(e, e.operand().type().isInteger(), e.type().isPointer());
        return null;
    }

    @Override
    public Void visit(TExpr.PtrToInt e) {
        conversion(e, e.operand().type().isPointer(), e.type().isInteger() && !e.type().isBool());
        return null;
    }

    @Override
    public Void visit(TExpr.NullToPtr e) {
        conversion(e, e.operand().type().isInteger() || e.operand().type().isNullptr() || e.operand().type().isPointer(),
                e.type().isPointer() || e.type().isNullptr());
        return null;
    }

    // ---- pointer operators ----------------------------------------------------------------------

    @Override
    public Void visit(TExpr.AddrOf e) {
        node(e);
        assertSame(types.pointer(e.operand().type()), e.type());
        assertTrue(!(e.operand() instanceof TExpr.Member m && m.member().bits().isPresent()), "no address of a bit-field");
        return e.operand().accept(this);
    }

    @Override
    public Void visit(TExpr.PtrAdd e) {
        node(e);
        assertSame(e.pointer().type(), e.type());
        assertTrue(e.type() instanceof CType.Pointer p && p.target().isComplete());
        assertSame(types.ptrdiffT(), e.index().type());
        rvalue(e.pointer());
        return rvalue(e.index());
    }

    @Override
    public Void visit(TExpr.PtrDiff e) {
        node(e);
        assertSame(types.ptrdiffT(), e.type());
        assertTrue(e.left().type().isPointer() && e.right().type().isPointer());
        rvalue(e.left());
        return rvalue(e.right());
    }

    // ---- arithmetic, shifts, comparisons, logical, unary --------------------------------------

    @Override
    public Void visit(TExpr.Add e) {
        arithmetic(e, false);
        return null;
    }

    @Override
    public Void visit(TExpr.Sub e) {
        arithmetic(e, false);
        return null;
    }

    @Override
    public Void visit(TExpr.Mul e) {
        arithmetic(e, false);
        return null;
    }

    @Override
    public Void visit(TExpr.Div e) {
        arithmetic(e, false);
        return null;
    }

    @Override
    public Void visit(TExpr.Rem e) {
        arithmetic(e, true);
        return null;
    }

    @Override
    public Void visit(TExpr.BitAnd e) {
        arithmetic(e, true);
        return null;
    }

    @Override
    public Void visit(TExpr.BitOr e) {
        arithmetic(e, true);
        return null;
    }

    @Override
    public Void visit(TExpr.BitXor e) {
        arithmetic(e, true);
        return null;
    }

    @Override
    public Void visit(TExpr.Shl e) {
        shift(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Shr e) {
        shift(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Eq e) {
        comparison(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Ne e) {
        comparison(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Lt e) {
        comparison(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Le e) {
        comparison(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Gt e) {
        comparison(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Ge e) {
        comparison(e);
        return null;
    }

    @Override
    public Void visit(TExpr.And e) {
        logical(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Or e) {
        logical(e);
        return null;
    }

    @Override
    public Void visit(TExpr.Neg e) {
        node(e);
        assertSame(e.operand().type(), e.type());
        assertTrue(e.type().isArithmetic());
        assertSame(e.type(), types.promote(e.type()), "unary minus works on a promoted operand");
        return rvalue(e.operand());
    }

    @Override
    public Void visit(TExpr.BitNot e) {
        node(e);
        assertSame(e.operand().type(), e.type());
        assertTrue(e.type().isInteger());
        assertSame(e.type(), types.promote(e.type()));
        return rvalue(e.operand());
    }

    @Override
    public Void visit(TExpr.Not e) {
        node(e);
        assertSame(types.int_(), e.type());
        assertSame(types.bool_(), e.operand().type());
        return rvalue(e.operand());
    }

    // ---- calls and assignment -------------------------------------------------------------------

    @Override
    public Void visit(TExpr.Call e) {
        node(e);
        assertTrue(e.callee().type() instanceof CType.Pointer p && p.target() instanceof CType.Function);
        var f = (CType.Function) ((CType.Pointer) e.callee().type()).target();
        assertSame(f.returnType(), e.type());
        assertTrue(e.arguments().size() >= f.parameters().size());
        assertTrue(f.isVariadic() || e.arguments().size() == f.parameters().size());
        for (int i = 0; i < e.arguments().size(); i++) {
            TExpr.Rvalue arg = e.arguments().get(i);
            if (i < f.parameters().size()) assertSame(f.parameters().get(i), arg.type(), "argument " + (i + 1) + " has the parameter's type");
            else assertSame(types.defaultArgumentPromote(arg.type()), arg.type(), "variadic argument " + (i + 1) + " is promoted");
            rvalue(arg);
        }
        return rvalue(e.callee());
    }

    @Override
    public Void visit(TExpr.Assign e) {
        node(e);
        assertSame(types.unqualified(e.target().type()), e.type());
        assertSame(e.type(), e.value().type());
        assertTrue(!e.target().type().quals().isConst() && !e.target().type().isArray());
        e.target().accept(this);
        return rvalue(e.value());
    }

    private void readModifyWrite(TExpr.Lvalue target, TExpr.Rvalue newValue, CType type) {
        assertSame(types.unqualified(target.type()), type);
        assertSame(type, newValue.type());
        assertTrue(!target.type().quals().isConst());
        target.accept(this);
        assignmentTargets.push(target);
        rvalue(newValue);
        assignmentTargets.pop();
    }

    @Override
    public Void visit(TExpr.CompoundAssign e) {
        node(e);
        readModifyWrite(e.target(), e.newValue(), e.type());
        return null;
    }

    @Override
    public Void visit(TExpr.PostfixAssign e) {
        node(e);
        readModifyWrite(e.target(), e.newValue(), e.type());
        return null;
    }

    @Override
    public Void visit(TExpr.TargetValue e) {
        node(e);
        assertTrue(!assignmentTargets.isEmpty(), "a TargetValue is inside an assignment");
        assertSame(assignmentTargets.peek(), e.target(), "and refers to that assignment's target node");
        assertSame(types.unqualified(e.target().type()), e.type());
        return null;
    }

    @Override
    public Void visit(TExpr.Cond e) {
        node(e);
        assertSame(types.bool_(), e.condition().type());
        assertSame(e.type(), e.thenValue().type());
        assertSame(e.type(), e.elseValue().type());
        rvalue(e.condition());
        rvalue(e.thenValue());
        return rvalue(e.elseValue());
    }

    @Override
    public Void visit(TExpr.Comma e) {
        node(e);
        assertSame(e.right().type(), e.type());
        assertTrue(e.left().type().isVoid(), "the left operand of a comma is a void expression");
        rvalue(e.left());
        return rvalue(e.right());
    }

    // ---- statements ---------------------------------------------------------------------------------

    private void condition(TExpr.Rvalue c) {
        assertSame(types.bool_(), c.type(), "a controlling expression is a bool");
        rvalue(c);
    }

    private void target(JumpTarget t) {
        assertTrue(declaredTargets.add(t), "each jump target belongs to one statement");
    }

    @Override
    public Void visit(TStmt.Block s) {
        for (TStmt item : s.items()) item.accept(this);
        return null;
    }

    @Override
    public Void visit(TStmt.ExprStmt s) {
        return rvalue(s.expr());
    }

    @Override
    public Void visit(TStmt.LocalDecl s) {
        assertTrue(locals.contains(s.symbol()), "declared local " + s.symbol().name + " is listed");
        s.init().ifPresent(init -> {
            if (s.symbol().type().isScalar()) {
                assertTrue(init.items().size() <= 1);
                init.items().forEach(i -> assertSame(types.unqualified(s.symbol().type()), i.value().type()));
            }
            for (var item : init.items()) {
                assertTrue(item.offset() + types.size(item.value().type()) <= types.size(s.symbol().type()),
                        "item at " + item.offset() + " fits in " + s.symbol().name);
                item.value().accept(this);
            }
        });
        return null;
    }

    @Override
    public Void visit(TStmt.If s) {
        condition(s.condition());
        s.thenBranch().accept(this);
        s.elseBranch().ifPresent(b -> b.accept(this));
        return null;
    }

    @Override
    public Void visit(TStmt.While s) {
        target(s.target());
        condition(s.condition());
        return s.body().accept(this);
    }

    @Override
    public Void visit(TStmt.DoWhile s) {
        target(s.target());
        s.body().accept(this);
        condition(s.condition());
        return null;
    }

    @Override
    public Void visit(TStmt.For s) {
        target(s.target());
        for (TStmt i : s.init()) i.accept(this);
        s.condition().ifPresent(this::condition);
        s.step().ifPresent(this::rvalue);
        return s.body().accept(this);
    }

    @Override
    public Void visit(TStmt.Switch s) {
        target(s.target());
        assertTrue(s.value().type().isInteger());
        assertSame(s.value().type(), types.promote(s.value().type()), "the controlling value is promoted");
        var seen = new HashSet<Long>();
        for (var c : s.cases()) {
            if (c instanceof TStmt.Case single) assertTrue(seen.add(single.value()), "distinct case values");
            else if (c instanceof TStmt.CaseRange r) assertTrue(r.low() <= r.high() || !types.isSigned(s.value().type()));
        }
        rvalue(s.value());
        return s.body().accept(this);
    }

    @Override
    public Void visit(TStmt.Labeled s) {
        target(s.target());
        s.body().ifPresent(b -> b.accept(this));
        return null;
    }

    @Override
    public Void visit(TStmt.Goto s) {
        jumps.add(s);
        return null;
    }

    @Override
    public Void visit(TStmt.Break s) {
        jumps.add(s);
        return null;
    }

    @Override
    public Void visit(TStmt.Continue s) {
        jumps.add(s);
        return null;
    }

    @Override
    public Void visit(TStmt.Return s) {
        if (s.value().isEmpty()) {
            assertTrue(returnType.isVoid());
        } else {
            assertSame(returnType.isVoid() ? types.void_() : types.unqualified(returnType), s.value().get().type());
            rvalue(s.value().get());
        }
        return null;
    }
}

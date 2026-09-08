package org.jbm.cc.tast;

import lombok.NonNull;

import java.util.List;

/**
 * S-expression dump of a typed tree, the test oracle: every node prints
 * as {@code (name:type children...)}, references as {@code name:type},
 * constants as {@code value:type}. The node name is the record's, so the
 * conversion the typer inserted is visible in the expectation:
 * {@code (add:int (rv:int a:int) (int-to-int:int (rv:char b:char)))}.
 */
public final class TypedPrinter implements TVisitor<String>, TStmtVisitor<String> {

    private TypedPrinter() {
    }

    public static String print(@NonNull TExpr e) {
        return e.accept(new TypedPrinter());
    }

    public static String print(@NonNull TStmt s) {
        return s.accept(new TypedPrinter());
    }

    public static String print(@NonNull TFunction f) {
        return new TypedPrinter().function(f);
    }

    /** One line per global, string literal and function, in that order. */
    public static String print(@NonNull TUnit unit) {
        var p = new TypedPrinter();
        var lines = new java.util.ArrayList<String>();
        for (var g : unit.globals()) {
            lines.add("(global " + p.symbol(g.symbol()) + g.init().map(i -> " " + p.init(i, g.symbol().type())).orElse("") + ")");
        }
        for (var str : unit.strings()) lines.add("(string " + p.symbol(str.symbol()) + ")");
        for (var f : unit.functions()) lines.add(p.function(f));
        return String.join("\n", lines);
    }

    private String symbol(org.jbm.cc.sema.Symbol s) {
        return s.name + ":" + s.type().spelling();
    }

    // A scalar's initializer prints as its value; an aggregate's as the item list.
    private String init(TInit i, org.jbm.cc.types.CType type) {
        if (type.isScalar() && i.items().size() == 1) return i.items().get(0).value().accept(this);
        var sb = new StringBuilder("(init");
        for (var item : i.items()) sb.append(" (").append(item.offset()).append(' ').append(item.value().accept(this)).append(')');
        return sb.append(')').toString();
    }

    private String function(TFunction f) {
        var sb = new StringBuilder("(function ").append(symbol(f.symbol()));
        sb.append(" (params");
        for (var p : f.parameters()) sb.append(' ').append(symbol(p));
        sb.append(") (locals");
        for (var l : f.locals()) sb.append(' ').append(symbol(l));
        return sb.append(") ").append(f.body().accept(this)).append(')').toString();
    }

    private String stmts(String name, List<TStmt> items) {
        var sb = new StringBuilder("(").append(name);
        for (TStmt s : items) sb.append(' ').append(s.accept(this));
        return sb.append(')').toString();
    }

    // ---- statements ----------------------------------------------------------------------

    @Override
    public String visit(TStmt.Block s) {
        return stmts("block", s.items());
    }

    @Override
    public String visit(TStmt.ExprStmt s) {
        return "(expr " + s.expr().accept(this) + ")";
    }

    @Override
    public String visit(TStmt.LocalDecl s) {
        return "(local " + symbol(s.symbol()) + s.init().map(i -> " " + init(i, s.symbol().type())).orElse("") + ")";
    }

    @Override
    public String visit(TStmt.If s) {
        return "(if " + s.condition().accept(this) + " " + s.thenBranch().accept(this)
                + s.elseBranch().map(e -> " " + e.accept(this)).orElse("") + ")";
    }

    @Override
    public String visit(TStmt.While s) {
        return "(while " + s.condition().accept(this) + " " + s.body().accept(this) + ")";
    }

    @Override
    public String visit(TStmt.DoWhile s) {
        return "(do " + s.body().accept(this) + " " + s.condition().accept(this) + ")";
    }

    @Override
    public String visit(TStmt.For s) {
        return "(for " + stmts("init", s.init()) + " " + s.condition().map(c -> c.accept(this)).orElse("_") + " "
                + s.step().map(c -> c.accept(this)).orElse("_") + " " + s.body().accept(this) + ")";
    }

    @Override
    public String visit(TStmt.Switch s) {
        var sb = new StringBuilder("(switch ").append(s.value().accept(this)).append(" (cases");
        for (var c : s.cases()) {
            if (c instanceof TStmt.Case single) sb.append(' ').append(single.value());
            else if (c instanceof TStmt.CaseRange range) sb.append(' ').append(range.low()).append("...").append(range.high());
        }
        s.defaultTarget().ifPresent(d -> sb.append(" default"));
        return sb.append(") ").append(s.body().accept(this)).append(')').toString();
    }

    @Override
    public String visit(TStmt.Labeled s) {
        return "(label " + s.target().name + s.body().map(b -> " " + b.accept(this)).orElse("") + ")";
    }

    @Override
    public String visit(TStmt.Goto s) {
        return "(goto " + s.target().name + ")";
    }

    @Override
    public String visit(TStmt.Break s) {
        return "(break " + s.target().name + ")";
    }

    @Override
    public String visit(TStmt.Continue s) {
        return "(continue " + s.target().name + ")";
    }

    @Override
    public String visit(TStmt.Return s) {
        return "(return" + s.value().map(v -> " " + v.accept(this)).orElse("") + ")";
    }

    // ---- expressions ----------------------------------------------------------------------

    private String node(String name, TExpr e, TExpr... children) {
        var sb = new StringBuilder("(").append(name).append(':').append(e.type().spelling());
        for (TExpr c : children) sb.append(' ').append(c.accept(this));
        return sb.append(')').toString();
    }

    @Override
    public String visit(TExpr.VarRef e) {
        return e.symbol().name + ":" + e.type().spelling();
    }

    @Override
    public String visit(TExpr.FuncRef e) {
        return e.symbol().name + ":" + e.type().spelling();
    }

    @Override
    public String visit(TExpr.IntConst e) {
        return e.value() + ":" + e.type().spelling();
    }

    @Override
    public String visit(TExpr.FloatConst e) {
        return e.value() + ":" + e.type().spelling();
    }

    @Override
    public String visit(TExpr.IntToFloat e) {
        return node("int-to-float", e, e.operand());
    }

    @Override
    public String visit(TExpr.FloatToInt e) {
        return node("float-to-int", e, e.operand());
    }

    @Override
    public String visit(TExpr.FloatToFloat e) {
        return node("float-to-float", e, e.operand());
    }

    @Override
    public String visit(TExpr.LvalueToRvalue e) {
        return node("rv", e, e.operand());
    }

    @Override
    public String visit(TExpr.ArrayDecay e) {
        return node("decay", e, e.operand());
    }

    @Override
    public String visit(TExpr.FunctionDecay e) {
        return node("fdecay", e, e.operand());
    }

    @Override
    public String visit(TExpr.IntToInt e) {
        return node("int-to-int", e, e.operand());
    }

    @Override
    public String visit(TExpr.Add e) {
        return node("add", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Sub e) {
        return node("sub", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Mul e) {
        return node("mul", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Div e) {
        return node("div", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.ToBool e) {
        return node("to-bool", e, e.operand());
    }

    @Override
    public String visit(TExpr.ToVoid e) {
        return node("to-void", e, e.operand());
    }

    @Override
    public String visit(TExpr.Rem e) {
        return node("rem", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.BitAnd e) {
        return node("bitand", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.BitOr e) {
        return node("bitor", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.BitXor e) {
        return node("bitxor", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Shl e) {
        return node("shl", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Shr e) {
        return node("shr", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Eq e) {
        return node("eq", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Ne e) {
        return node("ne", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Lt e) {
        return node("lt", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Le e) {
        return node("le", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Gt e) {
        return node("gt", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Ge e) {
        return node("ge", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.And e) {
        return node("and", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Or e) {
        return node("or", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Neg e) {
        return node("neg", e, e.operand());
    }

    @Override
    public String visit(TExpr.BitNot e) {
        return node("bitnot", e, e.operand());
    }

    @Override
    public String visit(TExpr.Not e) {
        return node("not", e, e.operand());
    }

    @Override
    public String visit(TExpr.Cond e) {
        return node("cond", e, e.condition(), e.thenValue(), e.elseValue());
    }

    @Override
    public String visit(TExpr.Comma e) {
        return node("comma", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Deref e) {
        return node("deref", e, e.pointer());
    }

    @Override
    public String visit(TExpr.Member e) {
        String bits = e.member().bits().map(b -> ":" + b.bitOffset() + "/" + b.width()).orElse("");
        return "(member:" + e.type().spelling() + " " + e.base().accept(this) + " " + e.member().name() + bits + ")";
    }

    @Override
    public String visit(TExpr.Materialize e) {
        return node("materialize", e, e.value());
    }

    @Override
    public String visit(TExpr.FuncDeref e) {
        return node("fderef", e, e.pointer());
    }

    @Override
    public String visit(TExpr.AddrConst e) {
        String value = e.base().map(s -> "&" + s.name + (e.offset() != 0 ? "+" + e.offset() : ""))
                .orElse(e.isNull() ? "null" : Long.toString(e.offset()));
        return value + ":" + e.type().spelling();
    }

    @Override
    public String visit(TExpr.NullptrConst e) {
        return "nullptr:" + e.type().spelling();
    }

    @Override
    public String visit(TExpr.PtrToPtr e) {
        return node("ptr-to-ptr", e, e.operand());
    }

    @Override
    public String visit(TExpr.IntToPtr e) {
        return node("int-to-ptr", e, e.operand());
    }

    @Override
    public String visit(TExpr.PtrToInt e) {
        return node("ptr-to-int", e, e.operand());
    }

    @Override
    public String visit(TExpr.NullToPtr e) {
        return node("null", e, e.operand());
    }

    @Override
    public String visit(TExpr.AddrOf e) {
        return node("addr", e, e.operand());
    }

    @Override
    public String visit(TExpr.PtrAdd e) {
        return node("ptradd", e, e.pointer(), e.index());
    }

    @Override
    public String visit(TExpr.PtrDiff e) {
        return node("ptrdiff", e, e.left(), e.right());
    }

    @Override
    public String visit(TExpr.Call e) {
        var sb = new StringBuilder("(call:").append(e.type().spelling()).append(' ').append(e.callee().accept(this));
        for (TExpr a : e.arguments()) sb.append(' ').append(a.accept(this));
        return sb.append(')').toString();
    }

    @Override
    public String visit(TExpr.Assign e) {
        return node("assign", e, e.target(), e.value());
    }

    @Override
    public String visit(TExpr.CompoundAssign e) {
        return node("compound-assign", e, e.target(), e.newValue());
    }

    @Override
    public String visit(TExpr.PostfixAssign e) {
        return node("postfix-assign", e, e.target(), e.newValue());
    }

    @Override
    public String visit(TExpr.TargetValue e) {
        return "(target:" + e.type().spelling() + ")";
    }
}

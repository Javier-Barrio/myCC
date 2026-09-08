package org.jbm.cc.tast;

import lombok.NonNull;

/**
 * S-expression dump of a typed tree, the test oracle: every node prints
 * as {@code (name:type children...)}, references as {@code name:type},
 * constants as {@code value:type}. The node name is the record's, so the
 * conversion the typer inserted is visible in the expectation:
 * {@code (add:int (rv:int a:int) (int-to-int:int (rv:char b:char)))}.
 */
public final class TypedPrinter implements TVisitor<String> {

    private TypedPrinter() {
    }

    public static String print(@NonNull TExpr e) {
        return e.accept(new TypedPrinter());
    }

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
    public String visit(TExpr.FuncDeref e) {
        return node("fderef", e, e.pointer());
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

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
}

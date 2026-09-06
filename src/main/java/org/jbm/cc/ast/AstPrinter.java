package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the AST as compact S-expressions, e.g. {@code (+ a (* b c))} or
 * {@code (decl (x (ptr int)))}. This is what the parser tests compare
 * against and the quickest way to eyeball a parse.
 * <p>
 * Conventions: expressions print operator-first; types print as
 * {@code int}, {@code (ptr T)}, {@code (array T n)}, {@code (fn RET (params))},
 * {@code (struct S (member T) ...)}, with qualifiers wrapped as
 * {@code (const T)}; where an operand may be either an expression or a
 * type ({@code sizeof}, {@code _Generic}, {@code typeof}) the type is
 * wrapped as {@code (type T)}. Absent optional parts print as {@code _}.
 */
public final class AstPrinter implements Visitor<String> {

    private static final AstPrinter INSTANCE = new AstPrinter();

    private AstPrinter() {
    }

    // ---- entry points ------------------------------------------------------

    public static String print(List<? extends Decl> translationUnit) {
        return translationUnit.stream().map(AstPrinter::print).collect(Collectors.joining("\n"));
    }

    public static String print(BlockItem item) {
        if (item instanceof Decl d) return print(d);
        return print((Stmt) item);
    }

    public static String print(Decl d) {
        return d == null ? "_" : d.accept(INSTANCE);
    }

    public static String print(Stmt s) {
        return s == null ? "_" : s.accept(INSTANCE);
    }

    public static String print(Expr e) {
        return e == null ? "_" : e.accept(INSTANCE);
    }

    public static String print(Type t) {
        if (t == null) return "_";
        String core = t.accept(INSTANCE);
        var q = t.quals();
        if (q.isEmpty()) return core;
        var sb = new StringBuilder("(");
        if (q.isConst()) sb.append("const ");
        if (q.isVolatile()) sb.append("volatile ");
        if (q.isRestrict()) sb.append("restrict ");
        if (q.isAtomic()) sb.append("_Atomic ");
        return sb.append(core).append(')').toString();
    }

    public static String print(Initializer init) {
        return init == null ? "_" : init.accept(INSTANCE);
    }

    // ---- expressions ----------------------------------------------------

    @Override
    public String visit(Expr.Identifier e) {
        return e.name().text;
    }

    @Override
    public String visit(Expr.Literal e) {
        return e.token().text;
    }

    @Override
    public String visit(Expr.StringLiteral e) {
        return texts(e.parts());
    }

    @Override
    public String visit(Expr.Generic e) {
        var sb = new StringBuilder("(_Generic ");
        sb.append(e.controllingType() != null ? typeOperand(e.controllingType()) : print(e.controllingExpr()));
        for (var a : e.associations()) {
            sb.append(" (").append(a.type() == null ? "default" : print(a.type()))
                    .append(' ').append(print(a.expr())).append(')');
        }
        return sb.append(')').toString();
    }

    @Override
    public String visit(Expr.Index e) {
        return list("[]", print(e.array()), print(e.index()));
    }

    @Override
    public String visit(Expr.Call e) {
        var sb = new StringBuilder("(call ").append(print(e.callee()));
        for (var a : e.arguments()) sb.append(' ').append(print(a));
        return sb.append(')').toString();
    }

    @Override
    public String visit(Expr.Member e) {
        return list(e.op().text, print(e.object()), e.name().text);
    }

    @Override
    public String visit(Expr.Postfix e) {
        return list("post" + e.op().text, print(e.operand()));
    }

    @Override
    public String visit(Expr.CompoundLiteral e) {
        var sb = new StringBuilder("(compound ");
        for (var s : e.storageClasses()) sb.append(s.text).append(' ');
        return sb.append(print(e.type())).append(' ').append(print(e.initializer())).append(')').toString();
    }

    @Override
    public String visit(Expr.Unary e) {
        return list(e.op().text, print(e.operand()));
    }

    @Override
    public String visit(Expr.TypeOperator e) {
        return list(e.op().text, typeOperand(e.type()));
    }

    @Override
    public String visit(Expr.StaticAssertion e) {
        return e.message() == null
                ? list("static_assert", print(e.condition()))
                : list("static_assert", print(e.condition()), print(e.message()));
    }

    @Override
    public String visit(Expr.Cast e) {
        return list("cast", print(e.type()), print(e.operand()));
    }

    @Override
    public String visit(Expr.Binary e) {
        return list(e.op().text, print(e.left()), print(e.right()));
    }

    @Override
    public String visit(Expr.Conditional e) {
        return list("?:", print(e.condition()), print(e.thenExpr()), print(e.elseExpr()));
    }

    @Override
    public String visit(Expr.Assign e) {
        return list(e.op().text, print(e.target()), print(e.value()));
    }

    @Override
    public String visit(Expr.Comma e) {
        return list(",", print(e.left()), print(e.right()));
    }

    private static String typeOperand(Type t) {
        return list("type", print(t));
    }

    // ---- types (unqualified; print(Type) adds the qualifier wrapper) -------

    @Override
    public String visit(Type.Basic t) {
        return (t.isComplex() ? "_Complex " : "") + t.kind().spelling;
    }

    @Override
    public String visit(Type.BitInt t) {
        return list((t.isUnsigned() ? "unsigned " : "") + "_BitInt", print(t.width()));
    }

    @Override
    public String visit(Type.Pointer t) {
        return list("ptr", print(t.target()));
    }

    @Override
    public String visit(Type.Array t) {
        var sb = new StringBuilder("(array ");
        if (t.isStatic()) sb.append("static ");
        sb.append(print(t.element()));
        if (t.isStar()) sb.append(" *");
        else if (t.size() != null) sb.append(' ').append(print(t.size()));
        return sb.append(')').toString();
    }

    @Override
    public String visit(Type.Function t) {
        var sb = new StringBuilder("(fn ").append(print(t.returnType())).append(" (");
        sb.append(t.parameters().stream().map(AstPrinter::parameter).collect(Collectors.joining(" ")));
        sb.append(')');
        if (t.isVariadic()) sb.append(" ...");
        return sb.append(')').toString();
    }

    @Override
    public String visit(Type.Struct t) {
        var sb = new StringBuilder("(").append(t.keyword().text);
        if (t.tag() != null) sb.append(' ').append(t.tag().text);
        if (t.members() != null) {
            for (var m : t.members()) sb.append(' ').append(member(m));
        }
        return sb.append(')').toString();
    }

    @Override
    public String visit(Type.Enum t) {
        var sb = new StringBuilder("(enum");
        if (t.tag() != null) sb.append(' ').append(t.tag().text);
        if (t.underlying() != null) sb.append(" : ").append(print(t.underlying()));
        if (t.enumerators() != null) {
            for (var en : t.enumerators()) {
                sb.append(" (").append(en.name().text);
                if (en.value() != null) sb.append(' ').append(print(en.value()));
                sb.append(')');
            }
        }
        return sb.append(')').toString();
    }

    @Override
    public String visit(Type.TypedefName t) {
        return t.name().text;
    }

    @Override
    public String visit(Type.Typeof t) {
        return list(t.keyword().text, t.type() != null ? typeOperand(t.type()) : print(t.expr()));
    }

    private static String parameter(Type.Parameter p) {
        var sb = new StringBuilder("(");
        for (var s : p.storageClasses()) sb.append(s.text).append(' ');
        if (p.name() != null) sb.append(p.name().text).append(' ');
        return sb.append(print(p.type())).append(')').toString();
    }

    private static String member(Type.MemberDecl m) {
        if (m instanceof Expr.StaticAssertion sa) return print((Expr) sa);
        var x = (Type.Member) m;
        var sb = new StringBuilder("(");
        if (x.name() != null) sb.append(x.name().text).append(' ');
        sb.append(print(x.type()));
        if (x.bitWidth() != null) sb.append(" : ").append(print(x.bitWidth()));
        return sb.append(')').toString();
    }

    // ---- declarations ---------------------------------------------------

    @Override
    public String visit(Decl.Declaration d) {
        var sb = new StringBuilder("(decl").append(specifiers(d.specifiers()));
        if (d.declarators().isEmpty()) {
            sb.append(' ').append(print(d.specifiers().type()));
        }
        for (var id : d.declarators()) {
            sb.append(" (").append(id.name().text).append(' ').append(print(id.type()));
            if (id.initializer() != null) sb.append(' ').append(print(id.initializer()));
            sb.append(')');
        }
        return sb.append(')').toString();
    }

    @Override
    public String visit(Decl.FunctionDefinition d) {
        return "(fundef" + specifiers(d.specifiers()) + ' ' + d.name().text + ' '
                + print(d.type()) + ' ' + print(d.body()) + ')';
    }

    @Override
    public String visit(Decl.AttributeDeclaration d) {
        return list("attrs", attributes(d.attributes()));
    }

    private static String specifiers(Specifiers s) {
        var sb = new StringBuilder();
        for (var t : s.storageClasses()) sb.append(' ').append(t.text);
        for (var t : s.functionSpecifiers()) sb.append(' ').append(t.text);
        if (s.alignment() != null) {
            sb.append(" (alignas ").append(s.alignment().type() != null
                    ? typeOperand(s.alignment().type()) : print(s.alignment().expr())).append(')');
        }
        return sb.toString();
    }

    private static String attributes(List<Attribute> attrs) {
        return attrs.stream().map(a -> {
            var sb = new StringBuilder("[[");
            if (a.prefix() != null) sb.append(a.prefix().text).append("::");
            sb.append(a.name().text);
            if (a.arguments() != null) sb.append('(').append(texts(a.arguments())).append(')');
            return sb.append("]]").toString();
        }).collect(Collectors.joining(" "));
    }

    // ---- initializers ---------------------------------------------------

    @Override
    public String visit(Initializer.Expression i) {
        return print(i.expr());
    }

    @Override
    public String visit(Initializer.Braced i) {
        return "{" + i.items().stream().map(AstPrinter::item).collect(Collectors.joining(" ")) + "}";
    }

    private static String item(Initializer.Item item) {
        if (item.designators().isEmpty()) return print(item.initializer());
        var sb = new StringBuilder("(");
        for (var d : item.designators()) {
            if (d instanceof Initializer.ArrayDesignator a) sb.append('[').append(print(a.index())).append("] ");
            else sb.append('.').append(((Initializer.MemberDesignator) d).name().text).append(' ');
        }
        return sb.append(print(item.initializer())).append(')').toString();
    }

    // ---- statements -----------------------------------------------------

    @Override
    public String visit(Stmt.Labeled s) {
        String label;
        if (s.label() instanceof Stmt.NameLabel l) label = "(label " + l.name().text;
        else if (s.label() instanceof Stmt.CaseLabel l) {
            label = "(case " + print(l.low()) + (l.high() != null ? " " + print(l.high()) : "");
        } else label = "(default";
        return label + (s.body() != null ? " " + print(s.body()) : "") + ')';
    }

    @Override
    public String visit(Stmt.Compound s) {
        var sb = new StringBuilder("(block");
        for (var item : s.items()) sb.append(' ').append(print(item));
        return sb.append(')').toString();
    }

    @Override
    public String visit(Stmt.ExprStmt s) {
        return s.expr() == null ? "(expr)" : list("expr", print(s.expr()));
    }

    @Override
    public String visit(Stmt.If s) {
        return s.elseBranch() == null
                ? list("if", header(s.header()), print(s.thenBranch()))
                : list("if", header(s.header()), print(s.thenBranch()), print(s.elseBranch()));
    }

    @Override
    public String visit(Stmt.Switch s) {
        return list("switch", header(s.header()), print(s.body()));
    }

    @Override
    public String visit(Stmt.While s) {
        return list("while", print(s.condition()), print(s.body()));
    }

    @Override
    public String visit(Stmt.DoWhile s) {
        return list("do", print(s.body()), print(s.condition()));
    }

    @Override
    public String visit(Stmt.For s) {
        String init = s.initDecl() != null ? print(s.initDecl()) : print(s.initExpr());
        return list("for", init, print(s.condition()), print(s.step()), print(s.body()));
    }

    @Override
    public String visit(Stmt.Goto s) {
        return list("goto", s.label().text);
    }

    @Override
    public String visit(Stmt.Continue s) {
        return s.label() == null ? "(continue)" : list("continue", s.label().text);
    }

    @Override
    public String visit(Stmt.Break s) {
        return s.label() == null ? "(break)" : list("break", s.label().text);
    }

    @Override
    public String visit(Stmt.Return s) {
        return s.value() == null ? "(return)" : list("return", print(s.value()));
    }

    private static String header(Stmt.Header h) {
        if (h.declaration() == null) return print(h.condition());
        return h.condition() == null
                ? list("header", print(h.declaration()))
                : list("header", print(h.declaration()), print(h.condition()));
    }

    // ---- helpers --------------------------------------------------------

    private static String list(String head, String... items) {
        return "(" + head + " " + String.join(" ", items) + ")";
    }

    private static String texts(List<Token> tokens) {
        return tokens.stream().map(t -> t.text).collect(Collectors.joining(" "));
    }
}

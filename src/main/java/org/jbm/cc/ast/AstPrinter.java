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
public final class AstPrinter {

    private AstPrinter() {
    }

    public static String print(List<? extends Decl> translationUnit) {
        return translationUnit.stream().map(AstPrinter::print).collect(Collectors.joining("\n"));
    }

    public static String print(BlockItem item) {
        if (item instanceof Decl d) return print(d);
        return print((Stmt) item);
    }

    // ---- expressions ----------------------------------------------------

    public static String print(Expr e) {
        if (e == null) return "_";
        if (e instanceof Expr.Identifier x) return x.name().text;
        if (e instanceof Expr.Literal x) return x.token().text;
        if (e instanceof Expr.StringLiteral x) return texts(x.parts());
        if (e instanceof Expr.Generic x) {
            var sb = new StringBuilder("(_Generic ");
            sb.append(x.controllingType() != null ? typeOperand(x.controllingType()) : print(x.controllingExpr()));
            for (var a : x.associations()) {
                sb.append(" (").append(a.type() == null ? "default" : print(a.type()))
                        .append(' ').append(print(a.expr())).append(')');
            }
            return sb.append(')').toString();
        }
        if (e instanceof Expr.Index x) return list("[]", print(x.array()), print(x.index()));
        if (e instanceof Expr.Call x) {
            var sb = new StringBuilder("(call ").append(print(x.callee()));
            for (var a : x.arguments()) sb.append(' ').append(print(a));
            return sb.append(')').toString();
        }
        if (e instanceof Expr.Member x) return list(x.op().text, print(x.object()), x.name().text);
        if (e instanceof Expr.Postfix x) return list("post" + x.op().text, print(x.operand()));
        if (e instanceof Expr.CompoundLiteral x) {
            var sb = new StringBuilder("(compound ");
            for (var s : x.storageClasses()) sb.append(s.text).append(' ');
            return sb.append(print(x.type())).append(' ').append(print(x.initializer())).append(')').toString();
        }
        if (e instanceof Expr.Unary x) return list(x.op().text, print(x.operand()));
        if (e instanceof Expr.TypeOperator x) return list(x.op().text, typeOperand(x.type()));
        if (e instanceof Expr.StaticAssertion x) {
            return x.message() == null
                    ? list("static_assert", print(x.condition()))
                    : list("static_assert", print(x.condition()), print(x.message()));
        }
        if (e instanceof Expr.Cast x) return list("cast", print(x.type()), print(x.operand()));
        if (e instanceof Expr.Binary x) return list(x.op().text, print(x.left()), print(x.right()));
        if (e instanceof Expr.Conditional x) {
            return list("?:", print(x.condition()), print(x.thenExpr()), print(x.elseExpr()));
        }
        if (e instanceof Expr.Assign x) return list(x.op().text, print(x.target()), print(x.value()));
        if (e instanceof Expr.Comma x) return list(",", print(x.left()), print(x.right()));
        throw new IllegalArgumentException(e.getClass().getName());
    }

    private static String typeOperand(Type t) {
        return list("type", print(t));
    }

    // ---- types ----------------------------------------------------------

    public static String print(Type t) {
        if (t == null) return "_";
        String core = printUnqualified(t);
        var q = t.quals();
        if (q.isEmpty()) return core;
        var sb = new StringBuilder("(");
        if (q.isConst()) sb.append("const ");
        if (q.isVolatile()) sb.append("volatile ");
        if (q.isRestrict()) sb.append("restrict ");
        if (q.isAtomic()) sb.append("_Atomic ");
        return sb.append(core).append(')').toString();
    }

    private static String printUnqualified(Type t) {
        if (t instanceof Type.Basic x) return (x.isComplex() ? "_Complex " : "") + x.kind().spelling;
        if (t instanceof Type.BitInt x) {
            return list((x.isUnsigned() ? "unsigned " : "") + "_BitInt", print(x.width()));
        }
        if (t instanceof Type.Pointer x) return list("ptr", print(x.target()));
        if (t instanceof Type.Array x) {
            var sb = new StringBuilder("(array ");
            if (x.isStatic()) sb.append("static ");
            sb.append(print(x.element()));
            if (x.isStar()) sb.append(" *");
            else if (x.size() != null) sb.append(' ').append(print(x.size()));
            return sb.append(')').toString();
        }
        if (t instanceof Type.Function x) {
            var sb = new StringBuilder("(fn ").append(print(x.returnType())).append(" (");
            sb.append(x.parameters().stream().map(AstPrinter::print).collect(Collectors.joining(" ")));
            sb.append(')');
            if (x.isVariadic()) sb.append(" ...");
            return sb.append(')').toString();
        }
        if (t instanceof Type.Struct x) {
            var sb = new StringBuilder("(").append(x.keyword().text);
            if (x.tag() != null) sb.append(' ').append(x.tag().text);
            if (x.members() != null) {
                for (var m : x.members()) sb.append(' ').append(print(m));
            }
            return sb.append(')').toString();
        }
        if (t instanceof Type.Enum x) {
            var sb = new StringBuilder("(enum");
            if (x.tag() != null) sb.append(' ').append(x.tag().text);
            if (x.underlying() != null) sb.append(" : ").append(print(x.underlying()));
            if (x.enumerators() != null) {
                for (var en : x.enumerators()) {
                    sb.append(" (").append(en.name().text);
                    if (en.value() != null) sb.append(' ').append(print(en.value()));
                    sb.append(')');
                }
            }
            return sb.append(')').toString();
        }
        if (t instanceof Type.TypedefName x) return x.name().text;
        if (t instanceof Type.Typeof x) {
            return list(x.keyword().text, x.type() != null ? typeOperand(x.type()) : print(x.expr()));
        }
        throw new IllegalArgumentException(t.getClass().getName());
    }

    private static String print(Type.Parameter p) {
        var sb = new StringBuilder("(");
        for (var s : p.storageClasses()) sb.append(s.text).append(' ');
        if (p.name() != null) sb.append(p.name().text).append(' ');
        return sb.append(print(p.type())).append(')').toString();
    }

    private static String print(Type.MemberDecl m) {
        if (m instanceof Expr.StaticAssertion sa) return print((Expr) sa);
        var x = (Type.Member) m;
        var sb = new StringBuilder("(");
        if (x.name() != null) sb.append(x.name().text).append(' ');
        sb.append(print(x.type()));
        if (x.bitWidth() != null) sb.append(" : ").append(print(x.bitWidth()));
        return sb.append(')').toString();
    }

    // ---- declarations ---------------------------------------------------

    public static String print(Decl d) {
        if (d instanceof Expr.StaticAssertion sa) return print((Expr) sa);
        if (d instanceof Decl.AttributeDeclaration x) return list("attrs", attributes(x.attributes()));
        if (d instanceof Decl.Declaration x) {
            var sb = new StringBuilder("(decl").append(specifiers(x.specifiers()));
            if (x.declarators().isEmpty()) {
                sb.append(' ').append(print(x.specifiers().type()));
            }
            for (var id : x.declarators()) {
                sb.append(" (").append(id.name().text).append(' ').append(print(id.type()));
                if (id.initializer() != null) sb.append(' ').append(print(id.initializer()));
                sb.append(')');
            }
            return sb.append(')').toString();
        }
        if (d instanceof Decl.FunctionDefinition x) {
            return "(fundef" + specifiers(x.specifiers()) + ' ' + x.name().text + ' '
                    + print(x.type()) + ' ' + print(x.body()) + ')';
        }
        throw new IllegalArgumentException(d.getClass().getName());
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

    public static String print(Initializer init) {
        if (init == null) return "_";
        if (init instanceof Initializer.Expression x) return print(x.expr());
        var braced = (Initializer.Braced) init;
        return "{" + braced.items().stream().map(AstPrinter::print).collect(Collectors.joining(" ")) + "}";
    }

    private static String print(Initializer.Item item) {
        if (item.designators().isEmpty()) return print(item.initializer());
        var sb = new StringBuilder("(");
        for (var d : item.designators()) {
            if (d instanceof Initializer.ArrayDesignator a) sb.append('[').append(print(a.index())).append("] ");
            else sb.append('.').append(((Initializer.MemberDesignator) d).name().text).append(' ');
        }
        return sb.append(print(item.initializer())).append(')').toString();
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

    // ---- statements -----------------------------------------------------

    public static String print(Stmt s) {
        if (s == null) return "_";
        if (s instanceof Stmt.Labeled x) {
            String label;
            if (x.label() instanceof Stmt.NameLabel l) label = "(label " + l.name().text;
            else if (x.label() instanceof Stmt.CaseLabel l) {
                label = "(case " + print(l.low()) + (l.high() != null ? " " + print(l.high()) : "");
            } else label = "(default";
            return label + (x.body() != null ? " " + print(x.body()) : "") + ')';
        }
        if (s instanceof Stmt.Compound x) {
            var sb = new StringBuilder("(block");
            for (var item : x.items()) sb.append(' ').append(print(item));
            return sb.append(')').toString();
        }
        if (s instanceof Stmt.ExprStmt x) return x.expr() == null ? "(expr)" : list("expr", print(x.expr()));
        if (s instanceof Stmt.If x) {
            return x.elseBranch() == null
                    ? list("if", print(x.header()), print(x.thenBranch()))
                    : list("if", print(x.header()), print(x.thenBranch()), print(x.elseBranch()));
        }
        if (s instanceof Stmt.Switch x) return list("switch", print(x.header()), print(x.body()));
        if (s instanceof Stmt.While x) return list("while", print(x.condition()), print(x.body()));
        if (s instanceof Stmt.DoWhile x) return list("do", print(x.body()), print(x.condition()));
        if (s instanceof Stmt.For x) {
            String init = x.initDecl() != null ? print(x.initDecl()) : print(x.initExpr());
            return list("for", init, print(x.condition()), print(x.step()), print(x.body()));
        }
        if (s instanceof Stmt.Goto x) return list("goto", x.label().text);
        if (s instanceof Stmt.Continue x) return x.label() == null ? "(continue)" : list("continue", x.label().text);
        if (s instanceof Stmt.Break x) return x.label() == null ? "(break)" : list("break", x.label().text);
        if (s instanceof Stmt.Return x) return x.value() == null ? "(return)" : list("return", print(x.value()));
        throw new IllegalArgumentException(s.getClass().getName());
    }

    private static String print(Stmt.Header h) {
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

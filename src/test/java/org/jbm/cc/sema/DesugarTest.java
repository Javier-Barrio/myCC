package org.jbm.cc.sema;

import org.jbm.mycc.Main;
import org.jbm.mycc.cc.parse.ast.AstPrinter;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.parse.ast.Stmt;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.Scanner;
import org.jbm.mycc.cc.cpp.TokenConversion;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.Expr;
import org.jbm.mycc.cc.parse.ast.Initializer;
import org.jbm.mycc.cc.sema.Desugar;
import org.jbm.mycc.cc.sema.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class DesugarTest {

    private static List<Decl> parse(String source) {
        return Parser.parse(TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source))));
    }

    private static String desugared(String source) {
        return AstPrinter.print(Desugar.desugar(parse(source)));
    }

    /** Desugars `void f(void) { body }` and returns the body's items. */
    private static String body(String statements) {
        String out = desugared("void f(void) {\n" + statements + "\n}");
        String prefix = "(fundef f (fn void ()) (block ";
        return out.substring(prefix.length(), out.length() - 2);
    }

    @Test
    void prefixIncrementBecomesCompoundAssignmentEverywhere() {
        assertEquals("(decl (x int (+= i 1)))", desugared("int x = ++i;"));
        assertEquals("(decl (x int (-= (-> p n) 1)))", desugared("int x = --p->n;"));
        assertEquals("(decl (x int (call f (+= i 1))))", desugared("int x = f(++i);"));
        assertEquals("(decl (x int (* (+= i 1) 2)))", desugared("int x = ++i * 2;"));
        // The operand itself is rewritten first.
        assertEquals("(decl (x int (+= ([] a (+= j 1)) 1)))", desugared("int x = ++a[++j];"));
    }

    @Test
    void postfixIncrementIsRewrittenOnlyWhereItsValueIsDiscarded() {
        assertEquals("(expr (+= i 1)) (expr (-= j 1))", body("i++; j--;"));
        assertEquals("(for (= i 0) (< i n) (+= i 1) (block))", body("for (i = 0; i < n; i++) {}"));
        assertEquals("(for (+= i 1) _ (, (+= i 1) (-= j 1)) (block))", body("for (i++;; i++, j--) {}"));
        // In an expression statement both comma operands are discarded.
        assertEquals("(expr (, (+= i 1) (+= j 1)))", body("i++, j++;"));
        assertEquals("(expr (?: c (+= i 1) (-= j 1)))", body("c ? i++ : j--;"));
        // Value used: left alone.
        assertEquals("(expr (= x (post++ i)))", body("x = i++;"));
        assertEquals("(expr (call f (post++ i)))", body("f(i++);"));
        assertEquals("(return (post-- i))", body("return i--;"));
        assertEquals("(while (post++ i) (expr))", body("while (i++) ;"));
        assertEquals("(expr (= x (, (+= i 1) (post++ j))))", body("x = (i++, j++);"));
    }

    @Test
    void commaOperandsInValueContextsStillDiscardTheirLeftValue() {
        assertEquals("(decl (x int (, (+= i 1) (post++ j))))", desugared("int x = (i++, j++);"));
    }

    @Test
    void mixedPrefixAndPostfix() {
        assertEquals("(expr (+= (* (post++ p)) 1))", body("++*p++;"));
        assertEquals("(expr (+= (* (post++ p)) 1))", body("(*p++)++;"));
    }

    @Test
    void unchangedTreesKeepTheirIdentity() {
        var unit = parse("int a; struct S { int m; } s = {1}, t; void f(int n) { for (;;) n = n + 1; }");
        var result = Desugar.desugar(unit);
        for (int i = 0; i < unit.size(); i++) assertSame(unit.get(i), result.get(i));
    }

    @Test
    void sharedSpecifierNodesStaySharedAfterARewrite() {
        // The struct node is shared by the specifiers and both declarators;
        // s's initializer changes, so the declaration is rebuilt - the
        // struct must still be one node, or the resolver would declare its
        // enumerators twice.
        var result = Desugar.desugar(parse("int i; struct S { enum { Q } q; } s = {++i}, t;"));
        var decl = (Decl.Declaration) result.get(1);
        assertSame(decl.specifiers().type().orElseThrow(), decl.declarators().get(0).type().orElseThrow());
        assertSame(decl.specifiers().type().orElseThrow(), decl.declarators().get(1).type().orElseThrow());
        assertEquals("(decl (i int))\n(decl (s (struct S (q (enum (Q)))) {(+= i 1)}) (t (struct S (q (enum (Q))))))",
                AstPrinter.print(result));
        Resolver.resolve(result);
    }

    @Test
    void rewrittenStatementsAreNewNodesTheRestIsReused() {
        var unit = parse("void f(int i) { i++; i = 2; }");
        var result = Desugar.desugar(unit);
        var before = ((Decl.FunctionDefinition) unit.get(0)).body().items();
        var after = ((Decl.FunctionDefinition) result.get(0)).body().items();
        assertNotSame(before.get(0), after.get(0));
        assertSame(before.get(1), after.get(1));
        assertSame(((Stmt.ExprStmt) before.get(0)).token(), ((Stmt.ExprStmt) after.get(0)).token());
    }

    @Test
    void synthesizedTokensPointAtTheOperator() {
        var result = Desugar.desugar(parse("int x = ++i;"));
        var assign = (Expr.Assign) ((Initializer.Expression)
                ((Decl.Declaration) result.get(0)).declarators().get(0).initializer().orElseThrow()).expr();
        assertEquals("+=", assign.op().text);
        assertEquals(9, assign.op().column);
        assertEquals("1", ((Expr.Literal) assign.value()).token().text);
    }

    @Test
    void desugaredTreeStillResolves() {
        var result = Desugar.desugar(parse(Main.SOURCE + "\nvoid g(void) { for (int k = 0; k < 3; k++) ++values[k]; }"));
        var bindings = Resolver.resolve(result);
        assertEquals(List.of("values", "size_str", "get_count", "main", "g"),
                bindings.fileScope.stream().map(s -> s.name).toList());
    }
}

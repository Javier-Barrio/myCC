package org.jbm.cc;

import org.jbm.Main;
import org.jbm.cc.parse.ast.AstPrinter;
import org.jbm.cc.parse.ast.AstWalker;
import org.jbm.cc.parse.ast.Decl;
import org.jbm.cc.parse.ast.Expr;
import org.jbm.cc.parse.ast.Stmt;
import org.jbm.cc.parse.ast.Type;
import org.jbm.cc.cpp.BundledHeaders;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.sema.Desugar;
import org.jbm.cc.sema.Resolver;
import org.jbm.cc.sema.Typer;
import org.jbm.cc.sema.tast.TypedPrinter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source text all the way through the pipeline - lex, macro expansion,
 * phase 7, parse - and into an {@link AstWalker}: what a pass sees when
 * the preprocessor has been at work.
 */
class PipelineTest {

    private static List<Decl> parse(String source) {
        CppTokenizer.TokenSet tokens = CppTokenizer.tokenSet(source, BundledHeaders.INSTANCE, "test.c");
        return Parser.parse(TokenConversion.convert(new Scanner().expand(tokens)));
    }

    /** Counts nodes by kind and collects a few of them. */
    private static final class Census extends AstWalker {
        final Map<String, Integer> kinds = new TreeMap<>();
        final List<Expr.Identifier> identifiers = new ArrayList<>();
        final List<Expr.StringLiteral> strings = new ArrayList<>();
        final List<Expr.Literal> literals = new ArrayList<>();

        private void count(Object node) {
            kinds.merge(node.getClass().getSimpleName(), 1, Integer::sum);
        }

        @Override
        public void walk(Expr e) {
            if (e != null) count(e);
            super.walk(e);
        }

        @Override
        public void walk(Stmt s) {
            if (s != null) count(s);
            super.walk(s);
        }

        @Override
        public void walk(Decl d) {
            if (d != null) count(d);
            super.walk(d);
        }

        @Override
        public void walk(Type t) {
            if (t != null) count(t);
            super.walk(t);
        }

        @Override
        public Void visit(Expr.Identifier e) {
            identifiers.add(e);
            return super.visit(e);
        }

        @Override
        public Void visit(Expr.StringLiteral e) {
            strings.add(e);
            return super.visit(e);
        }

        @Override
        public Void visit(Expr.Literal e) {
            literals.add(e);
            return super.visit(e);
        }

        static Census of(String source) {
            var census = new Census();
            census.walkUnit(parse(source));
            return census;
        }
    }

    @Test
    void theDriverProgramReachesTheWalkerFullyExpanded() {
        var census = Census.of(Main.SOURCE);

        assertEquals(2, census.kinds.get("FunctionDefinition"));
        assertEquals(4, census.kinds.get("Declaration"), "values, size_str, total, i");
        assertEquals(1, census.kinds.get("While"));
        assertEquals(1, census.kinds.get("If"));
        assertEquals(3, census.kinds.get("Return"));
        assertEquals(1, census.kinds.get("Conditional"), "MAX expands to ?:");
        // No macro remains: every name is a plain identifier the walker can see.
        assertTrue(census.identifiers.stream().anyMatch(i -> i.name().text.equals("get_count")),
                "GETTER(count) pasted into get_count");
        assertTrue(census.identifiers.stream().noneMatch(i -> i.name().text.equals("BUFFER_SIZE")));
        // BUFFER_SIZE became the integer constant 8 wherever it was used.
        assertEquals(3, census.literals.stream().filter(l -> l.token().text.equals("8")).count());
        // XSTR(BUFFER_SIZE) became the string "8".
        assertEquals(List.of("\"8\""), census.strings.stream().map(s -> s.parts().get(0).text).toList());
    }

    @Test
    void functionLikeMacrosProduceOrdinaryExpressionNodes() {
        var census = Census.of("""
                #define SQUARE(x) ((x) * (x))
                int y = SQUARE(a + 1);
                """);
        assertEquals(3, census.kinds.get("Binary"), "one multiplication and two additions");
        // Both `a`s come from the same argument token, so they are two
        // structurally equal nodes - and still two distinct objects.
        assertEquals(2, census.identifiers.size());
        assertEquals(census.identifiers.get(0), census.identifiers.get(1));
        assertTrue(census.identifiers.get(0) != census.identifiers.get(1));
    }

    @Test
    void stringizeAndPasteReachTheWalkerAsLiteralAndIdentifier() {
        var census = Census.of("""
                #define STR(x) #x
                #define NAME(a, b) a##b
                const char *s = STR(hello world);
                int NAME(count, er) = 1;
                """);
        assertEquals("\"hello world\"", census.strings.get(0).parts().get(0).text);
        var decl = (Decl.Declaration) parse("""
                #define NAME(a, b) a##b
                int NAME(count, er) = 1;
                """).get(0);
        assertEquals("counter", decl.declarators().get(0).name().text);
    }

    @Test
    void macroExpandedDeclarationsGoThroughTheWholePipeline() {
        // A macro that generates a struct, a typedef and a function - the
        // walker, the printer and the resolver all see the expanded form.
        String source = """
                #define DEFINE_PAIR(T) typedef struct T##_pair { T first, second; } T##_pair; \\
                    T T##_sum(T##_pair p) { return p.first + p.second; }
                DEFINE_PAIR(int)
                DEFINE_PAIR(long)
                """;
        var unit = parse(source);
        assertEquals(4, unit.size());
        assertEquals(String.join("\n",
                        "(decl typedef (int_pair (struct int_pair (first int) (second int))))",
                        "(fundef int_sum (fn int ((p int_pair))) (block (return (+ (. p first) (. p second)))))",
                        "(decl typedef (long_pair (struct long_pair (first long) (second long))))",
                        "(fundef long_sum (fn long ((p long_pair))) (block (return (+ (. p first) (. p second)))))"),
                AstPrinter.print(unit));

        var census = new Census();
        census.walkUnit(unit);
        // Each struct body is one node shared by the specifiers and the
        // declarator, and the default walker walks it once per reference.
        assertEquals(4, census.kinds.get("Struct"));
        assertEquals(2, census.kinds.get("TypedefName"), "int_pair / long_pair as parameter types");

        var bindings = Resolver.resolve(unit);
        // Both p's resolve to their own function's parameter even though every
        // token in the two bodies carries the #define line's position.
        var ps = census.identifiers.stream().filter(i -> i.name().text.equals("p")).toList();
        assertEquals(4, ps.size());
        assertSame(bindings.symbolOf(ps.get(0)), bindings.symbolOf(ps.get(1)));
        assertSame(bindings.symbolOf(ps.get(2)), bindings.symbolOf(ps.get(3)));
        assertTrue(bindings.symbolOf(ps.get(0)) != bindings.symbolOf(ps.get(2)));
    }

    @Test
    void theMainProgramTypesEndToEnd() {
        var unit = Desugar.desugar(parse(Main.SOURCE));
        var typed = Typer.type(unit, Resolver.resolve(unit));
        var lines = TypedPrinter.print(typed).lines().toList();
        assertEquals("(global values:int [8])", lines.get(0));
        assertEquals("(global size_str:const char * &\"8\":const char *)", lines.get(1));
        assertEquals("(string \"8\":char [2])", lines.get(2));
        assertEquals("(function get_count:int (void) (params) (locals) (block (return 8:int)))", lines.get(3));
        String main = lines.get(4);
        assertTrue(main.startsWith("(function main:int (void) (params) (locals total:int i:int) (block (local total:int 0:int) (local i:int 0:int) "
                + "(while (to-bool:bool (lt:int (rv:int i:int) (call:int get_count:int (void)))) (block "
                + "(expr (assign:int (deref:int (ptradd:int * (decay:int * values:int [8]) (int-to-int:long (rv:int i:int)))) (mul:int (rv:int i:int) (rv:int i:int)))) "), main);
        assertTrue(main.contains("(expr (assign:int total:int (cond:int (to-bool:bool (gt:int (rv:int total:int) (rv:int (deref:int (ptradd:int * (decay:int * values:int [8]) (int-to-int:long (rv:int i:int))))))) (rv:int total:int) (rv:int (deref:int (ptradd:int * (decay:int * values:int [8]) (int-to-int:long (rv:int i:int))))))))"), main);
        assertTrue(main.endsWith("(if (to-bool:bool (gt:int (rv:int total:int) 8:int)) (block (return (rv:int total:int)))) (return 0:int)))"), main);
        assertEquals(5, lines.size());
    }

    @Test
    void undefIsAppliedBeforeTheParserSeesTheName() {
        var unit = parse("""
                #define DEBUG 0
                int a = DEBUG;
                #undef DEBUG
                int DEBUG = 2;
                """);
        assertEquals("(decl (a int 0))\n(decl (DEBUG int 2))", AstPrinter.print(unit));
        var census = new Census();
        census.walkUnit(unit);
        assertEquals(2, census.kinds.get("Declaration"));
        assertEquals(0, census.identifiers.size(), "DEBUG is a literal in one place and a declarator in the other");
    }
}

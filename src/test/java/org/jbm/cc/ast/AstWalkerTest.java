package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.parse.Parser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Visitor} dispatch and {@link AstWalker}'s default
 * traversal. Traces are the pre-order sequence of node kinds the walker
 * descends into, one simple class name per node.
 */
class AstWalkerTest {

    private static List<Decl> parse(String source) {
        return Parser.parse(TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source))));
    }

    /** Records every node the walker descends into, in pre-order. */
    private static class TracingWalker extends AstWalker {
        final List<Object> nodes = new ArrayList<>();

        List<String> trace() {
            return nodes.stream().map(n -> n.getClass().getSimpleName()).toList();
        }

        @Override
        public void walk(Decl d) {
            if (d != null) nodes.add(d);
            super.walk(d);
        }

        @Override
        public void walk(Stmt s) {
            if (s != null) nodes.add(s);
            super.walk(s);
        }

        @Override
        public void walk(Expr e) {
            if (e != null) nodes.add(e);
            super.walk(e);
        }

        @Override
        public void walk(Type t) {
            if (t != null) nodes.add(t);
            super.walk(t);
        }

        @Override
        public void walk(Initializer i) {
            if (i != null) nodes.add(i);
            super.walk(i);
        }
    }

    private static List<String> trace(String source) {
        var walker = new TracingWalker();
        walker.walkUnit(parse(source));
        return walker.trace();
    }

    // ---- dispatch ---------------------------------------------------------------

    // Exercises every node kind of every hierarchy.
    private static final String EVERYTHING = """
            typedef int T;
            struct S { int m; unsigned _BitInt(8) b; };
            enum E { A };
            [[deprecated]];
            static_assert(1, "x");
            int values[3] = {1, 2, 3};
            T g(T *p, int n);
            int f(int n) {
                typeof(n) t = (int)n;
                struct S s = (struct S){.m = 1};
                int i = 0, x = sizeof(int) + sizeof i;
                char *str = "a" "b";
                L: for (i = 0; i < n; i++) { if (i) continue; else break; }
                while (i) { i--; }
                do { switch (i) { case 1: goto L; default: ; } } while (0);
                x = _Generic(i, int: 1, default: 2), s.m = values[i] ? -x : ~x;
                return g(&t, n) + (x += 1);
            }
            """;

    @Test
    void everyNodeKindDispatchesToTheVisitMethodDeclaredForItsClass() {
        // A Visitor whose every method answers with the parameter type it was
        // declared with, so a wrong accept() would answer with another name.
        @SuppressWarnings("unchecked")
        Visitor<String> namer = (Visitor<String>) Proxy.newProxyInstance(
                Visitor.class.getClassLoader(), new Class<?>[]{Visitor.class},
                (proxy, method, args) -> method.getParameterTypes()[0].getSimpleName());

        var walker = new TracingWalker();
        walker.walkUnit(parse(EVERYTHING));
        for (Object node : walker.nodes) {
            String dispatched;
            if (node instanceof Expr e) dispatched = e.accept(namer);
            else if (node instanceof Stmt s) dispatched = s.accept(namer);
            else if (node instanceof Decl d) dispatched = d.accept(namer);
            else if (node instanceof Type t) dispatched = t.accept(namer);
            else dispatched = ((Initializer) node).accept(namer);
            assertEquals(node.getClass().getSimpleName(), dispatched);
        }
    }

    @Test
    void theSampleProgramAndTheWalkerCoverEveryNodeKind() {
        var walker = new TracingWalker();
        walker.walkUnit(parse(EVERYTHING));
        Set<String> visited = new TreeSet<>(walker.trace());

        for (Class<?> hierarchy : List.of(Expr.class, Stmt.class, Decl.class, Type.class, Initializer.class)) {
            Set<String> kinds = Arrays.stream(hierarchy.getPermittedSubclasses())
                    .map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
            kinds.removeAll(visited);
            assertTrue(kinds.isEmpty(), hierarchy.getSimpleName() + " kinds never walked: " + kinds);
        }
        // ...and the Visitor interface has exactly one method per kind.
        long kinds = List.of(Expr.class, Stmt.class, Decl.class, Type.class, Initializer.class).stream()
                .flatMap(h -> Arrays.stream(h.getPermittedSubclasses())).distinct().count();
        assertEquals(kinds, Visitor.class.getDeclaredMethods().length);
    }

    // ---- default traversal ---------------------------------------------------------

    @Test
    void defaultTraversalReachesEveryNodeInTheTree() {
        // Every AST node reachable through record components (found
        // reflectively, so a new child field cannot be forgotten here) must
        // be descended into by the walker - except the type aliased by a
        // TypedefName, which is deliberately not re-walked at each use.
        var unit = parse(EVERYTHING);
        var walker = new TracingWalker();
        walker.walkUnit(unit);
        Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        visited.addAll(walker.nodes);

        Set<Object> reachable = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Decl d : unit) collectReachable(d, reachable);

        var missed = reachable.stream().filter(n -> !visited.contains(n))
                .map(n -> n.getClass().getSimpleName()).sorted().toList();
        assertTrue(missed.isEmpty(), "nodes never walked: " + missed);
        assertTrue(reachable.size() > 100, "sample program is non-trivial: " + reachable.size());
    }

    private static boolean isNode(Object o) {
        return o instanceof Expr || o instanceof Stmt || o instanceof Decl || o instanceof Type || o instanceof Initializer;
    }

    private static void collectReachable(Object o, Set<Object> out) {
        if (o == null) return;
        if (o instanceof List<?> list) {
            for (Object item : list) collectReachable(item, out);
            return;
        }
        if (!(o instanceof Record) || !o.getClass().getPackageName().equals(Expr.class.getPackageName())) return;
        if (isNode(o) && !out.add(o)) return;
        for (var component : o.getClass().getRecordComponents()) {
            if (o instanceof Type.TypedefName && component.getName().equals("aliased")) continue;
            try {
                collectReachable(component.getAccessor().invoke(o), out);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void traversalIsPreOrderLeftToRight() {
        // The specifier type (int) is walked once for the declaration and
        // again as the base of the declarator's type: it is one shared node.
        assertEquals(List.of("Declaration", "Basic", "Basic", "Expression",
                        "Binary", "Identifier", "Binary", "Identifier", "Identifier"),
                trace("int x = a + b * c;"));
    }

    @Test
    void expressionsWalkAllOperands() {
        assertEquals(List.of("Declaration", "Basic", "Basic", "Expression",
                        "Conditional", "Call", "Identifier", "Identifier", "Index", "Identifier", "Literal",
                        "Cast", "Pointer", "Basic", "Member", "Identifier"),
                trace("int x = f(a) ? b[0] : (int *)s.m;"));
        assertEquals(List.of("Declaration", "Basic", "Basic", "Expression",
                        "Generic", "Identifier", "Basic", "Literal", "Literal"),
                trace("int x = _Generic(v, int: 1, default: 2);"));
        assertEquals(List.of("Declaration", "Basic", "Basic", "Expression",
                        "Comma", "Assign", "Identifier", "Postfix", "Identifier", "Unary", "Identifier"),
                trace("int x = (a = b++, -c);"));
    }

    @Test
    void statementsWalkHeadersBodiesAndClauses() {
        String prefix = "void f(int a, int b, int c) { ";
        var t = trace(prefix + "if (a) b; else c; }");
        assertEquals(List.of("If", "Identifier", "ExprStmt", "Identifier", "ExprStmt", "Identifier"),
                t.subList(t.indexOf("If"), t.size()));

        t = trace(prefix + "for (int i = 0; i < a; i++) { } }");
        assertEquals(List.of("For", "Declaration", "Basic", "Basic", "Expression", "Literal",
                        "Binary", "Identifier", "Identifier", "Postfix", "Identifier", "Compound"),
                t.subList(t.indexOf("For"), t.size()));

        t = trace(prefix + "if (int n = a; n) L: switch (n) { case 1 ... 2: return n; } }");
        assertEquals(List.of("If", "Declaration", "Basic", "Basic", "Expression", "Identifier", "Identifier",
                        "Labeled", "Switch", "Identifier", "Compound",
                        "Labeled", "Literal", "Literal", "Return", "Identifier"),
                t.subList(t.indexOf("If"), t.size()));
    }

    @Test
    void nullChildrenAreSkipped() {
        var t = trace("void f(void) { for (;;) ; return; }");
        assertEquals(List.of("For", "ExprStmt", "Return"), t.subList(t.indexOf("For"), t.size()));
    }

    @Test
    void typesWalkArraySizesParametersAndMembers() {
        // int (*f(int n))[n + 1]: function of n returning pointer to array
        assertEquals(List.of("Declaration", "Basic",
                        "Function", "Pointer", "Array", "Basic", "Binary", "Identifier", "Literal", "Basic"),
                trace("int (*f(int n))[n + 1];"));
        assertEquals(List.of("Declaration", "Struct", "Basic", "Literal", "Pointer", "Struct", "StaticAssertion", "Literal"),
                trace("struct S { int a : 3; struct S *next; static_assert(1); };"));
        assertEquals(List.of("Declaration", "Enum", "Basic", "Binary", "Identifier", "Literal"),
                trace("enum E : short { A, B = A + 1 };"));
        assertEquals(List.of("Declaration", "Typeof", "Pointer", "Basic", "Typeof", "Pointer", "Basic"),
                trace("typeof(int *) p;"));
    }

    @Test
    void typedefNamesAreLeaves() {
        // The second declaration's type is the typedef name; its aliased
        // struct body is not re-walked.
        var t = trace("typedef struct S { int m; } T; T x;");
        int second = t.lastIndexOf("Declaration");
        assertEquals(List.of("Declaration", "TypedefName", "TypedefName"), t.subList(second, t.size()));
    }

    @Test
    void initializersWalkDesignatorsAndNestedItems() {
        // Per item: designator expressions first, then the initializer.
        assertEquals(List.of("Declaration", "Struct", "Struct", "Braced",
                        "Expression", "Literal", "Literal", "Braced", "Expression", "Literal"),
                trace("struct P p = {.x = 1, [2] = {3}};"));
    }

    // ---- overriding ------------------------------------------------------------------

    @Test
    void anOverrideThatDoesNotCallSuperStopsTheDescent() {
        var walker = new TracingWalker() {
            @Override
            public Void visit(Expr.Binary e) {
                return null;
            }
        };
        walker.walkUnit(parse("int x = a + b;"));
        assertEquals(List.of("Declaration", "Basic", "Basic", "Expression", "Binary"), walker.trace());
    }

    @Test
    void hooksSeeTheNonSealedHelperRecords() {
        var seen = new ArrayList<String>();
        var walker = new AstWalker() {
            @Override
            protected void walkSpecifiers(Specifiers s) {
                seen.add("specifiers");
                super.walkSpecifiers(s);
            }

            @Override
            protected void walkInitDeclarator(Decl.InitDeclarator d) {
                seen.add("declarator " + d.name().text);
                super.walkInitDeclarator(d);
            }

            @Override
            protected void walkParameter(Type.Parameter p) {
                seen.add("param " + p.name().text);
                super.walkParameter(p);
            }

            @Override
            protected void walkMember(Type.MemberDecl m) {
                seen.add("member " + ((Type.Member) m).name().text);
                super.walkMember(m);
            }

            @Override
            protected void walkEnumerator(Type.Enumerator e) {
                seen.add("enumerator " + e.name().text);
                super.walkEnumerator(e);
            }

            @Override
            protected void walkLabel(Stmt.Label label) {
                seen.add("label");
                super.walkLabel(label);
            }

            @Override
            protected void walkHeader(Stmt.Header h) {
                seen.add("header");
                super.walkHeader(h);
            }

            @Override
            protected void walkItem(Initializer.Item item) {
                seen.add("item");
                super.walkItem(item);
            }
        };
        walker.walkUnit(parse("""
                struct S { int m; } s = {1};
                enum E { A };
                void f(int p) { switch (p) { case 1: ; } }
                """));
        // The struct node is shared by the specifiers and s's type, so the
        // default walker (which does not de-duplicate) meets member m twice.
        assertEquals(List.of("specifiers", "member m", "declarator s", "member m", "item",
                "specifiers", "enumerator A",
                "specifiers", "param p", "header", "label"), seen);
    }
}

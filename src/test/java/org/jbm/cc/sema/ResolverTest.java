package org.jbm.cc.sema;

import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.parse.ast.Expr;
import org.jbm.mycc.cc.parse.ast.Type;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.Scanner;
import org.jbm.mycc.cc.cpp.TokenConversion;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.Stmt;
import org.jbm.mycc.cc.sema.Bindings;
import org.jbm.mycc.cc.sema.Resolver;
import org.jbm.mycc.cc.sema.SemaException;
import org.jbm.mycc.cc.sema.Symbol;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Name resolution over the full pipeline. Identifier uses are rendered as
 * {@code name@line:col -> KIND name@line:col} (use position, then the
 * declaration it resolved to), in source order.
 */
class ResolverTest {

    private static List<Decl> parse(String source) {
        return Parser.parse(TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source))));
    }

    private static Bindings resolve(String source) {
        return Resolver.resolve(parse(source));
    }

    private static List<String> uses(Bindings b) {
        Comparator<Map.Entry<Expr.Identifier, Symbol>> byPosition =
                Comparator.comparingInt(e -> e.getKey().name().line);
        return b.identifiers.entrySet().stream()
                .sorted(byPosition.thenComparingInt(e -> e.getKey().name().column))
                .map(e -> e.getKey().name().text + "@" + e.getKey().name().line + ":" + e.getKey().name().column
                        + " -> " + e.getValue())
                .toList();
    }

    private static SemaException fails(String source) {
        return assertThrows(SemaException.class, () -> resolve(source));
    }

    // ---- scopes and shadowing -----------------------------------------------

    @Test
    void innerDeclarationsHideOuterOnes() {
        var b = resolve("""
                int x;
                void f(void) { int x; x = 1; }
                int g(void) { return x; }
                """);
        assertEquals(List.of("x@2:23 -> VARIABLE x@2:20", "x@3:22 -> VARIABLE x@1:5"), uses(b));
    }

    @Test
    void nestedBlocksAndTheirEnd() {
        var b = resolve("""
                void f(void) {
                    int a;
                    { int a; a = 1; }
                    a = 2;
                }
                """);
        assertEquals(List.of("a@3:14 -> VARIABLE a@3:11", "a@4:5 -> VARIABLE a@2:9"), uses(b));
    }

    @Test
    void undeclaredIdentifierIsAnError() {
        var e = fails("void f(void) { y = 1; }");
        assertTrue(e.getMessage().contains("undeclared identifier 'y'"), e.getMessage());
        // C23 has no implicit function declarations either.
        fails("void f(void) { g(); }");
    }

    @Test
    void redeclarationInTheSameBlockIsAnError() {
        fails("void f(void) { int a; int a; }");
        fails("void f(int a) { int a; }");
        fails("enum E { A }; void f(void) { int A; A = 1; int A; }");
    }

    @Test
    void fileScopeRedeclarationsShareOneSymbol() {
        String src = "int x; int x; int f(int); int f(int y) { return y; } int x = 3;";
        var unit = parse(src);
        var b = Resolver.resolve(unit);
        assertEquals(List.of("x", "f"), b.fileScope.stream().map(s -> s.name).toList());
        assertTrue(b.fileScope.get(0).isDefined(), "int x = 3 defines x");
        assertTrue(b.fileScope.get(1).isDefined(), "f has a body");
        assertTrue(b.fileScope.get(0) instanceof Symbol.Variable && b.fileScope.get(1) instanceof Symbol.Function);
        // Every declarator of x binds to the one symbol, as does f's prototype and definition.
        var x = b.fileScope.get(0);
        assertSame(x, b.symbolOf(((Decl.Declaration) unit.get(0)).declarators().get(0)));
        assertSame(x, b.symbolOf(((Decl.Declaration) unit.get(1)).declarators().get(0)));
        assertSame(x, b.symbolOf(((Decl.Declaration) unit.get(4)).declarators().get(0)));
        assertSame(b.fileScope.get(1), b.symbolOf((Decl.FunctionDefinition) unit.get(3)));
        fails("int x = 1; int x = 2;");
        fails("void f(void) {} void f(void) {}");
    }

    @Test
    void externRedeclarationInABlockIsAllowedNonExternIsNot() {
        resolve("extern int e; void f(void) { extern int e; e = 1; }");
        resolve("void f(void) { extern int e; { extern int e; } }");
        fails("int x; void f(void) { int x; int x; }");
    }

    @Test
    void declarationsWithLinkageDenoteOneEntity() {
        // 6.2.2p2: every declaration of a name with external linkage in
        // the unit is the same object, whatever its scope.
        var b = resolve("void f(void) { extern int e; e = 1; } int e; void g(void) { e = 2; }");
        assertEquals(1, b.fileScope.stream().filter(s -> s.name.equals("e")).count());
        assertEquals(List.of("e@1:30 -> VARIABLE e@1:27", "e@1:61 -> VARIABLE e@1:27"), uses(b));
        // 6.2.2p4: a block-scope extern denotes a visible prior declaration with linkage, internal too.
        var s = resolve("static int s; void f(void) { extern int s; s = 1; }");
        assertEquals(List.of("s@1:44 -> VARIABLE s@1:12"), uses(s));
        assertEquals(Symbol.Linkage.INTERNAL, s.fileScope.get(0).linkage());
        // A block-scope function declaration has external linkage as well.
        var h = resolve("void f(void) { int h(void); h(); } int h(void) { return 0; }");
        assertEquals(List.of("h@1:29 -> FUNCTION h@1:20"), uses(h));
        assertTrue(h.fileScope.get(1).isDefined());
        fails("void f(void) { extern int e; } long e;".replace("long e", "int e(void)"));
    }

    @Test
    void storageDurationAndLinkage() {
        var b = resolve("""
                int g; static int s; extern int e;
                static void sf(void) {}
                void f(void) { int l; static int sl; extern int be; }
                """);
        assertEquals(List.of("g:STATIC/EXTERNAL", "s:STATIC/INTERNAL", "e:STATIC/EXTERNAL",
                        "sf:function/INTERNAL", "f:function/EXTERNAL"),
                b.fileScope.stream().map(ResolverTest::describe).toList());
        var f = resolve("void f(void) { int l; static int sl; extern int be; }");
        var locals = f.declarators.values().stream()
                .sorted(Comparator.comparingInt(s -> s.declaredAt.column))
                .map(ResolverTest::describe).toList();
        assertEquals(List.of("l:AUTOMATIC/NONE", "sl:STATIC/NONE", "be:STATIC/EXTERNAL"), locals);
    }

    private static String describe(Symbol s) {
        String kind = s instanceof Symbol.Variable v ? v.storage.toString() : s.getClass().getSimpleName().toLowerCase();
        return s.name + ":" + kind + "/" + s.linkage();
    }

    @Test
    void eachEntityKindIsItsOwnSymbolClass() {
        var b = resolve("typedef int T; enum E { A }; int v; int f(int p) { return p; }");
        assertEquals(List.of("Typedef", "Enumerator", "Variable", "Function"),
                b.fileScope.stream().map(s -> s.getClass().getSimpleName()).toList());
        var a = (Symbol.Enumerator) b.fileScope.get(1);
        assertEquals("E", a.enumType.tag().orElseThrow().text);
        assertTrue(b.parameters.values().iterator().next() instanceof Symbol.Parameter);
        assertEquals(Symbol.Linkage.NONE, b.fileScope.get(0).linkage());
    }

    // ---- functions and parameters -----------------------------------------------

    @Test
    void parametersAreVisibleInTheBody() {
        var b = resolve("int f(int a, int b) { int c = a; return b + c; }");
        assertEquals(List.of("a@1:31 -> PARAMETER a@1:11", "b@1:41 -> PARAMETER b@1:18",
                "c@1:45 -> VARIABLE c@1:27"), uses(b));
    }

    @Test
    void parametersHideFileScopeNamesButOnlyInTheirFunction() {
        var b = resolve("int n; void f(int n) { n = 1; } void g(void) { n = 2; }");
        assertEquals(List.of("n@1:24 -> PARAMETER n@1:19", "n@1:48 -> VARIABLE n@1:5"), uses(b));
    }

    @Test
    void prototypeScopeLetsLaterParametersSeeEarlierOnes() {
        var b = resolve("void f(int n, int a[n]); int n;");
        assertEquals(List.of("n@1:21 -> PARAMETER n@1:12"), uses(b));
        // ...and ends at the ')'.
        fails("void f(int n); int x = n;");
    }

    @Test
    void functionCallsResolveToTheFunction() {
        var b = resolve("int get(void); int main(void) { return get(); }");
        assertEquals(List.of("get@1:40 -> FUNCTION get@1:5"), uses(b));
    }

    // ---- enumerators and typedefs --------------------------------------------------

    @Test
    void enumeratorsAreDeclaredInTheEnclosingScopeInOrder() {
        var b = resolve("enum E { A, B = A + 1 }; int x = B;");
        assertEquals(List.of("A@1:17 -> ENUMERATOR A@1:10", "B@1:34 -> ENUMERATOR B@1:13"), uses(b));
        fails("enum E { A = B, B };");
    }

    @Test
    void aTypedefNameIsNotAValue() {
        var e = fails("typedef int T; int x = T;");
        assertTrue(e.getMessage().contains("names a type"), e.getMessage());
    }

    @Test
    void typedefNamesAreBoundToTheirTypedef() {
        var b = resolve("typedef int T; T a, *b; const T c; void f(T p) { T q; }");
        var typedefs = b.typedefs.values().stream().distinct().toList();
        assertEquals(1, typedefs.size());
        assertEquals("T", typedefs.get(0).name);
        assertEquals(0, typedefs.get(0).scopeDepth);
        // One binding per typedef-name node: `T a, *b` shares one node, `const T`
        // is another, and each parameter/local spelling is its own.
        assertEquals(4, b.typedefs.size());
        for (var use : b.typedefs.keySet()) assertSame(typedefs.get(0), b.typedefOf(use));
    }

    @Test
    void typedefNamesFollowBlockScope() {
        var b = resolve("""
                typedef int T;
                void f(void) {
                    T x;
                    { typedef char T; T y; }
                    T z;
                    { T T; T = 1; }
                }
                """);
        var byLine = b.typedefs.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getKey().name().line))
                .map(e -> e.getKey().name().line + " -> " + e.getValue().declaredAt.line)
                .toList();
        assertEquals(List.of("3 -> 1", "4 -> 4", "5 -> 1", "6 -> 1"), byLine);
        assertEquals(List.of("T@6:12 -> VARIABLE T@6:9"), uses(b));
    }

    @Test
    void sharedSpecifiersAreResolvedOnce() {
        // The enum body is one node shared by every declarator (and by every
        // use of the typedef); its enumerators must not be redeclared.
        resolve("typedef enum { A } E; E e; E f; enum { B } x, y;");
        resolve("struct S { enum { Q } q, r; } a, b;");
        resolve("struct { int m; } p, q;");
    }

    // ---- tags ------------------------------------------------------------------------

    @Test
    void structTagDeclarationsReferencesAndDefinitionShareOneTag() {
        String src = "struct S; struct S *p; struct S { int a; }; struct S s;";
        var b = resolve(src);
        var tags = b.tags.values().stream().distinct().toList();
        assertEquals(1, tags.size());
        var tag = tags.get(0);
        assertTrue(tag.isComplete());
        assertEquals(4, b.tags.size(), "all four specifier nodes are bound");
        assertTrue(((Type.Struct) tag.definition().orElseThrow()).members().isPresent());
    }

    @Test
    void tagRedefinitionAndKindMismatchAreErrors() {
        fails("struct S { int a; }; struct S { int b; };");
        fails("struct S { int a; }; union S u;");
        fails("enum E { A }; struct E s;");
    }

    @Test
    void aStandaloneTagDeclarationHidesAnOuterOne() {
        var b = resolve("""
                struct S { int a; };
                void f(void) { struct S; struct S *p; struct S { int b; }; }
                struct S *q;
                """);
        assertEquals(2, b.tags.values().stream().distinct().count());
        var outer = b.tags.values().stream().filter(t -> t.scopeDepth == 0).findFirst().orElseThrow();
        var inner = b.tags.values().stream().filter(t -> t.scopeDepth == 1).findFirst().orElseThrow();
        assertNotSame(outer, inner);
        assertTrue(outer.isComplete() && inner.isComplete());
        // Without the standalone declaration, an inner `struct S *p` refers to the outer S.
        var c = resolve("struct S { int a; }; void f(void) { struct S *p; }");
        assertEquals(1, c.tags.values().stream().distinct().count());
    }

    @Test
    void anonymousStructsGetTheirOwnTag() {
        var b = resolve("struct { int a; } x; struct { int a; } y;");
        assertEquals(2, b.tags.values().stream().distinct().count());
    }

    // ---- statements: scopes, labels, jumps -----------------------------------------

    @Test
    void forAndIfHeadersScopeTheirDeclarations() {
        var b = resolve("int a, b; void f(void) { for (int i = 0; i; ) i = 0; if (int n = 1) a = n; else b = n; }");
        assertEquals(List.of(
                "i@1:42 -> VARIABLE i@1:35", "i@1:47 -> VARIABLE i@1:35",
                "a@1:69 -> VARIABLE a@1:5", "n@1:73 -> VARIABLE n@1:62",
                "b@1:81 -> VARIABLE b@1:8", "n@1:85 -> VARIABLE n@1:62"), uses(b));
        fails("void f(void) { for (int i = 0; i; ) ; i = 1; }");
        fails("void f(void) { if (int n = 1) ; n = 2; }");
    }

    @Test
    void gotoResolvesToLabelsAnywhereInTheFunction() {
        var b = resolve("void f(void) { goto end; { end: return; } }");
        assertEquals(1, b.gotos.size());
        var target = b.gotos.values().iterator().next();
        assertEquals("end", ((Stmt.NameLabel) target.label()).name().text);
        fails("void f(void) { goto nowhere; }");
        fails("void f(void) { L: ; L: ; }");
        // Labels are per function.
        resolve("void f(void) { L: ; } void g(void) { L: ; }");
        fails("void f(void) { goto L; } void g(void) { L: ; }");
    }

    @Test
    void breakAndContinueBindToTheInnermostLoopOrSwitch() {
        var b = resolve("""
                void f(int x) {
                    while (x) { switch (x) { case 1: break; default: continue; } break; }
                    do { continue; } while (x);
                }
                """);
        Comparator<Map.Entry<Stmt, Stmt>> byLine =
                Comparator.comparingInt(e -> jumpToken(e.getKey()).line);
        var targets = b.jumps.entrySet().stream()
                .sorted(byLine.thenComparingInt(e -> jumpToken(e.getKey()).column))
                .map(e -> jumpToken(e.getKey()).text + "->" + e.getValue().getClass().getSimpleName()).toList();
        assertEquals(List.of("break->Switch", "continue->While", "break->While", "continue->DoWhile"), targets);
    }

    @Test
    void labeledBreakAndContinueTargetTheNamedLoop() {
        var b = resolve("""
                void f(int x) {
                    outer: while (x) {
                        for (;;) { if (x) break outer; continue outer; }
                    }
                }
                """);
        var targets = b.jumps.values().stream().map(s -> s.getClass().getSimpleName()).distinct().toList();
        assertEquals(List.of("While"), targets);
        // Two labels on one loop, and a label in statement (not block) position.
        resolve("void f(void) { a: b: while (1) break b; if (1) c: for (;;) continue c; }");
    }

    @Test
    void jumpErrors() {
        fails("void f(void) { break; }");
        fails("void f(void) { continue; }");
        fails("void f(int x) { switch (x) { case 1: continue; } }");
        fails("void f(void) { L: { while (1) break L; } }");   // L labels a block, not the loop
        fails("void f(int x) { L: switch (x) { case 1: continue L; } }");
        fails("void f(void) { case 1: ; }");
        fails("void f(void) { default: ; }");
    }

    private static CppTokenizer.Token jumpToken(Stmt s) {
        if (s instanceof Stmt.Break br) return br.keyword();
        return ((Stmt.Continue) s).keyword();
    }

    // ---- end to end ------------------------------------------------------------------

    @Test
    void mainDriverProgramResolves() {
        var b = resolve(org.jbm.Main.SOURCE);
        assertEquals(List.of("values", "size_str", "get_count", "main"),
                b.fileScope.stream().map(s -> s.name).toList());
        var uses = uses(b);
        // Positions are those of SOURCE's text block, where line 1 is the first #define; a
        // macro-built name like GETTER(count) carries the position of its #define line.
        assertTrue(uses.contains("get_count@18:16 -> FUNCTION get_count@6:23"), uses.toString());
        assertTrue(uses.contains("values@19:9 -> VARIABLE values@8:5"), uses.toString());
        // SQUARE(i) expands to ((i) * (i)): two distinct uses sharing one token, both bound.
        assertEquals(2, uses.stream().filter(u -> u.equals("i@19:28 -> VARIABLE i@17:9")).count(), uses.toString());
        assertFalse(uses.isEmpty());
    }
}

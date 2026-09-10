package org.jbm.cc.sema.types;

import java.util.stream.Collectors;

/**
 * Spells a type in C declarator syntax: the derived parts wrap an inner
 * declarator inside-out, so a pointer to an array of functions comes out
 * as {@code int (*(*)[3])(void)} rather than a postfix chain.
 */
final class Spelling {

    private Spelling() {
    }

    static String of(CType t) {
        return spell(t, "");
    }

    // inner is the declarator built so far (empty for an abstract type).
    private static String spell(CType t, String inner) {
        if (t instanceof CType.Pointer p) {
            String q = p.quals().isEmpty() ? "" : " " + p.quals().prefix().trim();
            boolean tight = inner.isEmpty() || inner.startsWith("[") || inner.startsWith("(");
            String decl = "*" + q + (tight ? "" : " ") + inner;
            return spell(p.target(), needsParens(p.target()) ? "(" + decl + ")" : decl);
        }
        if (t instanceof CType.Array a) {
            String size = a.size().isPresent() ? Long.toString(a.size().getAsLong()) : "";
            return spell(a.element(), inner + "[" + size + "]");
        }
        if (t instanceof CType.Function f) {
            String params = f.parameters().stream().map(CType::spelling).collect(Collectors.joining(", "));
            if (f.isVariadic()) params += params.isEmpty() ? "..." : ", ...";
            if (params.isEmpty()) params = "void";
            return spell(f.returnType(), inner + "(" + params + ")");
        }
        String base = t.spelling();
        return inner.isEmpty() ? base : base + " " + inner;
    }

    // A pointer declarator binds looser than [] and (), so it is
    // parenthesized when what it points to is an array or a function.
    private static boolean needsParens(CType target) {
        return target instanceof CType.Array || target instanceof CType.Function;
    }
}

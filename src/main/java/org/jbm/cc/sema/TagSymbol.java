package org.jbm.cc.sema;

import org.jbm.cc.ast.Type;
import org.jbm.cc.cpp.CppTokenizer.Token;

/**
 * A struct, union or enum tag (C2y 6.2.3, tag namespace). Created on the
 * first mention in a scope - a definition, a forward declaration
 * {@code struct S;}, or a reference like {@code struct S *p} - and
 * completed when the body is seen. name is null for an anonymous type.
 */
public final class TagSymbol {
    public final String name;
    /** struct, union or enum. */
    public final String keyword;
    public final Token declaredAt;
    public final int scopeDepth;

    /** The Struct/Enum node carrying the body, once one has been seen. */
    public Type definition;

    TagSymbol(String name, String keyword, Token declaredAt, int scopeDepth) {
        this.name = name;
        this.keyword = keyword;
        this.declaredAt = declaredAt;
        this.scopeDepth = scopeDepth;
    }

    public boolean isComplete() {
        return definition != null;
    }

    @Override
    public String toString() {
        return keyword + " " + (name == null ? "<anonymous>" : name) + "@" + declaredAt.line + ":" + declaredAt.column;
    }
}

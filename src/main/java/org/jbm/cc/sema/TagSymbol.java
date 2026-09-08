package org.jbm.cc.sema;

import org.jbm.cc.ast.Type;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.types.CType;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A struct, union or enum tag (C2y 6.2.3, tag namespace). Created on the
 * first mention in a scope - a definition, a forward declaration
 * {@code struct S;}, or a reference like {@code struct S *p} - and
 * completed when the body is seen. name is absent for an anonymous type.
 */
public final class TagSymbol {
    public final Optional<String> name;
    /** struct, union or enum. */
    public final String keyword;
    public final Token declaredAt;
    public final int scopeDepth;

    // The Struct/Enum node carrying the body, once one has been seen.
    private @Nullable Type definition;

    // The semantic type, set by the typing pass: a record type for a
    // struct or union, the underlying integer type for an enum.
    private @Nullable CType type;

    TagSymbol(Optional<String> name, String keyword, Token declaredAt, int scopeDepth) {
        this.name = name;
        this.keyword = keyword;
        this.declaredAt = declaredAt;
        this.scopeDepth = scopeDepth;
    }

    /** The specifier node with the member or enumerator list, once seen. */
    public Optional<Type> definition() {
        return Optional.ofNullable(definition);
    }

    public boolean isComplete() {
        return definition != null;
    }

    void define(Type body) {
        definition = body;
    }

    public CType type() {
        if (type == null) throw new IllegalStateException("type of '" + this + "' has not been computed");
        return type;
    }

    public boolean hasType() {
        return type != null;
    }

    void setType(CType type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return keyword + " " + name.orElse("<anonymous>") + "@" + declaredAt.line + ":" + declaredAt.column;
    }
}

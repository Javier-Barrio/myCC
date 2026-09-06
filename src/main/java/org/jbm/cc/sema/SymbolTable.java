package org.jbm.cc.sema;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Block-structured symbol table with the ordinary and tag namespaces
 * (C2y 6.2.3). Labels are per function and members per struct, so they
 * live in the resolver and in sema's type model respectively.
 */
final class SymbolTable {

    private static final class Scope {
        final Map<String, Symbol> ordinary = new LinkedHashMap<>();
        final Map<String, TagSymbol> tags = new LinkedHashMap<>();
    }

    // Innermost scope first.
    private final Deque<Scope> scopes = new ArrayDeque<>();

    SymbolTable() {
        push();
    }

    void push() {
        scopes.push(new Scope());
    }

    void pop() {
        if (scopes.size() == 1) throw new IllegalStateException("cannot pop file scope");
        scopes.pop();
    }

    /** 0 at file scope. */
    int depth() {
        return scopes.size() - 1;
    }

    private Scope current() {
        return scopes.getFirst();
    }

    Optional<Symbol> lookup(String name) {
        for (Scope s : scopes) {
            Symbol sym = s.ordinary.get(name);
            if (sym != null) return Optional.of(sym);
        }
        return Optional.empty();
    }

    Optional<Symbol> lookupHere(String name) {
        return Optional.ofNullable(current().ordinary.get(name));
    }

    void declare(Symbol symbol) {
        current().ordinary.put(symbol.name, symbol);
    }

    Optional<TagSymbol> lookupTag(String name) {
        for (Scope s : scopes) {
            TagSymbol tag = s.tags.get(name);
            if (tag != null) return Optional.of(tag);
        }
        return Optional.empty();
    }

    Optional<TagSymbol> lookupTagHere(String name) {
        return Optional.ofNullable(current().tags.get(name));
    }

    /** Declares a named tag; anonymous tags are never looked up, so they are not entered. */
    void declareTag(TagSymbol tag) {
        current().tags.put(tag.name.orElseThrow(), tag);
    }
}

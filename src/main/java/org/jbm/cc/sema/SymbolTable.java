package org.jbm.cc.sema;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

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

    Symbol lookup(String name) {
        for (Scope s : scopes) {
            Symbol sym = s.ordinary.get(name);
            if (sym != null) return sym;
        }
        return null;
    }

    Symbol lookupHere(String name) {
        return scopes.peek().ordinary.get(name);
    }

    void declare(Symbol symbol) {
        scopes.peek().ordinary.put(symbol.name, symbol);
    }

    TagSymbol lookupTag(String name) {
        for (Scope s : scopes) {
            TagSymbol tag = s.tags.get(name);
            if (tag != null) return tag;
        }
        return null;
    }

    TagSymbol lookupTagHere(String name) {
        return scopes.peek().tags.get(name);
    }

    void declareTag(TagSymbol tag) {
        scopes.peek().tags.put(tag.name, tag);
    }
}

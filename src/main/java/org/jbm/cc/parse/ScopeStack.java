package org.jbm.cc.parse;

import org.jbm.cc.ast.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The one piece of semantic state the parser needs: which identifiers name
 * types (C2y 6.7.9 typedef-name). Ordinary identifiers are recorded too,
 * because an inner variable, function, parameter or enumerator hides an
 * outer typedef of the same name (6.2.1). One map per block scope; a null
 * value marks an ordinary identifier.
 */
public final class ScopeStack {
    private final Deque<Map<String, Type>> scopes = new ArrayDeque<>();

    public ScopeStack() {
        push();
    }

    public void push() {
        scopes.push(new HashMap<>());
    }

    public void pop() {
        if (scopes.size() == 1) throw new IllegalStateException("cannot pop file scope");
        scopes.pop();
    }

    public void declareTypedef(String name, Type type) {
        scopes.peek().put(name, type);
    }

    public void declareOrdinary(String name) {
        scopes.peek().put(name, null);
    }

    /** The type a typedef name currently denotes, or null if it is not one. */
    public Type typedefType(String name) {
        for (var scope : scopes) {
            if (scope.containsKey(name)) return scope.get(name);
        }
        return null;
    }

    public boolean isTypeName(String name) {
        return typedefType(name) != null;
    }
}

package org.jbm.mycc.cc.parse;

import lombok.NonNull;
import org.jbm.mycc.cc.parse.ast.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one piece of semantic state the parser needs: which identifiers name
 * types (C2y 6.7.9 typedef-name). Ordinary identifiers are recorded too,
 * because an inner variable, function, parameter or enumerator hides an
 * outer typedef of the same name (6.2.1). One map per block scope; an
 * ordinary identifier maps to an empty type.
 */
public final class ScopeStack {
    private final Deque<Map<String, Optional<Type>>> scopes = new ArrayDeque<>();

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

    public void declareTypedef(@NonNull String name, @NonNull Type type) {
        scopes.getFirst().put(name, Optional.of(type));
    }

    public void declareOrdinary(@NonNull String name) {
        scopes.getFirst().put(name, Optional.empty());
    }

    /** The type a typedef name currently denotes; empty if the name is not (or is no longer) one. */
    public Optional<Type> typedefType(@NonNull String name) {
        for (var scope : scopes) {
            var entry = scope.get(name);
            if (entry != null) return entry;
        }
        return Optional.empty();
    }

    public boolean isTypeName(@NonNull String name) {
        return typedefType(name).isPresent();
    }
}

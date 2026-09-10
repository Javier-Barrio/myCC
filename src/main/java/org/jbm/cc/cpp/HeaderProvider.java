package org.jbm.cc.cpp;

import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves the header an {@code #include} names. */
public interface HeaderProvider {

    /**
     * The header {@code #include "name"} ({@code quoted}) or {@code #include <name>}
     * refers to when written in {@code includer}, if this provider has it.
     */
    Optional<Header> find(String name, boolean quoted, String includer);

    /** Headers by name, both forms alike; for tests. */
    static HeaderProvider of(@NonNull Map<String, String> headers) {
        return (name, quoted, includer) -> {
            String text = headers.get(name);
            if (text == null) {
                return Optional.empty();
            }
            return Optional.of(new Header(name, text));
        };
    }

    /** The first provider that has the header wins. */
    static HeaderProvider chain(@NonNull List<HeaderProvider> providers) {
        return (name, quoted, includer) -> {
            for (HeaderProvider p : providers) {
                Optional<Header> found = p.find(name, quoted, includer);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        };
    }
}

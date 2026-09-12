package org.jbm.mycc.cc.cpp;

import lombok.NonNull;

import java.nio.file.Path;
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

    /** What a compilation for the VM sees: files next to the includer and in {@code searchDirs}, then the bundled headers. */
    static HeaderProvider standard(@NonNull List<Path> searchDirs) {
        return chain(List.of(new FileHeaders(searchDirs), BundledHeaders.INSTANCE));
    }

    /** Where the system's C library keeps its headers. */
    List<Path> SYSTEM_DIRS = List.of(Path.of("/usr/include/x86_64-linux-gnu"), Path.of("/usr/include"));

    /**
     * What a native build sees: files next to the includer and in
     * {@code searchDirs}, the headers that describe the compiler, then
     * the system's, which describe the C library the program is linked
     * with.
     */
    static HeaderProvider system(@NonNull List<Path> searchDirs) {
        return chain(List.of(new FileHeaders(searchDirs), BundledHeaders.COMPILER, new FileHeaders(SYSTEM_DIRS)));
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

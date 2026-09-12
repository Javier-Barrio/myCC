package org.jbm.mycc.cc.cpp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The standard headers this compiler ships, as resources under
 * {@code headers/}: declarations only, each guarded by {@code #ifndef}.
 * They answer both {@code <name>} and {@code "name"}.
 */
public final class BundledHeaders implements HeaderProvider {

    /** The headers that describe the compiler itself: the ones a native build takes from here rather than the system. */
    public static final List<String> COMPILER_NAMES = List.of(
            "stddef.h", "stdarg.h", "stdbool.h", "float.h", "stdalign.h", "stdnoreturn.h", "iso646.h");

    /** Every bundled header: the compiler's and the C library subset the VM implements. */
    public static final List<String> NAMES = List.of(
            "stdio.h", "stdlib.h", "string.h", "math.h", "stddef.h", "stdbool.h", "stdint.h", "limits.h", "wchar.h", "stdarg.h",
            "float.h", "stdalign.h", "stdnoreturn.h", "iso646.h");

    public static final BundledHeaders INSTANCE = new BundledHeaders(NAMES);
    public static final BundledHeaders COMPILER = new BundledHeaders(COMPILER_NAMES);

    private final List<String> names;

    private BundledHeaders(List<String> names) {
        this.names = names;
    }

    @Override
    public Optional<Header> find(String name, boolean quoted, String includer) {
        if (!names.contains(name)) {
            return Optional.empty();
        }
        try (InputStream in = BundledHeaders.class.getResourceAsStream("/headers/" + name)) {
            if (in == null) {
                throw new IllegalStateException("bundled header missing from the class path: " + name);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(new Header("<" + name + ">", text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

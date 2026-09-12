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

    public static final BundledHeaders INSTANCE = new BundledHeaders();

    public static final List<String> NAMES = List.of(
            "stdio.h", "stdlib.h", "string.h", "math.h", "stddef.h", "stdbool.h", "stdint.h", "limits.h", "wchar.h");

    private BundledHeaders() {
    }

    @Override
    public Optional<Header> find(String name, boolean quoted, String includer) {
        if (!NAMES.contains(name)) {
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

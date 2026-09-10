package org.jbm.mycc.cc.cpp;

import lombok.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Headers on disk: {@code "name"} is looked up next to the includer
 * first, then in the search directories; {@code <name>} only in the
 * search directories. A header is named by its path.
 */
public final class FileHeaders implements HeaderProvider {

    private final List<Path> searchDirs;

    public FileHeaders(@NonNull List<Path> searchDirs) {
        this.searchDirs = List.copyOf(searchDirs);
    }

    @Override
    public Optional<Header> find(String name, boolean quoted, String includer) {
        List<Path> dirs = new ArrayList<>();
        if (quoted) {
            Path parent = Path.of(includer).getParent();
            dirs.add(parent == null ? Path.of("") : parent);
        }
        dirs.addAll(searchDirs);
        for (Path dir : dirs) {
            Path candidate = dir.resolve(name).normalize();
            if (Files.isRegularFile(candidate)) {
                return Optional.of(read(candidate));
            }
        }
        return Optional.empty();
    }

    private static Header read(Path path) {
        try {
            return new Header(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

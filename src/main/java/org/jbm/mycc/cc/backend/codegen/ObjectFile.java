package org.jbm.mycc.cc.backend.codegen;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A relocatable object before it is written: sections with their bytes, the symbols, the relocations. */
public record ObjectFile(@NonNull List<Section> sections, @NonNull List<Symbol> symbols, @NonNull List<Relocation> relocations) {

    /** {@code bss} sections have no bytes in the file, only a size. */
    public record Section(@NonNull String name, byte @NonNull [] bytes, long size, int align, boolean code, boolean writable, boolean bss) {
    }

    public enum Kind { NOTYPE, FUNC, OBJECT, SECTION }

    /** A symbol; {@code section} null when it is undefined here. */
    public record Symbol(@NonNull String name, @Nullable String section, long offset, long size, boolean global, @NonNull Kind kind) {
    }

    /** The x86-64 relocation types the emitter's operands need. */
    public enum RelocType {
        R_X86_64_64(1), R_X86_64_PC32(2), R_X86_64_PLT32(4), R_X86_64_REX_GOTPCRELX(42);

        public final int code;

        RelocType(int code) {
            this.code = code;
        }
    }

    /** {@code symbol + addend} to be written per {@code type} at {@code offset} of {@code section}. */
    public record Relocation(@NonNull String section, long offset, @NonNull RelocType type, @NonNull String symbol, long addend) {
    }

    public Section section(String name) {
        return sections.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}

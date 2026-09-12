package org.jbm.mycc.cc.backend.lower.tac;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A defined object: its type, alignment, whether it is read-only, and
 * its initializer as typed items at byte offsets applied in order over
 * zeros, or no initializer for an all-zero object.
 */
public record Global(@NonNull String name, @NonNull Linkage linkage, @NonNull Type type, int align, boolean readonly,
                     @Nullable List<Item> init) implements Symbol {

    public Global {
        if (init != null) init = List.copyOf(init);
    }

    @Override
    public boolean isDefined() {
        return true;
    }

    public sealed interface Item permits IntItem, FloatItem, AddrItem, BitItem, BytesItem {
        long offset();
    }

    /** {@code offset : iN value}. */
    public record IntItem(long offset, @NonNull Type.Int type, long value) implements Item {
    }

    /** {@code offset : fN value}. */
    public record FloatItem(long offset, @NonNull Type.Float type, double value) implements Item {
    }

    /** {@code offset : addr @name + addend}: a relocation. */
    public record AddrItem(long offset, @NonNull String name, long addend) implements Item {
    }

    /** {@code offset : bit/width : value}: a bit-field's bits within the byte at the offset. */
    public record BitItem(long offset, int bit, int width, long value) implements Item {
    }

    /** {@code offset : bytes "..."}: a run of bytes. */
    public record BytesItem(long offset, byte @NonNull [] bytes) implements Item {
    }
}

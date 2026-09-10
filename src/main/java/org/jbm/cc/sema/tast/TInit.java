package org.jbm.cc.sema.tast;

import lombok.NonNull;
import org.jbm.cc.sema.types.Layout;

import java.util.List;
import java.util.Optional;

/**
 * A normalized initializer (6.7.11): the values to store, each at its
 * byte offset within the object and already converted to the
 * subobject's type, in the order written. A later item overrides an
 * earlier one at the same bytes (6.7.11p20), so they are applied in
 * order; subobjects with no item are zero. A scalar initializer is one
 * item at offset 0; an empty list zeroes the whole object.
 */
public record TInit(@NonNull List<Item> items) {

    public TInit {
        items = List.copyOf(items);
    }

    public static TInit scalar(@NonNull TExpr.Rvalue value) {
        return new TInit(List.of(new Item(0, value)));
    }

    /**
     * One value to store at a byte offset; for a bit-field member the
     * placement of its bits within the storage unit at that offset, since
     * two bit-fields can share the offset.
     */
    public record Item(long offset, @NonNull TExpr.Rvalue value, @NonNull Optional<Layout.BitField> bits) {
        public Item(long offset, @NonNull TExpr.Rvalue value) {
            this(offset, value, Optional.empty());
        }
    }
}

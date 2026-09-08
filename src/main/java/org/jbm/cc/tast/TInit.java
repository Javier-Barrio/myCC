package org.jbm.cc.tast;

import lombok.NonNull;

import java.util.List;

/**
 * A normalized initializer (6.7.11): the values to store, each at its
 * byte offset within the object, in ascending offset order and already
 * converted to the subobject's type. Subobjects with no item are zero.
 * A scalar initializer is one item at offset 0.
 */
public record TInit(@NonNull List<Item> items) {

    public TInit {
        items = List.copyOf(items);
    }

    public static TInit scalar(@NonNull TExpr.Rvalue value) {
        return new TInit(List.of(new Item(0, value)));
    }

    public record Item(long offset, @NonNull TExpr.Rvalue value) {
    }
}

package org.jbm.mycc.cc.backend.lower.tac;

import lombok.NonNull;

import java.util.List;

/** A named structure type: its members with their byte offsets, and its size and alignment. */
public record StructDef(@NonNull String name, @NonNull List<Member> members, long size, int align) {

    public StructDef {
        members = List.copyOf(members);
    }

    public record Member(@NonNull Type type, long offset) {
    }
}

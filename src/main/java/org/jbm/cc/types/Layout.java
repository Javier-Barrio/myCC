package org.jbm.cc.types;

import lombok.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The layout of a struct or union (C2y 6.7.3.2): its size and alignment
 * and where each member lives, by name. Members of an anonymous struct
 * or union member are entered into the enclosing map with their offsets
 * composed (6.7.3.2p15), so a nested lookup is one probe.
 * <p>
 * The algorithm is the standard's: members at increasing offsets in
 * declaration order, each at the next offset that satisfies its
 * alignment, the whole padded to the strictest member alignment; a union
 * puts every member at zero and is as large as its largest member. The
 * numbers come from {@link Types}, which asks the {@link Target}.
 */
public record Layout(long size, int align, @NonNull Map<String, Member> members) {

    public Layout {
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    /** A member's name, type, byte offset and, for a bit-field, its bit offset within the byte and width. */
    public record Member(@NonNull String name, @NonNull CType type, long offset, @NonNull Optional<BitField> bits) {
    }

    /** A bit-field's position: {@code width} bits starting {@code bitOffset} bits past the member's byte offset. */
    public record BitField(int bitOffset, int width) {
    }

    /** What sema passes in for each member declarator: the name is absent for an anonymous struct/union member. */
    public record Field(@NonNull Optional<String> name, @NonNull CType type, @NonNull OptionalInt bitWidth) {
    }

    public Optional<Member> member(@NonNull String name) {
        return Optional.ofNullable(members.get(name));
    }

    /**
     * Lays out {@code fields}. Callers have checked the constraints:
     * complete member types (a flexible array member last), unique names,
     * anonymous members of record type. Bit-fields are not laid out yet.
     */
    public static Layout of(@NonNull Types types, boolean isUnion, @NonNull List<Field> fields) {
        var members = new LinkedHashMap<String, Member>();
        long offset = 0;
        long size = 0;
        int align = 1;
        for (Field f : fields) {
            if (f.bitWidth().isPresent()) throw new IllegalArgumentException("bit-fields are not laid out yet");
            CType t = f.type();
            long fieldSize = t instanceof CType.Array a && !a.isComplete() ? 0 : types.size(t);
            int fieldAlign = types.align(t);
            long at = isUnion ? 0 : roundUp(offset, fieldAlign);
            if (f.name().isPresent()) {
                members.put(f.name().get(), new Member(f.name().get(), t, at, Optional.empty()));
            } else {
                // An anonymous struct/union member: its members become ours.
                Layout inner = ((CType.Record) t).tag().layout().orElseThrow();
                for (Member m : inner.members().values()) {
                    members.put(m.name(), new Member(m.name(), m.type(), at + m.offset(), m.bits()));
                }
            }
            align = Math.max(align, fieldAlign);
            if (isUnion) {
                size = Math.max(size, fieldSize);
            } else {
                offset = at + fieldSize;
                size = offset;
            }
        }
        return new Layout(roundUp(size, align), align, members);
    }

    static long roundUp(long value, int align) {
        return (value + align - 1) / align * align;
    }
}

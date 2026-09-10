package org.jbm.mycc.cc.sema.types;

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
public record Layout(long size, int align, @NonNull Map<String, Member> members, @NonNull List<Member> slots) {

    public Layout {
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        slots = List.copyOf(slots);
    }

    /**
     * A member's name, type, byte offset and, for a bit-field, its bit
     * offset within the storage unit and width. {@code members} holds the
     * named ones, anonymous struct/union members flattened in; {@code slots}
     * holds the initializable members in declaration order, an anonymous
     * member as one slot with an empty name (6.7.11 walks these).
     */
    public record Member(@NonNull String name, @NonNull CType type, long offset, @NonNull Optional<BitField> bits) {
        public boolean isAnonymous() {
            return name.isEmpty();
        }
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
     * anonymous members of record type, bit-field widths within their
     * type. A bit-field occupies {@code width} bits of the storage unit
     * of its declared type that contains its first bit, moved to the next
     * unit when the target does not let it straddle; a zero-width one
     * pads to the next unit boundary and an unnamed one takes space but
     * is no member.
     */
    public static Layout of(@NonNull Types types, boolean isUnion, @NonNull List<Field> fields) {
        Target target = types.target();
        var members = new LinkedHashMap<String, Member>();
        var slots = new java.util.ArrayList<Member>();
        long bit = 0;      // the next free bit in a struct
        long size = 0;     // bytes, for a union the widest member so far
        int align = 1;
        for (Field f : fields) {
            CType t = f.type();
            if (f.bitWidth().isPresent()) {
                int width = f.bitWidth().getAsInt();
                int unitBits = types.width(t);
                long unitBytes = types.size(t);
                if (f.name().isPresent() || target.unnamedBitFieldsAffectAlignment()) {
                    if (width > 0) align = Math.max(align, types.align(t));
                }
                if (width == 0) {
                    if (!isUnion) bit = roundUp(bit, unitBits);
                    continue;
                }
                long at = isUnion ? 0 : bit;
                if (!isUnion && !target.bitFieldsMayStraddle() && at / unitBits != (at + width - 1) / unitBits) {
                    at = roundUp(at, unitBits);
                }
                long unitStart = at / unitBits * unitBytes;
                if (f.name().isPresent()) {
                    var member = new Member(f.name().get(), t, unitStart, Optional.of(new BitField((int) (at % unitBits), width)));
                    members.put(f.name().get(), member);
                    slots.add(member);
                }
                if (isUnion) size = Math.max(size, unitBytes);
                else {
                    bit = at + width;
                    size = (bit + 7) / 8;
                }
                continue;
            }
            long fieldSize = t instanceof CType.Array a && !a.isComplete() ? 0 : types.size(t);
            int fieldAlign = types.align(t);
            long at = isUnion ? 0 : roundUp((bit + 7) / 8, fieldAlign);
            if (f.name().isPresent()) {
                var member = new Member(f.name().get(), t, at, Optional.empty());
                members.put(f.name().get(), member);
                slots.add(member);
            } else {
                // An anonymous struct/union member: its members become ours.
                Layout inner = ((CType.Record) t).tag().layout().orElseThrow();
                for (Member m : inner.members().values()) {
                    members.put(m.name(), new Member(m.name(), m.type(), at + m.offset(), m.bits()));
                }
                slots.add(new Member("", t, at, Optional.empty()));
            }
            align = Math.max(align, fieldAlign);
            if (isUnion) {
                size = Math.max(size, fieldSize);
            } else {
                bit = (at + fieldSize) * 8;
                size = at + fieldSize;
            }
        }
        return new Layout(roundUp(size, align), align, members, slots);
    }

    static long roundUp(long value, int align) {
        return (value + align - 1) / align * align;
    }
}

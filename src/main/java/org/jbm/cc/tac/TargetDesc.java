package org.jbm.cc.tac;

import lombok.NonNull;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Target;

/**
 * What a module records about the target it was compiled for, and what
 * a consumer checks against what it implements: the two integer class
 * widths, the pointer width, the byte order and the {@code long double}
 * format. Widths are in bits.
 */
public record TargetDesc(@NonNull String name, int wordWidth, int longWidth, int pointerWidth, boolean littleEndian,
                         int longDoubleWidth) {

    public static TargetDesc of(@NonNull Target target) {
        int ld = target.size(CType.Float.Rank.LDOUBLE) > 8 ? 80 : 64;
        return new TargetDesc(target.toString(), target.width(CType.Int.Rank.INT), target.width(CType.Int.Rank.LLONG),
                target.pointerWidth(), true, ld);
    }

    /** The class of a scalar type; {@link RegClass#NONE} for an aggregate or void. */
    public RegClass classOf(@NonNull Type t) {
        if (t instanceof Type.Int i) return i.width() <= wordWidth ? RegClass.W : RegClass.L;
        if (t instanceof Type.Ptr) return pointerWidth <= wordWidth ? RegClass.W : RegClass.L;
        if (t instanceof Type.Float f) {
            return switch (f.width()) {
                case 32 -> RegClass.S;
                case 64 -> RegClass.D;
                default -> longDoubleWidth == 80 ? RegClass.X : RegClass.D;
            };
        }
        return RegClass.NONE;
    }

    /** The width in bits of a class's register. */
    public int widthOf(@NonNull RegClass c) {
        return switch (c) {
            case W -> wordWidth;
            case L -> longWidth;
            case S -> 32;
            case D -> 64;
            case X -> 80;
            case NONE -> throw new IllegalArgumentException("no width: NONE");
        };
    }
}

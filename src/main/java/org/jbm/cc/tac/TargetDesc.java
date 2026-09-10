package org.jbm.cc.tac;

import lombok.NonNull;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Target;

/**
 * What a module records about the target it was compiled for, and what
 * a consumer checks against what it implements: the width of {@code int}
 * and of the integer register, the pointer width, the byte order and
 * the {@code long double} format. Widths are in bits.
 */
public record TargetDesc(@NonNull String name, int wordWidth, int registerWidth, int pointerWidth, boolean littleEndian,
                         int longDoubleWidth) {

    public static TargetDesc of(@NonNull Target target) {
        int ld = target.size(CType.Float.Rank.LDOUBLE) > 8 ? 80 : 64;
        return new TargetDesc(target.toString(), target.width(CType.Int.Rank.INT), target.width(CType.Int.Rank.LLONG),
                target.pointerWidth(), true, ld);
    }

    /** The class of a scalar type; {@link RegClass#NONE} for an aggregate or void. */
    public RegClass classOf(@NonNull Type t) {
        if (t instanceof Type.Int || t instanceof Type.Ptr) {
            return RegClass.INT;
        }
        if (t instanceof Type.Float) {
            return RegClass.FLOAT;
        }
        return RegClass.NONE;
    }

    /** Whether a floating precision is one this target computes in. */
    public boolean hasPrecision(int width) {
        if (width == 32 || width == 64) {
            return true;
        }
        return width == longDoubleWidth;
    }
}

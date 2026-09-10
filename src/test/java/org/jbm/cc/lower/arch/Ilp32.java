package org.jbm.cc.lower.arch;

import org.jbm.cc.sema.types.CType.Float;
import org.jbm.cc.sema.types.CType.Int.Rank;
import org.jbm.cc.sema.types.Target;

/**
 * A test-only 32-bit target (ILP32, unsigned plain char, 4-byte
 * {@code long double}), used to check that no width leaks into a rule
 * that should be asking the target.
 */
public final class Ilp32 implements Target {

    public static final Ilp32 INSTANCE = new Ilp32();

    @Override
    public int width(Rank rank) {
        return switch (rank) {
            case BOOL, CHAR -> 8;
            case SHORT -> 16;
            case INT, LONG -> 32;
            case LLONG -> 64;
        };
    }

    @Override
    public int align(Rank rank) {
        return Math.min(width(rank) / 8, 4);
    }

    @Override
    public int size(Float.Rank rank) {
        return switch (rank) {
            case FLOAT -> 4;
            case DOUBLE -> 8;
            case LDOUBLE -> 12;
        };
    }

    @Override
    public int align(Float.Rank rank) {
        return 4;
    }

    @Override
    public int bitIntSize(int width) {
        if (width <= 8) return 1;
        if (width <= 16) return 2;
        return (width + 31) / 32 * 4;
    }

    @Override
    public int bitIntAlign(int width) {
        return Math.min(bitIntSize(width), 4);
    }

    @Override
    public boolean bitFieldsMayStraddle() {
        return false;
    }

    @Override
    public boolean unnamedBitFieldsAffectAlignment() {
        return false;
    }

    @Override
    public int pointerWidth() {
        return 32;
    }

    @Override
    public int pointerAlign() {
        return 4;
    }

    @Override
    public boolean charIsSigned() {
        return false;
    }

    @Override
    public Rank sizeRank() {
        return Rank.INT;
    }

    @Override
    public Rank ptrdiffRank() {
        return Rank.INT;
    }

    @Override
    public Rank wcharRank() {
        return Rank.SHORT;
    }

    @Override
    public boolean wcharIsSigned() {
        return false;
    }

    @Override
    public String toString() {
        return "ilp32";
    }
}

package org.jbm.cc.types;

import org.jbm.cc.types.CType.Float;
import org.jbm.cc.types.CType.Int.Rank;

/**
 * The System V AMD64 ABI: LP64, plain {@code char} signed, natural
 * alignment, {@code long double} the 80-bit x87 format in 16 bytes.
 */
public final class X86_64SysV implements Target {

    public static final X86_64SysV INSTANCE = new X86_64SysV();

    private X86_64SysV() {
    }

    @Override
    public int width(Rank rank) {
        return switch (rank) {
            case BOOL, CHAR -> 8;
            case SHORT -> 16;
            case INT -> 32;
            case LONG, LLONG -> 64;
        };
    }

    @Override
    public int align(Rank rank) {
        return width(rank) / 8;
    }

    @Override
    public int size(Float.Rank rank) {
        return switch (rank) {
            case FLOAT -> 4;
            case DOUBLE -> 8;
            case LDOUBLE -> 16;
        };
    }

    @Override
    public int align(Float.Rank rank) {
        return size(rank);
    }

    @Override
    public int pointerWidth() {
        return 64;
    }

    @Override
    public int pointerAlign() {
        return 8;
    }

    @Override
    public boolean charIsSigned() {
        return true;
    }

    @Override
    public Rank sizeRank() {
        return Rank.LONG;
    }

    @Override
    public Rank ptrdiffRank() {
        return Rank.LONG;
    }

    @Override
    public Rank wcharRank() {
        return Rank.INT;
    }

    @Override
    public boolean wcharIsSigned() {
        return true;
    }

    @Override
    public String toString() {
        return "x86_64-sysv";
    }
}

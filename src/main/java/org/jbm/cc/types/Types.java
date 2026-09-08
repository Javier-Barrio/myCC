package org.jbm.cc.types;

import lombok.NonNull;
import org.jbm.cc.types.CType.Int.Rank;
import org.jbm.cc.types.CType.Int.Sign;

import java.util.HashMap;
import java.util.Map;

/**
 * The type factory for one compilation: the interner that makes every
 * {@link CType} unique, and the operations that need the {@link Target}.
 * All construction goes through here ({@code pointer(t)},
 * {@code qualified(t, q)}), so identity is equality; nothing outside this
 * package constructs a CType.
 */
public final class Types {

    private final Target target;
    private final Map<CType, CType> interned = new HashMap<>();

    private final CType.Void voidType;
    private final CType.Int boolType;

    public Types(@NonNull Target target) {
        this.target = target;
        this.voidType = (CType.Void) intern(new CType.Void(Quals.NONE));
        this.boolType = (CType.Int) intern(new CType.Int(Rank.BOOL, Sign.UNSIGNED, Quals.NONE));
    }

    public Target target() {
        return target;
    }

    private CType intern(CType t) {
        CType existing = interned.putIfAbsent(t, t);
        return existing == null ? t : existing;
    }

    // ---- construction ---------------------------------------------------------------

    public CType.Void void_() {
        return voidType;
    }

    public CType.Int bool_() {
        return boolType;
    }

    public CType.Int integer(@NonNull Rank rank, @NonNull Sign sign) {
        return (CType.Int) intern(new CType.Int(rank, sign, Quals.NONE));
    }

    public CType.Int char_() {
        return integer(Rank.CHAR, Sign.PLAIN);
    }

    public CType.Int schar() {
        return integer(Rank.CHAR, Sign.SIGNED);
    }

    public CType.Int uchar() {
        return integer(Rank.CHAR, Sign.UNSIGNED);
    }

    public CType.Int short_() {
        return integer(Rank.SHORT, Sign.SIGNED);
    }

    public CType.Int ushort() {
        return integer(Rank.SHORT, Sign.UNSIGNED);
    }

    public CType.Int int_() {
        return integer(Rank.INT, Sign.SIGNED);
    }

    public CType.Int uint() {
        return integer(Rank.INT, Sign.UNSIGNED);
    }

    public CType.Int long_() {
        return integer(Rank.LONG, Sign.SIGNED);
    }

    public CType.Int ulong() {
        return integer(Rank.LONG, Sign.UNSIGNED);
    }

    public CType.Int llong() {
        return integer(Rank.LLONG, Sign.SIGNED);
    }

    public CType.Int ullong() {
        return integer(Rank.LLONG, Sign.UNSIGNED);
    }

    public CType.Float floating(@NonNull CType.Float.Rank rank) {
        return (CType.Float) intern(new CType.Float(rank, Quals.NONE));
    }

    public CType.Float float_() {
        return floating(CType.Float.Rank.FLOAT);
    }

    public CType.Float double_() {
        return floating(CType.Float.Rank.DOUBLE);
    }

    public CType.Float longDouble() {
        return floating(CType.Float.Rank.LDOUBLE);
    }

    public CType.Pointer pointer(@NonNull CType to) {
        return (CType.Pointer) intern(new CType.Pointer(to, Quals.NONE));
    }

    /** {@code t} with exactly these qualifiers. */
    public CType qualified(@NonNull CType t, @NonNull Quals quals) {
        if (t.quals().equals(quals)) return t;
        CType fresh;
        if (t instanceof CType.Void) fresh = new CType.Void(quals);
        else if (t instanceof CType.Int i) fresh = new CType.Int(i.rank(), i.sign(), quals);
        else if (t instanceof CType.Float f) fresh = new CType.Float(f.rank(), quals);
        else if (t instanceof CType.Pointer p) fresh = new CType.Pointer(p.target(), quals);
        else throw new IllegalStateException(t.spelling());
        return intern(fresh);
    }

    /** {@code t} with its qualifiers added to {@code quals}. */
    public CType plusQuals(@NonNull CType t, @NonNull Quals quals) {
        return qualified(t, t.quals().plus(quals));
    }

    public CType unqualified(@NonNull CType t) {
        return qualified(t, Quals.NONE);
    }

    // ---- the target's numbers ---------------------------------------------------------

    /** {@code size_t} (7.21p2). */
    public CType.Int sizeT() {
        return integer(target.sizeRank(), Sign.UNSIGNED);
    }

    /** {@code ptrdiff_t} (7.21p2). */
    public CType.Int ptrdiffT() {
        return integer(target.ptrdiffRank(), Sign.SIGNED);
    }

    public CType.Int wcharT() {
        return integer(target.wcharRank(), target.wcharIsSigned() ? Sign.SIGNED : Sign.UNSIGNED);
    }

    public boolean isSigned(@NonNull CType.Int t) {
        return switch (t.sign()) {
            case SIGNED -> true;
            case UNSIGNED -> false;
            case PLAIN -> target.charIsSigned();
        };
    }

    /** Width in bits of an integer or pointer type. */
    public int width(@NonNull CType t) {
        if (t instanceof CType.Int i) return target.width(i.rank());
        if (t instanceof CType.Pointer) return target.pointerWidth();
        throw new IllegalArgumentException("no width: " + t.spelling());
    }

    /** Size in bytes of a complete scalar type. */
    public long size(@NonNull CType t) {
        if (t instanceof CType.Int i) return target.width(i.rank()) / 8;
        if (t instanceof CType.Float f) return target.size(f.rank());
        if (t instanceof CType.Pointer) return target.pointerWidth() / 8;
        throw new IllegalArgumentException("no size: " + t.spelling());
    }

    /** Alignment in bytes of a complete scalar type. */
    public int align(@NonNull CType t) {
        if (t instanceof CType.Int i) return target.align(i.rank());
        if (t instanceof CType.Float f) return target.align(f.rank());
        if (t instanceof CType.Pointer) return target.pointerAlign();
        throw new IllegalArgumentException("no alignment: " + t.spelling());
    }

    /** How many distinct types have been created; for tests of interning. */
    public int internedCount() {
        return interned.size();
    }
}

package org.jbm.cc.types;

import lombok.NonNull;
import org.jbm.cc.types.CType.Int.Rank;
import org.jbm.cc.types.CType.Int.Sign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

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
    private final CType.Nullptr nullptrType;

    public Types(@NonNull Target target) {
        this.target = target;
        this.voidType = (CType.Void) intern(new CType.Void(Quals.NONE));
        this.boolType = (CType.Int) intern(new CType.Int(Rank.BOOL, Sign.UNSIGNED, Quals.NONE));
        this.nullptrType = (CType.Nullptr) intern(new CType.Nullptr(Quals.NONE));
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

    public CType.Nullptr nullptrT() {
        return nullptrType;
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

    public CType.BitInt bitInt(int width, boolean isUnsigned) {
        return (CType.BitInt) intern(new CType.BitInt(width, isUnsigned, Quals.NONE));
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

    public CType.Record record(@NonNull Tag tag) {
        return (CType.Record) intern(new CType.Record(tag, Quals.NONE));
    }

    public CType.Pointer pointer(@NonNull CType to) {
        return (CType.Pointer) intern(new CType.Pointer(to, Quals.NONE));
    }

    public CType.Array array(@NonNull CType element, long size) {
        if (size < 0) throw new IllegalArgumentException("negative array size");
        return (CType.Array) intern(new CType.Array(element, OptionalLong.of(size)));
    }

    public CType.Array incompleteArray(@NonNull CType element) {
        return (CType.Array) intern(new CType.Array(element, OptionalLong.empty()));
    }

    /**
     * A function type from its <em>unadjusted</em> parameter types: each is
     * adjusted per 6.7.7.4p7-8 (array to pointer to element, function to
     * pointer to function, top-level qualifiers dropped).
     */
    public CType.Function function(@NonNull CType returnType, @NonNull List<? extends CType> parameters,
                                   boolean isVariadic) {
        var adjusted = new ArrayList<CType>(parameters.size());
        for (CType p : parameters) adjusted.add(adjustParameter(p));
        return (CType.Function) intern(new CType.Function(returnType, adjusted, isVariadic));
    }

    /** 6.7.7.4p7-8, and 6.7.7.4p15: qualifiers do not take part in compatibility. */
    public CType adjustParameter(@NonNull CType p) {
        if (p instanceof CType.Array a) return pointer(a.element());
        if (p instanceof CType.Function) return pointer(p);
        return unqualified(p);
    }

    /**
     * {@code t} with exactly these qualifiers. Qualifying an array
     * qualifies its element type (6.7.4.1p10); a function type cannot be
     * qualified.
     */
    public CType qualified(@NonNull CType t, @NonNull Quals quals) {
        if (t.quals().equals(quals)) return t;
        CType fresh;
        if (t instanceof CType.Void) fresh = new CType.Void(quals);
        else if (t instanceof CType.Int i) fresh = new CType.Int(i.rank(), i.sign(), quals);
        else if (t instanceof CType.BitInt b) fresh = new CType.BitInt(b.width(), b.isUnsigned(), quals);
        else if (t instanceof CType.Float f) fresh = new CType.Float(f.rank(), quals);
        else if (t instanceof CType.Pointer p) fresh = new CType.Pointer(p.target(), quals);
        else if (t instanceof CType.Nullptr) fresh = new CType.Nullptr(quals);
        else if (t instanceof CType.Record r) fresh = new CType.Record(r.tag(), quals);
        else if (t instanceof CType.Array a) fresh = new CType.Array(qualified(a.element(), quals), a.size());
        else throw new IllegalArgumentException("cannot qualify " + t.spelling());
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

    /** Whether an integer type is signed; plain {@code char} asks the target. */
    public boolean isSigned(@NonNull CType t) {
        if (t instanceof CType.BitInt b) return !b.isUnsigned();
        if (!(t instanceof CType.Int i)) throw new IllegalArgumentException("not an integer type: " + t.spelling());
        return switch (i.sign()) {
            case SIGNED -> true;
            case UNSIGNED -> false;
            case PLAIN -> target.charIsSigned();
        };
    }

    /** Width in bits of an integer or pointer type. */
    public int width(@NonNull CType t) {
        if (t instanceof CType.Int i) return target.width(i.rank());
        if (t instanceof CType.BitInt b) return b.width();
        if (t instanceof CType.Pointer || t instanceof CType.Nullptr) return target.pointerWidth();
        throw new IllegalArgumentException("no width: " + t.spelling());
    }

    /** The unsigned counterpart of an integer type (6.3.2.2p1's last step). */
    public CType unsignedOf(@NonNull CType t) {
        if (t instanceof CType.BitInt b) return bitInt(b.width(), true);
        return integer(((CType.Int) t).rank(), Sign.UNSIGNED);
    }

    /**
     * Integer conversion rank (6.3.1.1p1): standard types in rank order
     * whatever their widths; bit-precise types among themselves by width;
     * a bit-precise type ranks above a standard type of lesser width and
     * below one of the same or greater width.
     */
    public int rankCompare(@NonNull CType a, @NonNull CType b) {
        if (a instanceof CType.Int ia && b instanceof CType.Int ib) return ia.rank().compareTo(ib.rank());
        if (a instanceof CType.BitInt ba && b instanceof CType.BitInt bb) return Integer.compare(ba.width(), bb.width());
        if (a instanceof CType.BitInt ba) return target.width(((CType.Int) b).rank()) < ba.width() ? 1 : -1;
        return -rankCompare(b, a);
    }

    /** Size in bytes of a complete object type. */
    public long size(@NonNull CType t) {
        if (t instanceof CType.Int i) return target.width(i.rank()) / 8;
        if (t instanceof CType.BitInt b) return target.bitIntSize(b.width());
        if (t instanceof CType.Float f) return target.size(f.rank());
        if (t instanceof CType.Pointer || t instanceof CType.Nullptr) return target.pointerWidth() / 8;
        if (t instanceof CType.Array a && a.size().isPresent()) return size(a.element()) * a.size().getAsLong();
        if (t instanceof CType.Record r && r.tag().layout().isPresent()) return r.tag().layout().get().size();
        throw new IllegalArgumentException("no size: " + t.spelling());
    }

    /** Alignment in bytes of an object type. */
    public int align(@NonNull CType t) {
        if (t instanceof CType.Int i) return target.align(i.rank());
        if (t instanceof CType.BitInt b) return target.bitIntAlign(b.width());
        if (t instanceof CType.Float f) return target.align(f.rank());
        if (t instanceof CType.Pointer || t instanceof CType.Nullptr) return target.pointerAlign();
        if (t instanceof CType.Array a) return align(a.element());
        if (t instanceof CType.Record r && r.tag().layout().isPresent()) return r.tag().layout().get().align();
        throw new IllegalArgumentException("no alignment: " + t.spelling());
    }

    // ---- compatibility (6.2.7) --------------------------------------------------------------

    /**
     * Whether two types are compatible (6.2.7p1). Interning makes identical
     * types the same object, so the structural cases are only the ones the
     * standard lists: arrays where either size is unknown, functions
     * parameter by parameter, pointers by their targets. Qualifiers must
     * match at every level (6.7.4.1p10).
     */
    public boolean compatible(@NonNull CType a, @NonNull CType b) {
        if (a == b) return true;
        if (!a.quals().equals(b.quals())) return false;
        if (a instanceof CType.Pointer pa && b instanceof CType.Pointer pb) {
            return compatible(pa.target(), pb.target());
        }
        if (a instanceof CType.Array aa && b instanceof CType.Array ab) {
            if (aa.size().isPresent() && ab.size().isPresent() && aa.size().getAsLong() != ab.size().getAsLong()) {
                return false;
            }
            return compatible(aa.element(), ab.element());
        }
        if (a instanceof CType.Function fa && b instanceof CType.Function fb) {
            if (fa.isVariadic() != fb.isVariadic() || fa.parameters().size() != fb.parameters().size()) return false;
            if (!compatible(fa.returnType(), fb.returnType())) return false;
            for (int i = 0; i < fa.parameters().size(); i++) {
                if (!compatible(fa.parameters().get(i), fb.parameters().get(i))) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * The composite type of two compatible types (6.2.7p3): an array takes
     * the known size, a function is composed parameter by parameter, a
     * pointer composes its target. Callers check {@link #compatible} first.
     */
    public CType composite(@NonNull CType a, @NonNull CType b) {
        if (a == b) return a;
        if (a instanceof CType.Pointer pa && b instanceof CType.Pointer pb) {
            return qualified(pointer(composite(pa.target(), pb.target())), a.quals());
        }
        if (a instanceof CType.Array aa && b instanceof CType.Array ab) {
            CType element = composite(aa.element(), ab.element());
            OptionalLong size = aa.size().isPresent() ? aa.size() : ab.size();
            return size.isPresent() ? array(element, size.getAsLong()) : incompleteArray(element);
        }
        if (a instanceof CType.Function fa && b instanceof CType.Function fb) {
            var params = new ArrayList<CType>(fa.parameters().size());
            for (int i = 0; i < fa.parameters().size(); i++) {
                params.add(composite(fa.parameters().get(i), fb.parameters().get(i)));
            }
            return function(composite(fa.returnType(), fb.returnType()), params, fa.isVariadic());
        }
        throw new IllegalArgumentException("not compatible: " + a.spelling() + " and " + b.spelling());
    }

    // ---- conversions (6.3) -------------------------------------------------------------

    /**
     * Integer promotions (6.3.2.1p2): an integer type of rank lower than
     * {@code int} becomes {@code int} if that can represent all its values,
     * otherwise {@code unsigned int}. Other types are returned unchanged.
     * Qualifiers are dropped, since promotions apply to values.
     */
    public CType promote(@NonNull CType t) {
        // Bit-precise types are never promoted (6.3.2.1p2).
        if (!(t instanceof CType.Int i)) return unqualified(t);
        if (i.rank().compareTo(Rank.INT) >= 0) return unqualified(i);
        boolean fits = isSigned(i) || target.width(i.rank()) < target.width(Rank.INT);
        return fits ? int_() : uint();
    }

    /**
     * Default argument promotions (6.5.3.3p6): integer promotions, and
     * {@code float} to {@code double}.
     */
    public CType defaultArgumentPromote(@NonNull CType t) {
        if (t instanceof CType.Float f && f.rank() == CType.Float.Rank.FLOAT) return double_();
        return promote(t);
    }

    /**
     * Usual arithmetic conversions (6.3.2.2): the common real type of two
     * arithmetic operands. Floating wins by rank; otherwise both are
     * promoted and the integer rules apply: same type, else same
     * signedness takes the higher rank, else the unsigned one if its rank
     * is at least the signed one's, else the signed one if it can
     * represent every value of the unsigned one, else the unsigned
     * counterpart of the signed one. The last two steps are where the
     * target's widths decide.
     */
    public CType usualArithmetic(@NonNull CType a, @NonNull CType b) {
        if (!a.isArithmetic() || !b.isArithmetic()) {
            throw new IllegalArgumentException(a.spelling() + " and " + b.spelling());
        }
        if (a instanceof CType.Float fa) {
            if (b instanceof CType.Float fb && fb.rank().compareTo(fa.rank()) > 0) return floating(fb.rank());
            return floating(fa.rank());
        }
        if (b instanceof CType.Float fb) return floating(fb.rank());
        CType pa = promote(a);
        CType pb = promote(b);
        if (pa == pb) return pa;
        boolean ua = !isSigned(pa), ub = !isSigned(pb);
        if (ua == ub) return rankCompare(pa, pb) >= 0 ? pa : pb;
        CType unsigned = ua ? pa : pb;
        CType signed = ua ? pb : pa;
        if (rankCompare(unsigned, signed) >= 0) return unsigned;
        if (width(signed) > width(unsigned)) return signed;
        return unsignedOf(signed);
    }

    /** How many distinct types have been created; for tests of interning. */
    public int internedCount() {
        return interned.size();
    }
}

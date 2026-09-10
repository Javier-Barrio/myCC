package org.jbm.mycc.cc.sema.types;

/**
 * What the C standard leaves to the implementation (5.2.5.3, 6.2.6.2,
 * 6.2.5p20, 7.21): widths and alignments of the scalar ranks, the
 * signedness of plain {@code char}, and which ranks {@code size_t},
 * {@code ptrdiff_t} and {@code wchar_t} are. {@link Types} and the layout
 * code ask a Target for every number that could differ between machines;
 * nothing else in the compiler front end holds one.
 * <p>
 * Widths are in bits, sizes and alignments in bytes.
 */
public interface Target {

    int width(CType.Int.Rank rank);

    int align(CType.Int.Rank rank);

    int size(CType.Float.Rank rank);

    int align(CType.Float.Rank rank);

    /** Storage size in bytes of a {@code _BitInt} of this width. */
    int bitIntSize(int width);

    int bitIntAlign(int width);

    /**
     * Whether a bit-field may straddle a boundary of its declared type's
     * storage unit (6.7.3.2p13 leaves it to the implementation). When
     * not, one that would is moved to the next unit.
     */
    boolean bitFieldsMayStraddle();

    /** Whether an unnamed bit-field's declared type contributes to the record's alignment. */
    boolean unnamedBitFieldsAffectAlignment();

    int pointerWidth();

    int pointerAlign();

    boolean charIsSigned();

    /** {@code size_t} is the unsigned integer type of this rank (7.21p2). */
    CType.Int.Rank sizeRank();

    /** {@code ptrdiff_t} is the signed integer type of this rank. */
    CType.Int.Rank ptrdiffRank();

    /** {@code wchar_t}'s rank; its signedness is {@link #wcharIsSigned()}. */
    CType.Int.Rank wcharRank();

    boolean wcharIsSigned();
}

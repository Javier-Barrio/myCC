package org.jbm.cc.types;

import org.jbm.cc.types.CType.Int.Rank;
import org.jbm.cc.types.CType.Int.Sign;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypesTest {

    private final Types x64 = new Types(X86_64SysV.INSTANCE);
    private final Types ilp32 = new Types(Ilp32.INSTANCE);

    // ---- interning --------------------------------------------------------------

    @Test
    void equalTypesAreTheSameObject() {
        assertSame(x64.int_(), x64.int_());
        assertSame(x64.int_(), x64.integer(Rank.INT, Sign.SIGNED));
        assertSame(x64.pointer(x64.int_()), x64.pointer(x64.int_()));
        assertSame(x64.pointer(x64.pointer(x64.char_())), x64.pointer(x64.pointer(x64.char_())));
        assertNotSame(x64.int_(), x64.uint());
        assertNotSame(x64.pointer(x64.int_()), x64.pointer(x64.uint()));
    }

    @Test
    void qualifiedTypesAreDistinctAndUnqualifiedReturnsTheShared() {
        CType i = x64.int_();
        CType ci = x64.qualified(i, Quals.CONST);
        assertNotSame(i, ci);
        assertSame(ci, x64.qualified(i, Quals.CONST));
        assertSame(i, x64.unqualified(ci));
        assertSame(i, x64.qualified(i, Quals.NONE));
        // Pointer qualifiers are on the pointer, not the target.
        CType pci = x64.pointer(ci);
        CType cpi = x64.qualified(x64.pointer(i), Quals.CONST);
        assertNotSame(pci, cpi);
        assertSame(x64.pointer(i), x64.unqualified(cpi));
        assertSame(pci, x64.unqualified(pci));
    }

    @Test
    void eachDistinctTypeIsCreatedOnce() {
        x64.char_();
        int before = x64.internedCount();
        for (int k = 0; k < 100; k++) x64.pointer(x64.qualified(x64.char_(), Quals.CONST));
        assertEquals(before + 2, x64.internedCount(), "const char, and pointer to it");
    }

    @Test
    void theThreeCharTypesAreDistinct() {
        assertNotSame(x64.char_(), x64.schar());
        assertNotSame(x64.char_(), x64.uchar());
        assertTrue(x64.isSigned(x64.char_()), "plain char is signed on x86-64");
        assertFalse(ilp32.isSigned(ilp32.char_()), "unsigned on the ILP32 test target");
        assertTrue(x64.isSigned(x64.schar()));
        assertFalse(x64.isSigned(x64.uchar()));
    }

    @Test
    void boolIsAnUnsignedIntegerAndOnlyCharIsPlain() {
        assertTrue(x64.bool_().isInteger());
        assertTrue(x64.bool_().isBool());
        assertTrue(x64.bool_().isUnsigned());
        assertThrows(IllegalArgumentException.class, () -> new CType.Int(Rank.INT, Sign.PLAIN, Quals.NONE));
        assertThrows(IllegalArgumentException.class, () -> new CType.Int(Rank.BOOL, Sign.SIGNED, Quals.NONE));
    }

    // ---- predicates -------------------------------------------------------------------

    @Test
    void predicates() {
        assertTrue(x64.int_().isArithmetic() && x64.int_().isScalar() && x64.int_().isInteger());
        assertTrue(x64.double_().isArithmetic() && x64.double_().isFloating() && !x64.double_().isInteger());
        assertTrue(x64.pointer(x64.void_()).isScalar() && x64.pointer(x64.void_()).isPointer());
        assertFalse(x64.pointer(x64.void_()).isArithmetic());
        assertTrue(x64.void_().isVoid() && !x64.void_().isScalar());
    }

    // ---- the target's numbers ----------------------------------------------------------

    @Test
    void widthsComeFromTheTarget() {
        assertEquals(64, x64.width(x64.long_()));
        assertEquals(32, ilp32.width(ilp32.long_()));
        assertEquals(64, x64.width(x64.pointer(x64.int_())));
        assertEquals(32, ilp32.width(ilp32.pointer(ilp32.int_())));
        assertEquals(8, x64.size(x64.long_()));
        assertEquals(4, ilp32.size(ilp32.long_()));
        assertEquals(16, x64.size(x64.longDouble()));
        assertEquals(12, ilp32.size(ilp32.longDouble()));
        assertEquals(8, x64.align(x64.llong()));
        assertEquals(4, ilp32.align(ilp32.llong()));
    }

    @Test
    void sizeTAndPtrdiffTAreTheTargetsRanks() {
        assertSame(x64.ulong(), x64.sizeT());
        assertSame(x64.long_(), x64.ptrdiffT());
        assertSame(ilp32.uint(), ilp32.sizeT());
        assertSame(ilp32.int_(), ilp32.ptrdiffT());
        assertSame(x64.int_(), x64.wcharT());
        assertSame(ilp32.ushort(), ilp32.wcharT());
    }

    @Test
    void intTypeIsTheSameObjectRegardlessOfTarget() {
        // A CType carries nothing target-specific, so the two interners
        // produce equal (though separately interned) objects.
        assertEquals(x64.long_(), ilp32.long_());
        assertEquals(x64.pointer(x64.char_()).spelling(), ilp32.pointer(ilp32.char_()).spelling());
    }

    // ---- promotions and usual arithmetic conversions -----------------------------------

    @Test
    void integerPromotions() {
        assertSame(x64.int_(), x64.promote(x64.bool_()));
        assertSame(x64.int_(), x64.promote(x64.char_()));
        assertSame(x64.int_(), x64.promote(x64.uchar()));
        assertSame(x64.int_(), x64.promote(x64.short_()));
        assertSame(x64.int_(), x64.promote(x64.ushort()), "unsigned short fits in a 32-bit int");
        assertSame(x64.uint(), x64.promote(x64.uint()));
        assertSame(x64.long_(), x64.promote(x64.long_()));
        assertSame(x64.double_(), x64.promote(x64.double_()));
        assertSame(x64.int_(), x64.promote(x64.qualified(x64.short_(), Quals.CONST)), "promotion drops qualifiers");
        assertSame(x64.int_(), x64.promote(x64.qualified(x64.int_(), Quals.CONST)));
    }

    @Test
    void defaultArgumentPromotions() {
        assertSame(x64.double_(), x64.defaultArgumentPromote(x64.float_()));
        assertSame(x64.double_(), x64.defaultArgumentPromote(x64.double_()));
        assertSame(x64.int_(), x64.defaultArgumentPromote(x64.char_()));
        assertSame(x64.pointer(x64.int_()), x64.defaultArgumentPromote(x64.pointer(x64.int_())));
    }

    @Test
    void usualArithmeticConversionsOnX86_64() {
        assertSame(x64.int_(), x64.usualArithmetic(x64.char_(), x64.short_()));
        assertSame(x64.int_(), x64.usualArithmetic(x64.bool_(), x64.bool_()));
        assertSame(x64.uint(), x64.usualArithmetic(x64.uint(), x64.int_()));
        assertSame(x64.long_(), x64.usualArithmetic(x64.long_(), x64.int_()));
        assertSame(x64.ulong(), x64.usualArithmetic(x64.int_(), x64.ulong()));
        assertSame(x64.ulong(), x64.usualArithmetic(x64.long_(), x64.ulong()));
        assertSame(x64.ullong(), x64.usualArithmetic(x64.llong(), x64.ulong()), "same width: unsigned long long");
        assertSame(x64.float_(), x64.usualArithmetic(x64.float_(), x64.int_()));
        assertSame(x64.double_(), x64.usualArithmetic(x64.float_(), x64.double_()));
        assertSame(x64.longDouble(), x64.usualArithmetic(x64.double_(), x64.longDouble()));
        assertSame(x64.longDouble(), x64.usualArithmetic(x64.longDouble(), x64.ullong()));
        assertSame(x64.int_(), x64.usualArithmetic(x64.qualified(x64.int_(), Quals.CONST), x64.int_()));
        assertThrows(IllegalArgumentException.class, () -> x64.usualArithmetic(x64.pointer(x64.int_()), x64.int_()));
    }

    @Test
    void usualArithmeticConversionsAskTheTargetForWidths() {
        // long can hold every unsigned int on LP64 but not on ILP32 (6.3.2.2).
        assertSame(x64.long_(), x64.usualArithmetic(x64.uint(), x64.long_()));
        assertSame(ilp32.ulong(), ilp32.usualArithmetic(ilp32.uint(), ilp32.long_()));
        // long long is wider than unsigned long only on ILP32.
        assertSame(ilp32.llong(), ilp32.usualArithmetic(ilp32.llong(), ilp32.ulong()));
        assertSame(x64.ullong(), x64.usualArithmetic(x64.llong(), x64.ulong()));
    }

    // ---- arrays and functions ------------------------------------------------------------

    @Test
    void arraysAndFunctionsAreInternedAndAdjusted() {
        assertSame(x64.array(x64.int_(), 3), x64.array(x64.int_(), 3));
        assertNotSame(x64.array(x64.int_(), 3), x64.array(x64.int_(), 4));
        assertNotSame(x64.array(x64.int_(), 3), x64.incompleteArray(x64.int_()));
        assertFalse(x64.incompleteArray(x64.int_()).isComplete());
        assertTrue(x64.array(x64.int_(), 3).isComplete());
        assertFalse(x64.void_().isComplete());
        var f = x64.function(x64.int_(), List.of(x64.array(x64.char_(), 4), x64.function(x64.void_(), List.of(), false),
                x64.qualified(x64.int_(), Quals.CONST)), false);
        assertSame(f, x64.function(x64.int_(), List.of(x64.pointer(x64.char_()),
                x64.pointer(x64.function(x64.void_(), List.of(), false)), x64.int_()), false));
        assertFalse(f.isComplete());
        assertEquals(12, x64.size(x64.array(x64.int_(), 3)));
        assertEquals(4, x64.align(x64.array(x64.int_(), 3)));
        assertThrows(IllegalArgumentException.class, () -> x64.size(x64.incompleteArray(x64.int_())));
    }

    @Test
    void qualifyingAnArrayQualifiesItsElements() {
        CType a = x64.qualified(x64.array(x64.int_(), 2), Quals.CONST);
        assertSame(x64.array(x64.qualified(x64.int_(), Quals.CONST), 2), a);
        assertTrue(a.quals().isConst());
        assertThrows(IllegalArgumentException.class,
                () -> x64.qualified(x64.function(x64.int_(), List.of(), false), Quals.CONST));
    }

    // ---- compatibility and composite types --------------------------------------------------

    @Test
    void compatibility() {
        CType intArr = x64.incompleteArray(x64.int_());
        assertTrue(x64.compatible(intArr, x64.array(x64.int_(), 3)));
        assertFalse(x64.compatible(x64.array(x64.int_(), 2), x64.array(x64.int_(), 3)));
        assertTrue(x64.compatible(x64.pointer(intArr), x64.pointer(x64.array(x64.int_(), 2))));
        assertFalse(x64.compatible(x64.int_(), x64.qualified(x64.int_(), Quals.CONST)));
        assertFalse(x64.compatible(x64.pointer(x64.int_()), x64.pointer(x64.qualified(x64.int_(), Quals.CONST))));
        assertFalse(x64.compatible(x64.int_(), x64.long_()));
        assertFalse(x64.compatible(x64.char_(), x64.schar()));
        var f1 = x64.function(x64.int_(), List.of(x64.incompleteArray(x64.char_())), false);
        var f2 = x64.function(x64.int_(), List.of(x64.pointer(x64.char_())), false);
        assertSame(f1, f2, "adjusted parameters make these one type");
        assertFalse(x64.compatible(f1, x64.function(x64.int_(), List.of(x64.pointer(x64.char_())), true)));
        assertFalse(x64.compatible(f1, x64.function(x64.long_(), List.of(x64.pointer(x64.char_())), false)));
        var g1 = x64.function(x64.int_(), List.of(x64.pointer(intArr)), false);
        var g2 = x64.function(x64.int_(), List.of(x64.pointer(x64.array(x64.int_(), 5))), false);
        assertTrue(x64.compatible(g1, g2));
    }

    @Test
    void compositeTypes() {
        CType intArr = x64.incompleteArray(x64.int_());
        assertSame(x64.array(x64.int_(), 3), x64.composite(intArr, x64.array(x64.int_(), 3)));
        assertSame(x64.array(x64.int_(), 3), x64.composite(x64.array(x64.int_(), 3), intArr));
        assertSame(intArr, x64.composite(intArr, intArr));
        // 6.2.7p5's example: int (*)(int (*)[], int (*)[3]) composes to int (*)(int (*)[3], int (*)[3]).
        var f1 = x64.function(x64.int_(), List.of(x64.pointer(intArr), x64.pointer(x64.array(x64.int_(), 3))), false);
        var f2 = x64.function(x64.int_(), List.of(x64.pointer(x64.array(x64.int_(), 3)), x64.pointer(intArr)), false);
        var expected = x64.function(x64.int_(),
                List.of(x64.pointer(x64.array(x64.int_(), 3)), x64.pointer(x64.array(x64.int_(), 3))), false);
        assertSame(expected, x64.composite(f1, f2));
        assertSame(x64.pointer(expected), x64.composite(x64.pointer(f1), x64.pointer(f2)));
        assertThrows(IllegalArgumentException.class, () -> x64.composite(x64.int_(), x64.long_()));
    }

    // ---- spelling ------------------------------------------------------------------------

    @Test
    void spelling() {
        assertEquals("int", x64.int_().spelling());
        assertEquals("unsigned long", x64.ulong().spelling());
        assertEquals("signed char", x64.schar().spelling());
        assertEquals("char", x64.char_().spelling());
        assertEquals("bool", x64.bool_().spelling());
        assertEquals("long double", x64.longDouble().spelling());
        assertEquals("const int", x64.qualified(x64.int_(), Quals.CONST).spelling());
        assertEquals("const char *", x64.pointer(x64.qualified(x64.char_(), Quals.CONST)).spelling());
        assertEquals("char * const", x64.qualified(x64.pointer(x64.char_()), Quals.CONST).spelling());
        assertEquals("void * *", x64.pointer(x64.pointer(x64.void_())).spelling());
        assertEquals("const volatile int",
                x64.qualified(x64.int_(), new Quals(true, true, false, false)).spelling());
    }

    @Test
    void declaratorSpelling() {
        CType i = x64.int_();
        assertEquals("int [3]", x64.array(i, 3).spelling());
        assertEquals("int []", x64.incompleteArray(i).spelling());
        assertEquals("int [2][3]", x64.array(x64.array(i, 3), 2).spelling());
        assertEquals("int *[3]", x64.array(x64.pointer(i), 3).spelling());
        assertEquals("int (*)[3]", x64.pointer(x64.array(i, 3)).spelling());
        assertEquals("int (void)", x64.function(i, List.of(), false).spelling());
        assertEquals("int (char, ...)", x64.function(i, List.of(x64.char_()), true).spelling());
        assertEquals("int (*)(char)", x64.pointer(x64.function(i, List.of(x64.char_()), false)).spelling());
        assertEquals("int (*(*)[3])(void)",
                x64.pointer(x64.array(x64.pointer(x64.function(i, List.of(), false)), 3)).spelling());
        assertEquals("char *(*)(int)", x64.pointer(x64.function(x64.pointer(x64.char_()), List.of(i), false)).spelling());
        assertEquals("const char *(void)", x64.function(x64.pointer(x64.qualified(x64.char_(), Quals.CONST)), List.of(), false).spelling());
        assertEquals("int * const *", x64.pointer(x64.qualified(x64.pointer(i), Quals.CONST)).spelling());
    }
}

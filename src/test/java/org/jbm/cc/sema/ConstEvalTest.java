package org.jbm.cc.sema;

import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TExpr.Constant;
import org.jbm.cc.tast.TExpr.Rvalue;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Ilp32;
import org.jbm.cc.types.Types;
import org.jbm.cc.types.X86_64SysV;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of constant evaluation on hand-built typed trees, so the
 * arithmetic rules are checked apart from how the typer builds them.
 */
class ConstEvalTest {

    private static final Token AT = new Token(TokenType.PUNCTUATOR, "+", 1, 1);

    private final Types types = new Types(X86_64SysV.INSTANCE);
    private final ConstEval eval = new ConstEval(types);

    private TExpr.IntConst i(long v, CType t) {
        return new TExpr.IntConst(v, t, AT);
    }

    private TExpr.IntConst i(long v) {
        return i(v, types.int_());
    }

    private TExpr.FloatConst f(double v, CType t) {
        return new TExpr.FloatConst(v, t, AT);
    }

    private String fold(Rvalue x) {
        Optional<Constant> c = eval.fold(x);
        if (c.isEmpty()) return "-";
        Constant k = c.get();
        if (k instanceof TExpr.IntConst n) return n.value() + ":" + n.type().spelling();
        if (k instanceof TExpr.FloatConst d) return d.value() + ":" + d.type().spelling();
        if (k instanceof TExpr.AddrConst a) return a.base().map(s -> "&" + s.name).orElse("null") + "+" + a.offset() + ":" + a.type().spelling();
        return k.type().spelling();
    }

    @Test
    void unsignedArithmeticWrapsAtTheTargetsWidth() {
        CType u = types.uint();
        assertEquals("0:unsigned int", fold(new TExpr.Add(i(0xFFFFFFFFL, u), i(1, u), u, AT)));
        assertEquals("4294967295:unsigned int", fold(new TExpr.Sub(i(0, u), i(1, u), u, AT)));
        assertEquals("1:unsigned int", fold(new TExpr.Mul(i(0x80000001L, u), i(0x80000001L, u), u, AT)));
        CType ul = types.ulong();
        assertEquals("0:unsigned long", fold(new TExpr.Add(i(-1, ul), i(1, ul), ul, AT)));
        assertEquals("-1:unsigned long", fold(new TExpr.Sub(i(0, ul), i(1, ul), ul, AT)), "all 64 bits set");
        CType uc = types.uchar();
        assertEquals("0:unsigned char", fold(new TExpr.Add(i(255, uc), i(1, uc), uc, AT)));
    }

    @Test
    void signedOverflowIsAnError() {
        CType t = types.int_();
        assertThrows(SemaException.class, () -> fold(new TExpr.Add(i(Integer.MAX_VALUE), i(1), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Sub(i(Integer.MIN_VALUE), i(1), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Mul(i(65536), i(65536), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Neg(i(Integer.MIN_VALUE), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Div(i(Integer.MIN_VALUE), i(-1), t, AT)));
        assertEquals("2147483647:int", fold(new TExpr.Sub(i(Integer.MAX_VALUE), i(0), t, AT)));
        CType l = types.long_();
        assertEquals("4294967296:long", fold(new TExpr.Mul(i(65536, l), i(65536, l), l, AT)));
    }

    @Test
    void divisionTruncatesTowardZeroAndRejectsZero() {
        CType t = types.int_();
        assertEquals("-3:int", fold(new TExpr.Div(i(-7), i(2), t, AT)));
        assertEquals("-1:int", fold(new TExpr.Rem(i(-7), i(2), t, AT)));
        assertEquals("1:int", fold(new TExpr.Rem(i(7), i(-2), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Div(i(1), i(0), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Rem(i(1), i(0), t, AT)));
        CType u = types.uint();
        assertEquals("1431655765:unsigned int", fold(new TExpr.Div(i(0xFFFFFFFFL, u), i(3, u), u, AT)), "unsigned division");
    }

    @Test
    void shiftsAndBitwise() {
        CType t = types.int_();
        assertEquals("-2147483648:int", fold(new TExpr.Shl(i(1), i(31), t, AT)), "wraps like the multiplication it is");
        assertEquals("-4:int", fold(new TExpr.Shr(i(-8), i(1), t, AT)), "arithmetic right shift");
        assertEquals("2147483647:int", fold(new TExpr.BitAnd(i(-1), i(Integer.MAX_VALUE), t, AT)));
        assertEquals("-1:int", fold(new TExpr.BitOr(i(-2), i(1), t, AT)));
        assertEquals("-1:int", fold(new TExpr.BitNot(i(0), t, AT)));
        assertEquals("6:int", fold(new TExpr.BitXor(i(5), i(3), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Shl(i(1), i(32), t, AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.Shl(i(1), i(-1), t, AT)));
        CType u = types.uint();
        assertEquals("1:unsigned int", fold(new TExpr.Shr(i(0x80000000L, u), i(31, u), u, AT)), "logical right shift");
    }

    @Test
    void conversionsNarrowAndTruncate() {
        assertEquals("44:char", fold(new TExpr.IntToInt(i(300), types.char_(), AT)));
        assertEquals("-1:signed char", fold(new TExpr.IntToInt(i(255), types.schar(), AT)));
        assertEquals("255:unsigned char", fold(new TExpr.IntToInt(i(-1), types.uchar(), AT)));
        assertEquals("-1:short", fold(new TExpr.IntToInt(i(65535), types.short_(), AT)));
        assertEquals("1:bool", fold(new TExpr.ToBool(i(300), types.bool_(), AT)));
        assertEquals("0:bool", fold(new TExpr.ToBool(f(0.0, types.double_()), types.bool_(), AT)));
        assertEquals("2:int", fold(new TExpr.FloatToInt(f(2.9, types.double_()), types.int_(), AT)));
        assertEquals("-2:int", fold(new TExpr.FloatToInt(f(-2.9, types.double_()), types.int_(), AT)));
        assertEquals("255:unsigned char", fold(new TExpr.FloatToInt(f(255.9, types.double_()), types.uchar(), AT)));
        assertEquals("1.0E10:double", fold(new TExpr.IntToFloat(i(10000000000L, types.long_()), types.double_(), AT)));
        assertEquals("0.10000000149011612:float", fold(new TExpr.FloatToFloat(f(0.1, types.double_()), types.float_(), AT)));
        assertEquals("1.8446744073709552E19:double", fold(new TExpr.IntToFloat(i(-1, types.ulong()), types.double_(), AT)),
                "an unsigned value converts by its mathematical value");
        assertThrows(SemaException.class, () -> fold(new TExpr.FloatToInt(f(1e100, types.double_()), types.int_(), AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.FloatToInt(f(-1.0, types.double_()), types.uint(), AT)));
        assertThrows(SemaException.class, () -> fold(new TExpr.FloatToInt(f(Double.NaN, types.double_()), types.int_(), AT)));
    }

    @Test
    void comparisonsLogicalAndConditional() {
        CType t = types.int_();
        CType u = types.uint();
        assertEquals("1:int", fold(new TExpr.Lt(i(-1), i(0), t, AT)));
        assertEquals("0:int", fold(new TExpr.Lt(i(-1, u), i(0, u), t, AT)), "unsigned: all bits set is the maximum");
        assertEquals("1:int", fold(new TExpr.Ge(i(0xFFFFFFFFL, u), i(1, u), t, AT)));
        assertEquals("1:int", fold(new TExpr.Eq(f(1.5, types.double_()), f(1.5, types.double_()), t, AT)));
        assertEquals("1:int", fold(new TExpr.And(i(1, types.bool_()), i(1, types.bool_()), t, AT)));
        assertEquals("0:int", fold(new TExpr.Not(i(1, types.bool_()), t, AT)));
        assertEquals("7:int", fold(new TExpr.Cond(i(1, types.bool_()), i(7), new TExpr.Div(i(1), i(0), t, AT), t, AT)),
                "only the chosen arm is evaluated");
    }

    @Test
    void nonConstantsDoNotFold() {
        var symbol = types.int_(); // any non-constant leaf: a call has no constant value
        Rvalue call = new TExpr.Call(new TExpr.IntToPtr(i(0), types.pointer(types.function(types.int_(), java.util.List.of(), false)), AT),
                java.util.List.of(), types.int_(), AT);
        assertEquals("-", fold(call));
        assertEquals("-", fold(new TExpr.Add(i(1), call, types.int_(), AT)));
        assertTrue(assertThrows(SemaException.class, () -> eval.require(call, AT, "thing")).getMessage().contains("thing is not a constant"));
        assertThrows(SemaException.class, () -> eval.requireInteger(f(1.5, types.double_()), AT, "thing"));
    }

    @Test
    void addressConstantsFollowPointerArithmetic() {
        CType ip = types.pointer(types.int_());
        var nul = new TExpr.NullToPtr(i(0), ip, AT);
        assertEquals("null+0:int *", fold(nul));
        assertEquals("null+8:int *", fold(new TExpr.PtrAdd(nul, i(2, types.long_()), ip, AT)));
        assertEquals("null+8:char *", fold(new TExpr.PtrToPtr(new TExpr.PtrAdd(nul, i(2, types.long_()), ip, AT), types.pointer(types.char_()), AT)));
        assertEquals("null+16:int *", fold(new TExpr.IntToPtr(i(16), ip, AT)));
        assertEquals("1:int", fold(new TExpr.Eq(nul, new TExpr.NullToPtr(i(0), ip, AT), types.int_(), AT)));
        assertEquals("0:bool", fold(new TExpr.ToBool(nul, types.bool_(), AT)));
        assertEquals("-", fold(new TExpr.PtrToInt(nul, types.long_(), AT)), "a pointer's integer value is not a constant");
    }

    @Test
    void widthsComeFromTheTarget() {
        var ilp32 = new ConstEval(new Types(Ilp32.INSTANCE));
        var t32 = new Types(Ilp32.INSTANCE);
        CType l = t32.long_();
        assertThrows(SemaException.class, () -> ilp32.require(new TExpr.Add(i(Integer.MAX_VALUE, l), i(1, l), l, AT), AT, "x"),
                "long is 32 bits there");
        Constant c = ilp32.require(new TExpr.IntToInt(i(0x1FF, t32.int_()), t32.char_(), AT), AT, "x");
        assertEquals(255, ((TExpr.IntConst) c).value(), "plain char is unsigned there");
    }
}

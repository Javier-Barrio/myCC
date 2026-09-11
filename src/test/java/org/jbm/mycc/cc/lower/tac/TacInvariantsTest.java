package org.jbm.mycc.cc.lower.tac;

import org.jbm.mycc.cc.lower.tac.*;
import org.jbm.mycc.cc.lower.tac.Module;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.jbm.mycc.cc.lower.tac.TacTest.AT;
import static org.jbm.mycc.cc.lower.tac.TacTest.X64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every rule the checker enforces, one acceptance and one rejection each. */
class TacInvariantsTest {

    static final Type.Func I_I = new Type.Func(List.of(Type.I32), false, Type.I32);

    /** A module with {@code @sq}, {@code @g : i32}, {@code %P}, and a function {@code f(i32 %x)} whose entry the test fills. */
    private static org.jbm.mycc.cc.lower.tac.Module module(Consumer<Function> body) {
        var m = new org.jbm.mycc.cc.lower.tac.Module(X64);
        m.structs.add(new StructDef("P", List.of(new StructDef.Member(Type.I32, 0)), 4, 4));
        m.globals.add(new Global("g", Linkage.EXTERNAL, Type.I32, 4, false, null));
        m.funcDecls.add(new org.jbm.mycc.cc.lower.tac.Module.FuncDecl("sq", I_I));
        var f = new Function("f", Linkage.EXTERNAL, I_I, List.of(new Var("x", Type.I32)));
        f.blocks.add(new Block("entry"));
        body.accept(f);
        m.functions.add(f);
        return m;
    }

    private static String rejects(Consumer<Function> body) {
        return assertThrows(IllegalStateException.class, () -> TacInvariants.check(module(body))).getMessage();
    }

    private static void accepts(Consumer<Function> body) {
        assertTrue(TacInvariants.check(module(body)) > 0);
    }

    private static Var x(Function f) {
        return f.params.get(0);
    }

    private static Var local(Function f, String name, Type t) {
        var v = new Var(name, t);
        f.locals.add(v);
        return v;
    }

    @Test
    void terminators() {
        accepts(f -> f.entry().instrs.add(new Instr.Ret(x(f), AT)));
        assertTrue(rejects(f -> f.entry().instrs.add(new Instr.Mov(x(f), new Operand.IntImm(1), Type.I32, AT))).contains("no terminator"));
        assertTrue(rejects(f -> {
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("terminator before the end"));
        assertTrue(rejects(f -> { }).contains("is empty"));
        assertTrue(rejects(f -> f.entry().instrs.add(new Instr.Br(new Block("other"), AT))).contains("outside the function"));
        assertTrue(rejects(f -> f.entry().instrs.add(new Instr.Br(f.entry(), AT))).contains("entry block has a predecessor"));
    }

    @Test
    void declarationsAndClasses() {
        assertTrue(rejects(f -> f.entry().instrs.add(new Instr.Ret(new Var("y", Type.I32), AT))).contains("not declared"));
        assertTrue(rejects(f -> {
            f.locals.add(x(f));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("declared twice"));
        assertTrue(rejects(f -> {
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Bin(Instr.BinOp.ADD, d, d, d, Type.F64, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("add on a FLOAT"));
        assertTrue(rejects(f -> {
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Bin(Instr.BinOp.ADD, x(f), x(f), d, Type.I32, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("not of class INT"));
        accepts(f -> {
            var l = local(f, "l", Type.I64);
            f.entry().instrs.add(new Instr.Mov(l, x(f), Type.I64, AT));
            f.entry().instrs.add(new Instr.Mov(x(f), l, Type.I32, AT));
            f.entry().instrs.add(new Instr.Bin(Instr.BinOp.ADD, l, l, x(f), Type.I64, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        });
        assertTrue(rejects(f -> {
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Mov(d, x(f), Type.F64, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("mov between INT and FLOAT"));
        assertTrue(rejects(f -> {
            f.entry().instrs.add(new Instr.Bin(Instr.BinOp.ADD, x(f), x(f), x(f), Type.F64, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("needs .sN or .uN"));
        assertTrue(rejects(f -> {
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Bin(Instr.BinOp.FADD, d, d, d, Type.I32, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("needs a precision"));
        assertTrue(rejects(f -> {
            var s = local(f, "s", new Type.Struct("Q"));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("unknown struct"));
    }

    @Test
    void comparisonsAndConversions() {
        accepts(f -> {
            var c = local(f, "c", Type.U8);
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Cmp(Instr.CmpOp.SLT, c, x(f), new Operand.IntImm(3), AT));
            f.entry().instrs.add(new Instr.Cvt(Instr.CvtOp.I2F, d, x(f), (Type.Float) Type.F64, AT));
            f.entry().instrs.add(new Instr.Cmp(Instr.CmpOp.FLT, c, d, new Operand.FloatImm(0.5), AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        });
        assertTrue(rejects(f -> {
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Cmp(Instr.CmpOp.EQ, d, x(f), x(f), AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("must be an integer"));
        assertTrue(rejects(f -> {
            var c = local(f, "c", Type.U8);
            f.entry().instrs.add(new Instr.Cmp(Instr.CmpOp.FLT, c, x(f), x(f), AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("flt on INT"));
        assertTrue(rejects(f -> {
            var l = local(f, "l", Type.I64);
            f.entry().instrs.add(new Instr.Cvt(Instr.CvtOp.F2I, l, x(f), (Type.Float) Type.F32, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("f2i"));
    }

    @Test
    void memory() {
        accepts(f -> {
            var p = local(f, "p", Type.PTR);
            var c = local(f, "c", Type.I8);
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.AddrOfGlobal(p, "g", AT));
            f.entry().instrs.add(new Instr.Load(c, p, 8, Instr.Ext.SIGNED, false, AT));
            f.entry().instrs.add(new Instr.Store(p, c, Type.I8, false, AT));
            f.entry().instrs.add(new Instr.Store(p, new Operand.IntImm(1), Type.I32, false, AT));
            f.entry().instrs.add(new Instr.Load(d, p, 64, Instr.Ext.FLOAT, false, AT));
            f.entry().instrs.add(new Instr.Store(p, p, new Type.Struct("P"), false, AT));
            f.entry().instrs.add(new Instr.Store(p, new Operand.IntImm(0), new Type.Struct("P"), false, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        });
        assertTrue(rejects(f -> {
            f.entry().instrs.add(new Instr.Load(x(f), x(f), 32, Instr.Ext.SIGNED, false, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("not a ptr"));
        assertTrue(rejects(f -> {
            var p = local(f, "p", Type.PTR);
            f.entry().instrs.add(new Instr.Load(x(f), p, 64, Instr.Ext.FLOAT, false, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("load.f into a INT"));
        assertTrue(rejects(f -> {
            var p = local(f, "p", Type.PTR);
            f.entry().instrs.add(new Instr.AddrOfGlobal(p, "nope", AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("unknown @nope"));
        assertTrue(rejects(f -> {
            var p = local(f, "p", Type.PTR);
            f.entry().instrs.add(new Instr.Store(p, x(f), new Type.Struct("P"), false, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("aggregate store takes a ptr"));
    }

    @Test
    void controlAndCalls() {
        accepts(f -> {
            var c = local(f, "c", Type.U8);
            var then = new Block("then");
            var els = new Block("else");
            f.blocks.add(then);
            f.blocks.add(els);
            f.entry().instrs.add(new Instr.Cmp(Instr.CmpOp.EQ, c, x(f), new Operand.IntImm(0), AT));
            f.entry().instrs.add(new Instr.Switch(x(f), els, List.of(new Instr.Case(1, then)), AT));
            then.instrs.add(new Instr.Call(x(f), I_I, "sq", List.of(x(f)), null, AT));
            then.instrs.add(new Instr.CondBr(c, then, els, AT));
            els.instrs.add(new Instr.Trap("x", AT));
        });
        assertTrue(rejects(f -> f.entry().instrs.add(new Instr.Switch(x(f), f.entry(), List.of(new Instr.Case(1, f.entry()), new Instr.Case(1, f.entry())), AT))).contains("duplicate case"));
        assertTrue(rejects(f -> f.entry().instrs.add(new Instr.Ret(null, AT))).contains("without a value"));
        assertTrue(rejects(f -> {
            f.entry().instrs.add(new Instr.Call(x(f), I_I, "nope", List.of(x(f)), null, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("unknown @nope"));
        assertTrue(rejects(f -> {
            f.entry().instrs.add(new Instr.Call(x(f), I_I, "sq", List.of(), null, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("too few"));
        assertTrue(rejects(f -> {
            var d = local(f, "d", Type.F64);
            f.entry().instrs.add(new Instr.Call(x(f), I_I, "sq", List.of(d), null, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("not of class INT"));
        assertTrue(rejects(f -> {
            var p = local(f, "p", Type.PTR);
            var asig = new Type.Func(List.of(), false, new Type.Struct("P"));
            f.entry().instrs.add(new Instr.ICall(null, asig, p, List.of(), null, AT));
            f.entry().instrs.add(new Instr.Ret(x(f), AT));
        }).contains("needs `into`"));
    }

    @Test
    void globals() {
        var m = new org.jbm.mycc.cc.lower.tac.Module(X64);
        m.globals.add(new Global("a", Linkage.EXTERNAL, new Type.Array(Type.I8, 4), 1, true, List.of(new Global.BytesItem(0, new byte[]{1, 2, 3, 4}))));
        m.globals.add(new Global("p", Linkage.EXTERNAL, Type.PTR, 8, false, List.of(new Global.AddrItem(0, "a", 2))));
        assertEquals(0, TacInvariants.check(m));
        var bad = new org.jbm.mycc.cc.lower.tac.Module(X64);
        bad.globals.add(new Global("a", Linkage.EXTERNAL, Type.I32, 4, false, List.of(new Global.IntItem(4, (Type.Int) Type.I32, 1))));
        assertTrue(assertThrows(IllegalStateException.class, () -> TacInvariants.check(bad)).getMessage().contains("outside the object"));
        var ro = new Module(X64);
        ro.globals.add(new Global("a", Linkage.EXTERNAL, Type.I32, 4, true, null));
        assertTrue(assertThrows(IllegalStateException.class, () -> TacInvariants.check(ro)).getMessage().contains("readonly without"));
    }
}

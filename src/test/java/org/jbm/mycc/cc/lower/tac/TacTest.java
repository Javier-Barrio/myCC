package org.jbm.mycc.cc.lower.tac;

import org.jbm.mycc.cc.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.lower.tac.*;
import org.jbm.mycc.cc.lower.tac.Module;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model and its text form on hand-built modules. */
class TacTest {

    static final Token AT = new Token(TokenType.IDENTIFIER, "", 0, 0);
    static final TargetDesc X64 = TargetDesc.of(X86_64SysV.INSTANCE);

    @Test
    void targetDescriptorAndClasses() {
        assertEquals("x86_64-sysv", X64.name());
        assertEquals(32, X64.wordWidth());
        assertEquals(64, X64.registerWidth());
        assertEquals(64, X64.pointerWidth());
        assertEquals(80, X64.longDoubleWidth());
        assertTrue(X64.hasPrecision(32) && X64.hasPrecision(64) && X64.hasPrecision(80) && !X64.hasPrecision(128));
        assertEquals(RegClass.INT, X64.classOf(Type.I8));
        assertEquals(RegClass.INT, X64.classOf(Type.U32));
        assertEquals(RegClass.INT, X64.classOf(Type.I64));
        assertEquals(RegClass.INT, X64.classOf(Type.PTR));
        assertEquals(RegClass.FLOAT, X64.classOf(Type.F32));
        assertEquals(RegClass.FLOAT, X64.classOf(Type.F64));
        assertEquals(RegClass.FLOAT, X64.classOf(Type.F80));
        assertEquals(RegClass.NONE, X64.classOf(new Type.Struct("P")));
        assertEquals(RegClass.NONE, X64.classOf(new Type.Array(Type.I32, 3)));
    }

    @Test
    void typeSpellings() {
        assertEquals("i32", Type.I32.spelling());
        assertEquals("u8", Type.U8.spelling());
        assertEquals("f64", Type.F64.spelling());
        assertEquals("ptr", Type.PTR.spelling());
        assertEquals("[3 x i32]", new Type.Array(Type.I32, 3).spelling());
        assertEquals("%P", new Type.Struct("P").spelling());
        assertEquals("(i32, f64) -> i32", new Type.Func(List.of(Type.I32, Type.F64), false, Type.I32).spelling());
        assertEquals("(ptr, ...) -> i32", new Type.Func(List.of(Type.PTR), true, Type.I32).spelling());
        assertEquals("(...) -> void", new Type.Func(List.of(), true, Type.VOID).spelling());
        assertEquals(Type.I32, new Type.Int(32, true));
    }

    @Test
    void everyNamedThingIsASymbol() {
        var m = new org.jbm.mycc.cc.lower.tac.Module(X64);
        m.globals.add(new Global("g", Linkage.EXTERNAL, Type.I32, 4, false, null));
        m.globalDecls.add(new org.jbm.mycc.cc.lower.tac.Module.GlobalDecl("errno", Type.I32));
        var sig = new Type.Func(List.of(Type.PTR), true, Type.I32);
        m.funcDecls.add(new org.jbm.mycc.cc.lower.tac.Module.FuncDecl("printf", sig));
        m.functions.add(new Function("f", Linkage.INTERNAL, sig, List.of(new Var("s", Type.PTR))));

        List<String> names = m.symbols().stream().map(Symbol::name).toList();
        assertEquals(List.of("g", "errno", "printf", "f"), names);
        List<Boolean> defined = m.symbols().stream().map(Symbol::isDefined).toList();
        assertEquals(List.of(true, false, false, true), defined);
        assertEquals(Type.I32, m.symbol("g").orElseThrow().type());
        assertEquals(sig, m.symbol("printf").orElseThrow().type());
        assertEquals(sig, m.symbol("f").orElseThrow().type());
        assertTrue(m.symbol("f").orElseThrow() instanceof Function);
        assertTrue(m.symbol("nope").isEmpty());
    }

    @Test
    void theExampleModulePrints() {
        var m = new org.jbm.mycc.cc.lower.tac.Module(X64);
        m.structs.add(new StructDef("P", List.of(new StructDef.Member(Type.I32, 0), new StructDef.Member(Type.I32, 4)), 8, 4));
        m.globals.add(new Global("counter", Linkage.EXTERNAL, Type.I32, 4, false, List.of(new Global.IntItem(0, (Type.Int) Type.I32, 0))));
        m.globals.add(new Global("greeting", Linkage.EXTERNAL, new Type.Array(Type.I8, 6), 1, true,
                List.of(new Global.BytesItem(0, "hello\0".getBytes()))));
        m.globals.add(new Global("bss", Linkage.INTERNAL, new Type.Array(Type.I32, 4), 4, false, null));
        m.funcDecls.add(new org.jbm.mycc.cc.lower.tac.Module.FuncDecl("printf", new Type.Func(List.of(Type.PTR), true, Type.I32)));
        m.globalDecls.add(new Module.GlobalDecl("errno", Type.I32));

        var x = new Var("x", Type.I32);
        var t0 = new Var("t0", Type.I32);
        var sq = new Function("sq", Linkage.EXTERNAL, new Type.Func(List.of(Type.I32), false, Type.I32), List.of(x));
        sq.locals.add(t0);
        var entry = new Block("entry");
        entry.instrs.add(new Instr.Bin(Instr.BinOp.MUL, t0, x, x, Type.I32, AT));
        entry.instrs.add(new Instr.Ret(t0, AT));
        sq.blocks.add(entry);
        m.functions.add(sq);

        var c = new Var("c", Type.I32);
        var xv = new Var("x", Type.I32);
        var p = new Var("p", Type.PTR);
        var f = new Function("f", Linkage.EXTERNAL, new Type.Func(List.of(Type.I32), false, Type.I32), List.of(c));
        f.locals.add(xv);
        f.locals.add(p);
        var fe = new Block("entry");
        fe.instrs.add(new Instr.Mov(xv, new Operand.IntImm(1), Type.I32, AT));
        fe.instrs.add(new Instr.AddrOfVar(p, c, AT));
        fe.instrs.add(new Instr.Store(p, new Operand.IntImm(5), Type.I32, false, AT));
        fe.instrs.add(new Instr.Ret(xv, AT));
        f.blocks.add(fe);
        m.functions.add(f);

        assertEquals("""
                target x86_64-sysv
                type %P = { i32 @0, i32 @4 } size 8 align 4
                global @counter : i32 align 4 = { 0 : i32 0 }
                global @greeting : [6 x i8] align 1 readonly = { 0 : bytes "hello\\00" }
                global internal @bss : [4 x i32] align 4
                declare @errno : i32
                declare @printf(ptr, ...) -> i32
                define @sq(i32 %x) -> i32 {
                  i32 %t0
                .entry:
                  %t0 = mul.s32 %x, %x
                  ret %t0
                }
                define @f(i32 %c) -> i32 {
                  i32 %x
                  ptr %p
                .entry:
                  mov.s32 %x, 1
                  %p = addrof %c
                  store.32 %p, 5
                  ret %x
                }
                """, TacWriter.print(m));
    }

    @Test
    void everyInstructionPrints() {
        var a = new Var("a", Type.I32);
        var b = new Var("b", Type.I32);
        var l = new Var("l", Type.I64);
        var d = new Var("d", Type.F64);
        var s = new Var("s", Type.F32);
        var p = new Var("p", Type.PTR);
        var q = new Var("q", Type.PTR);
        var c = new Var("c", Type.U8);
        var vol = new Var("v", Type.I32, true);
        var then = new Block("then");
        var els = new Block("else");
        var sig = new Type.Func(List.of(Type.I32, Type.F64), false, Type.I32);
        var vsig = new Type.Func(List.of(Type.PTR), true, Type.VOID);
        var asig = new Type.Func(List.of(), false, new Type.Struct("P"));
        List<Instr> all = List.of(
                new Instr.Mov(a, b, Type.I32, AT),
                new Instr.Mov(c, a, Type.U8, AT),
                new Instr.Mov(d, new Operand.FloatImm(1.5), Type.F64, AT),
                new Instr.AddrOfGlobal(p, "g", AT),
                new Instr.Bin(Instr.BinOp.WADD, a, a, new Operand.IntImm(-1), Type.U32, AT),
                new Instr.Bin(Instr.BinOp.ASHR, l, l, new Operand.IntImm(32), Type.I64, AT),
                new Instr.Bin(Instr.BinOp.FADD, d, d, d, Type.F64, AT),
                new Instr.Cmp(Instr.CmpOp.ULT, c, a, b, AT),
                new Instr.Cmp(Instr.CmpOp.FNE, c, d, new Operand.FloatImm(0.0), AT),
                new Instr.Cvt(Instr.CvtOp.I2F, d, a, (Type.Float) Type.F64, AT),
                new Instr.Mov(s, d, Type.F32, AT),
                new Instr.Load(c, p, 8, Instr.Ext.UNSIGNED, false, AT),
                new Instr.Load(l, p, 32, Instr.Ext.SIGNED, true, AT),
                new Instr.Load(d, p, 64, Instr.Ext.FLOAT, false, AT),
                new Instr.Store(p, d, Type.F64, false, AT),
                new Instr.Store(p, vol, Type.I32, true, AT),
                new Instr.Store(q, p, new Type.Struct("P"), false, AT),
                new Instr.Store(p, new Operand.IntImm(0), new Type.Array(Type.I32, 3), false, AT),
                new Instr.Br(then, AT),
                new Instr.CondBr(c, then, els, AT),
                new Instr.Switch(a, els, List.of(new Instr.Case(1, then), new Instr.Case(-2, els)), AT),
                new Instr.Switch(a, els, List.of(), AT),
                new Instr.Ret(null, AT),
                new Instr.Ret(p, AT),
                new Instr.Trap("end of \"f\"", AT),
                new Instr.Call(a, sig, "f", List.of(a, d), null, AT),
                new Instr.Call(null, vsig, "printf", List.of(p, new Operand.IntImm(3)), null, AT),
                new Instr.Call(null, asig, "mk", List.of(), p, AT),
                new Instr.ICall(a, sig, q, List.of(b, d), null, AT));
        assertEquals("""
                mov.s32 %a, %b
                mov.u8 %c, %a
                mov.64 %d, 1.5
                %p = addrof @g
                %a = wadd.u32 %a, -1
                %l = ashr.s64 %l, 32
                %d = fadd.64 %d, %d
                %c = ult %a, %b
                %c = fne %d, 0.0
                %d = i2f.64 %a
                mov.32 %s, %d
                %c = load.u8 %p
                %l = load.s32 %p volatile
                %d = load.f64 %p
                store.f64 %p, %d
                store.32 %p, %v volatile
                store.%P %q, %p
                store.[3 x i32] %p, 0
                br .then
                condbr %c, .then, .else
                switch %a, .else, [ 1 -> .then, -2 -> .else ]
                switch %a, .else, []
                ret
                ret %p
                trap "end of \\"f\\""
                %a = call (i32, f64) -> i32 @f(%a, %d)
                call (ptr, ...) -> void @printf(%p, 3)
                call () -> %P @mk() into %p
                %a = icall (i32, f64) -> i32 %q(%b, %d)""",
                String.join("\n", all.stream().map(TacWriter::print).toList()));
    }
}

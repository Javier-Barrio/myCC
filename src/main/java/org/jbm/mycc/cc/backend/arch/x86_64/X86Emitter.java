package org.jbm.mycc.cc.backend.arch.x86_64;

import org.jbm.mycc.cc.backend.codegen.Asm;
import org.jbm.mycc.cc.backend.codegen.Backend;
import org.jbm.mycc.cc.backend.codegen.Frame;
import org.jbm.mycc.cc.backend.codegen.Operand;
import org.jbm.mycc.cc.backend.lower.tac.Block;
import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.Instr;
import org.jbm.mycc.cc.backend.lower.tac.Linkage;
import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.backend.lower.tac.Operand.IntImm;
import org.jbm.mycc.cc.backend.lower.tac.RegClass;
import org.jbm.mycc.cc.backend.lower.tac.TacVisitor;
import org.jbm.mycc.cc.backend.lower.tac.TacWriter;
import org.jbm.mycc.cc.backend.lower.tac.Type;
import org.jbm.mycc.cc.backend.lower.tac.Var;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.jbm.mycc.cc.backend.codegen.Operand.imm;
import static org.jbm.mycc.cc.backend.codegen.Operand.mem;
import static org.jbm.mycc.cc.backend.codegen.Operand.reg;
import static org.jbm.mycc.cc.backend.codegen.Operand.sym;
import static org.jbm.mycc.cc.backend.arch.x86_64.X86Abi.part;
import static org.jbm.mycc.cc.backend.arch.x86_64.X86Abi.suffix;

/**
 * x86-64, one method per TAC instruction, building the assembly IR
 * with AT&T operand order. Every variable lives in a frame slot; an
 * instruction reads its operands into {@code %rax}, {@code %rcx} or
 * {@code %xmm0}, {@code %xmm1}, computes, and writes the result to its
 * slot. A scalar read extends per the variable's type, so a register
 * always holds a value the way the TAC says it is held; a write stores
 * the type's width. Floating values are held as doubles in registers.
 */
public final class X86Emitter implements Backend, TacVisitor<Void> {

    private static final Operand RAX = reg("rax");
    private static final Operand RCX = reg("rcx");
    private static final Operand RDX = reg("rdx");
    private static final Operand RSI = reg("rsi");
    private static final Operand RDI = reg("rdi");
    private static final Operand RSP = reg("rsp");
    private static final Operand RBP = reg("rbp");
    private static final Operand EAX = reg("eax");
    private static final Operand EDX = reg("edx");
    private static final Operand AL = reg("al");
    private static final Operand CL = reg("cl");
    private static final Operand XMM0 = reg("xmm0");
    private static final Operand XMM1 = reg("xmm1");

    private Asm asm;
    private Module module;
    private Function function;
    private Frame frame;

    // For a function returning an aggregate: the slot holding the hidden
    // first argument, the address the caller wants the result at.
    private long intoSlot;
    private boolean returnsAggregate;

    // Floating constants, emitted after the code as 8-byte words in
    // .rodata and loaded PC-relative: the instruction set has no
    // floating immediates.
    private final LinkedHashMap<Long, String> pool = new LinkedHashMap<>();
    private int constants;

    @Override
    public String target() {
        return "x86_64-sysv";
    }

    @Override
    public org.jbm.mycc.cc.backend.codegen.Encoder encoder() {
        return new X86Encoder();
    }

    @Override
    public void function(Asm asm, Module module, Function function) {
        this.asm = asm;
        this.module = module;
        this.function = function;
        this.frame = new Frame(module, function);
        this.returnsAggregate = isAggregate(function.sig.ret());
        if (returnsAggregate) {
            intoSlot = frame.reserve(8, 8);
        }
        asm.note(TacWriter.print(function).split("\n")[0].replace(" {", ""));
        if (function.linkage == Linkage.EXTERNAL) {
            asm.global(function.name);
        }
        asm.label(function.name);
        prologue();
        for (Block b : function.blocks) {
            asm.label(label(b));
            for (Instr i : b.instrs) {
                asm.comment(TacWriter.print(i));
                i.accept(this);
            }
        }
        emitPool();
    }

    // An aggregate has no register class, and neither has void, which is
    // not an aggregate.
    private static boolean isAggregate(Type t) {
        return t instanceof Type.Array || t instanceof Type.Struct;
    }

    private String label(Block b) {
        return ".L_" + function.name + "_" + b.name;
    }

    private Operand.RipRel constant(double d) {
        long bits = Double.doubleToRawLongBits(d);
        return Operand.rip(pool.computeIfAbsent(bits, b -> ".LC" + constants++));
    }

    private void emitPool() {
        if (pool.isEmpty()) {
            return;
        }
        asm.section(".section .rodata");
        asm.align(8);
        pool.forEach((bits, name) -> {
            asm.label(name);
            asm.word(bits, Double.toString(Double.longBitsToDouble(bits)));
        });
        asm.section(".text");
        pool.clear();
    }

    // ---- frame ---------------------------------------------------------------------------------

    private void prologue() {
        asm.insn("pushq", RBP);
        asm.insn("movq", RSP, RBP);
        StringBuilder slots = new StringBuilder();
        for (Var v : frame.variables()) {
            if (slots.length() > 0) {
                slots.append(", ");
            }
            slots.append(v).append(" at ").append(frame.offset(v)).append("(%rbp)");
        }
        asm.insn("subq", imm(frame.size()), RSP);
        asm.comment(slots.toString());
        spillParameters();
    }

    // Each parameter from where the caller put it into its slot: the
    // integer and floating registers in order, then the stack above the
    // return address, 8 bytes each. An aggregate arrives as a pointer to
    // its bytes and is copied once every scalar is in its slot, since
    // the copy uses %rsi, %rdi and %rcx. A function returning an
    // aggregate gets the address to write it at as a hidden first
    // integer argument.
    private void spillParameters() {
        int ints = 0;
        int floats = 0;
        int stack = 0;
        if (returnsAggregate) {
            asm.comment("the result's address from its argument register");
            asm.insn("movq", reg(X86Abi.INT_ARGS.get(ints++)), mem(intoSlot, "rbp"));
        }
        List<Var> aggregates = new ArrayList<>();
        List<Operand> pointers = new ArrayList<>();
        for (Var p : function.params) {
            RegClass c = module.target.classOf(p.type);
            if (c == RegClass.FLOAT) {
                if (floats < X86Abi.FLOAT_ARGS.size()) {
                    asm.comment(p + " from its argument register");
                    writeFloatFromAbi(X86Abi.FLOAT_ARGS.get(floats++), p);
                } else {
                    asm.comment(p + " from the stack");
                    asm.insn(width(p.type) == 32 ? "movss" : "movsd", mem(16 + 8 * stack++, "rbp"), XMM0);
                    writeFloatFromAbi("xmm0", p);
                }
                continue;
            }
            boolean inRegister = ints < X86Abi.INT_ARGS.size();
            String register = inRegister ? X86Abi.INT_ARGS.get(ints++) : null;
            Operand from = inRegister ? reg(register) : mem(16 + 8 * stack++, "rbp");
            if (c == RegClass.NONE) {
                aggregates.add(p);
                pointers.add(from);
            } else if (inRegister) {
                asm.comment(p + " from its argument register");
                write(register, p);
            } else {
                asm.comment(p + " from the stack");
                asm.insn("movq", from, RAX);
                write("rax", p);
            }
        }
        for (int k = 0; k < aggregates.size(); k++) {
            Var p = aggregates.get(k);
            asm.comment(p + " copied from the address in its argument");
            asm.insn("movq", pointers.get(k), RSI);
            asm.insn("leaq", slot(p), RDI);
            asm.insn("movq", imm(module.sizeOf(p.type)), RCX);
            asm.insn("rep movsb");
        }
    }

    private Operand slot(Var v) {
        return mem(frame.offset(v), "rbp");
    }

    // ---- reads and writes ----------------------------------------------------------------------

    private static int width(Type t) {
        if (t instanceof Type.Int i) {
            return i.width();
        }
        if (t instanceof Type.Float f) {
            return f.width();
        }
        return 64;
    }

    // A scalar variable's value into a register, extended per its type.
    private void read(Var v, String register) {
        if (v.type instanceof Type.Int i) {
            extendFrom(slot(v), i.width(), i.signed(), register);
        } else if (v.type instanceof Type.Ptr) {
            asm.insn("movq", slot(v), reg(register));
        } else {
            throw new IllegalStateException(v + " is not an integer");
        }
    }

    // `src`, a memory operand or a register part, extended from `width` bits into a 64-bit register.
    private void extendFrom(Operand src, int width, boolean signed, String register) {
        switch (width) {
            case 64 -> asm.insn("movq", src, reg(register));
            case 32 -> {
                if (signed) {
                    asm.insn("movslq", src, reg(register));
                } else {
                    asm.insn("movl", src, reg(part(register, 32)));
                }
            }
            default -> asm.insn((signed ? "movs" : "movz") + suffix(width) + "q", src, reg(register));
        }
    }

    private void read(org.jbm.mycc.cc.backend.lower.tac.Operand o, String register) {
        if (o instanceof IntImm x) {
            immediate(x.value(), register);
        } else {
            read((Var) o, register);
        }
    }

    private void immediate(long value, String register) {
        asm.insn(value == (int) value ? "movq" : "movabsq", imm(value), reg(register));
    }

    // A register's low bits into a scalar variable's slot, at the type's width.
    private void write(String register, Var v) {
        int w = width(v.type);
        asm.insn("mov" + suffix(w), reg(part(register, w)), slot(v));
    }

    // The low N bits of a register extended in place, as a modifier says.
    private void extend(String register, Type.Int mod) {
        if (mod.width() == 64) {
            return;
        }
        extendFrom(reg(part(register, mod.width())), mod.width(), mod.signed(), register);
    }

    private void readFloat(org.jbm.mycc.cc.backend.lower.tac.Operand o, String xmm) {
        if (o instanceof org.jbm.mycc.cc.backend.lower.tac.Operand.FloatImm x) {
            asm.insn("movsd", constant(x.value()), reg(xmm));
            return;
        }
        Var v = (Var) o;
        if (width(v.type) == 32) {
            asm.insn("movss", slot(v), reg(xmm));
            asm.insn("cvtss2sd", reg(xmm), reg(xmm));
        } else {
            asm.insn("movsd", slot(v), reg(xmm));
        }
    }

    private void writeFloat(String xmm, Var v) {
        if (width(v.type) == 32) {
            asm.insn("cvtsd2ss", reg(xmm), reg(xmm));
            asm.insn("movss", reg(xmm), slot(v));
        } else {
            asm.insn("movsd", reg(xmm), slot(v));
        }
    }

    // A floating value as the ABI passes it, single or double per the variable's own type.
    private void writeFloatFromAbi(String xmm, Var v) {
        asm.insn(width(v.type) == 32 ? "movss" : "movsd", reg(xmm), slot(v));
    }

    private void roundToSingle(Operand xmm) {
        asm.insn("cvtsd2ss", xmm, xmm);
        asm.insn("cvtss2sd", xmm, xmm);
    }

    // ---- variables and addresses ---------------------------------------------------------------

    @Override
    public Void visit(Instr.Mov i) {
        if (i.mod() instanceof Type.Int mod) {
            read(i.src(), "rax");
            extend("rax", mod);
            write("rax", i.dst());
        } else {
            readFloat(i.src(), "xmm0");
            if (((Type.Float) i.mod()).width() == 32) {
                roundToSingle(XMM0);
            }
            writeFloat("xmm0", i.dst());
        }
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfVar i) {
        asm.insn("leaq", slot(i.var()), RAX);
        write("rax", i.dst());
        return null;
    }

    // A symbol of this module is PC-relative; one from elsewhere, a
    // library function or object, comes through the GOT, as a position
    // independent executable requires.
    @Override
    public Void visit(Instr.AddrOfGlobal i) {
        if (defined(i.name())) {
            asm.insn("leaq", Operand.rip(i.name()), RAX);
        } else {
            asm.insn("movq", Operand.got(i.name()), RAX);
        }
        write("rax", i.dst());
        return null;
    }

    private boolean defined(String name) {
        return module.functions.stream().anyMatch(f -> f.name.equals(name))
                || module.globals.stream().anyMatch(g -> g.name().equals(name));
    }

    // ---- arithmetic, comparison, conversion ----------------------------------------------------

    @Override
    public Void visit(Instr.Bin i) {
        if (i.mod() instanceof Type.Int mod) {
            read(i.a(), "rax");
            read(i.b(), "rcx");
            integerOp(i.op(), mod);
            extend("rax", mod);
            write("rax", i.dst());
        } else {
            readFloat(i.a(), "xmm0");
            readFloat(i.b(), "xmm1");
            String op = switch (i.op()) {
                case FADD -> "addsd";
                case FSUB -> "subsd";
                case FMUL -> "mulsd";
                case FDIV -> "divsd";
                default -> throw new IllegalStateException(i.op() + " is not a floating operation");
            };
            asm.insn(op, XMM1, XMM0);
            if (((Type.Float) i.mod()).width() == 32) {
                roundToSingle(XMM0);
            }
            writeFloat("xmm0", i.dst());
        }
        return null;
    }

    // %rax op %rcx into %rax, at 64 bits; the unsigned operations and the
    // shifts first bring their operands to the modifier's width.
    private void integerOp(Instr.BinOp op, Type.Int mod) {
        Type.Int unsigned = new Type.Int(mod.width(), false);
        Type.Int signed = new Type.Int(mod.width(), true);
        switch (op) {
            case WADD, ADD -> asm.insn("addq", RCX, RAX);
            case WSUB, SUB -> asm.insn("subq", RCX, RAX);
            case WMUL, MUL -> asm.insn("imulq", RCX, RAX);
            case SDIV, SREM -> {
                asm.insn("cqto");
                asm.insn("idivq", RCX);
                if (op == Instr.BinOp.SREM) {
                    asm.insn("movq", RDX, RAX);
                }
            }
            case UDIV, UREM -> {
                extend("rax", unsigned);
                extend("rcx", unsigned);
                asm.insn("xorl", EDX, EDX);
                asm.insn("divq", RCX);
                if (op == Instr.BinOp.UREM) {
                    asm.insn("movq", RDX, RAX);
                }
            }
            case AND -> asm.insn("andq", RCX, RAX);
            case OR -> asm.insn("orq", RCX, RAX);
            case XOR -> asm.insn("xorq", RCX, RAX);
            case SHL -> asm.insn("shlq", CL, RAX);
            case LSHR -> {
                extend("rax", unsigned);
                asm.insn("shrq", CL, RAX);
            }
            case ASHR -> {
                extend("rax", signed);
                asm.insn("sarq", CL, RAX);
            }
            default -> throw new IllegalStateException(op + " is not an integer operation");
        }
    }

    // The comparison's truth into %al, zero-extended; the operands are
    // held extended, so a plain 64-bit compare is right at every width.
    @Override
    public Void visit(Instr.Cmp i) {
        if (i.op().isFloating()) {
            readFloat(i.a(), "xmm0");
            readFloat(i.b(), "xmm1");
            floatingCompare(i.op());
        } else {
            read(i.a(), "rax");
            read(i.b(), "rcx");
            asm.insn("cmpq", RCX, RAX);
            String set = switch (i.op()) {
                case EQ -> "sete";
                case NE -> "setne";
                case SLT -> "setl";
                case SLE -> "setle";
                case ULT -> "setb";
                case ULE -> "setbe";
                default -> throw new IllegalStateException();
            };
            asm.insn(set, AL);
        }
        asm.insn("movzbq", AL, RAX);
        write("rax", i.dst());
        return null;
    }

    // ucomisd sets CF for below and PF for unordered: `a < b` is `b above a`
    // so that a NaN makes it false; equality also needs the parity clear.
    private void floatingCompare(Instr.CmpOp op) {
        switch (op) {
            case FEQ -> {
                asm.insn("ucomisd", XMM1, XMM0);
                asm.insn("sete", AL);
                asm.insn("setnp", CL);
                asm.insn("andb", CL, AL);
            }
            case FNE -> {
                asm.insn("ucomisd", XMM1, XMM0);
                asm.insn("setne", AL);
                asm.insn("setp", CL);
                asm.insn("orb", CL, AL);
            }
            case FLT -> {
                asm.insn("ucomisd", XMM0, XMM1);
                asm.insn("seta", AL);
            }
            case FLE -> {
                asm.insn("ucomisd", XMM0, XMM1);
                asm.insn("setae", AL);
            }
            default -> throw new IllegalStateException();
        }
    }

    // Between the integer and the floating file; the unsigned forms go
    // through the signed instructions with the top bit handled apart.
    @Override
    public Void visit(Instr.Cvt i) {
        switch (i.op()) {
            case I2F -> {
                read(i.src(), "rax");
                asm.insn("cvtsi2sdq", RAX, XMM0);
                writeFloat("xmm0", i.dst());
            }
            case U2F -> {
                read(i.src(), "rax");
                asm.insn("testq", RAX, RAX);
                asm.insn("js", sym("1f"));
                asm.insn("cvtsi2sdq", RAX, XMM0);
                asm.insn("jmp", sym("2f"));
                asm.label("1");
                asm.insn("movq", RAX, RCX);
                asm.insn("shrq", imm(1), RCX);
                asm.insn("andl", imm(1), EAX);
                asm.insn("orq", RAX, RCX);
                asm.insn("cvtsi2sdq", RCX, XMM0);
                asm.insn("addsd", XMM0, XMM0);
                asm.label("2");
                writeFloat("xmm0", i.dst());
            }
            case F2I -> {
                readFloat(i.src(), "xmm0");
                asm.insn("cvttsd2siq", XMM0, RAX);
                extend("rax", (Type.Int) i.dst().type);
                write("rax", i.dst());
            }
            case F2U -> {
                readFloat(i.src(), "xmm0");
                asm.insn("movsd", constant(9223372036854775808.0), XMM1);
                asm.insn("ucomisd", XMM1, XMM0);
                asm.insn("jae", sym("1f"));
                asm.insn("cvttsd2siq", XMM0, RAX);
                asm.insn("jmp", sym("2f"));
                asm.label("1");
                asm.insn("subsd", XMM1, XMM0);
                asm.insn("cvttsd2siq", XMM0, RAX);
                asm.insn("btcq", imm(63), RAX);
                asm.label("2");
                extend("rax", (Type.Int) i.dst().type);
                write("rax", i.dst());
            }
        }
        return null;
    }

    // ---- memory --------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Load i) {
        read(i.ptr(), "rcx");
        Operand at = mem(0, "rcx");
        if (i.ext() == Instr.Ext.FLOAT) {
            if (i.width() == 32) {
                asm.insn("movss", at, XMM0);
                asm.insn("cvtss2sd", XMM0, XMM0);
            } else {
                asm.insn("movsd", at, XMM0);
            }
            writeFloat("xmm0", i.dst());
        } else {
            extendFrom(at, i.width(), i.ext() == Instr.Ext.SIGNED, "rax");
            write("rax", i.dst());
        }
        return null;
    }

    // A scalar at its width; an aggregate as a byte copy from the
    // pointer the value holds, or a byte fill for the immediate 0.
    @Override
    public Void visit(Instr.Store i) {
        Type t = i.type();
        Operand at = mem(0, "rcx");
        if (t instanceof Type.Int n) {
            read(i.value(), "rax");
            read(i.ptr(), "rcx");
            asm.insn("mov" + suffix(n.width()), reg(part("rax", n.width())), at);
        } else if (t instanceof Type.Ptr) {
            read(i.value(), "rax");
            read(i.ptr(), "rcx");
            asm.insn("movq", RAX, at);
        } else if (t instanceof Type.Float f) {
            readFloat(i.value(), "xmm0");
            read(i.ptr(), "rcx");
            if (f.width() == 32) {
                asm.insn("cvtsd2ss", XMM0, XMM0);
                asm.insn("movss", XMM0, at);
            } else {
                asm.insn("movsd", XMM0, at);
            }
        } else {
            read(i.ptr(), "rdi");
            asm.insn("movq", imm(module.sizeOf(t)), RCX);
            if (i.value() instanceof IntImm) {
                asm.insn("xorl", EAX, EAX);
                asm.insn("rep stosb");
            } else {
                read(i.value(), "rsi");
                asm.insn("rep movsb");
            }
        }
        return null;
    }

    // ---- control -------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Br i) {
        asm.insn("jmp", sym(label(i.target())));
        return null;
    }

    @Override
    public Void visit(Instr.CondBr i) {
        read(i.cond(), "rax");
        asm.insn("testq", RAX, RAX);
        asm.insn("jne", sym(label(i.then())));
        asm.insn("jmp", sym(label(i.otherwise())));
        return null;
    }

    // A chain of compares; a case value beyond 32 bits goes through %rcx.
    @Override
    public Void visit(Instr.Switch i) {
        read(i.value(), "rax");
        for (Instr.Case c : i.cases()) {
            if (c.value() == (int) c.value()) {
                asm.insn("cmpq", imm(c.value()), RAX);
            } else {
                asm.insn("movabsq", imm(c.value()), RCX);
                asm.insn("cmpq", RCX, RAX);
            }
            asm.insn("je", sym(label(c.target())));
        }
        asm.insn("jmp", sym(label(i.dflt())));
        return null;
    }

    // The value in %rax or %xmm0 at the width the signature says. An
    // aggregate's bytes, whose address the operand holds, are copied to
    // the caller's address, which is returned in %rax.
    @Override
    public Void visit(Instr.Ret i) {
        if (i.value() != null) {
            RegClass c = module.target.classOf(function.sig.ret());
            if (c == RegClass.FLOAT) {
                readFloat(i.value(), "xmm0");
                if (width(function.sig.ret()) == 32) {
                    asm.insn("cvtsd2ss", XMM0, XMM0);
                }
            } else if (isAggregate(function.sig.ret())) {
                read(i.value(), "rsi");
                asm.insn("movq", mem(intoSlot, "rbp"), RDI);
                asm.insn("movq", imm(module.sizeOf(function.sig.ret())), RCX);
                asm.insn("rep movsb");
                asm.insn("movq", mem(intoSlot, "rbp"), RAX);
            } else {
                read(i.value(), "rax");
            }
        }
        asm.insn("leave");
        asm.insn("ret");
        return null;
    }

    @Override
    public Void visit(Instr.Trap i) {
        asm.insn("ud2");
        return null;
    }

    // ---- calls ---------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Call i) {
        call(i.sig(), i.args(), i.into(), i.dst(), () -> asm.insn("call", defined(i.callee()) ? sym(i.callee()) : Operand.plt(i.callee())));
        return null;
    }

    @Override
    public Void visit(Instr.ICall i) {
        // %r10 is neither an argument register nor %rax, which a variadic call uses
        call(i.sig(), i.args(), i.into(), i.dst(), () -> {
            read(i.callee(), "r10");
            asm.insn("icall", reg("r10"));
        });
        return null;
    }

    // One argument and how it travels: by its class, single when the
    // callee's own parameter is an f32.
    private record Arg(org.jbm.mycc.cc.backend.lower.tac.Operand operand, RegClass klass, boolean single) {
    }

    // SysV: integers and pointers in six registers, floating values in
    // eight, the rest on the stack right to left with the stack kept
    // 16-aligned at the call; %al counts the vector registers for a
    // variadic callee. An aggregate result's address is the hidden first
    // argument; an aggregate argument is already the pointer the TAC
    // passes. The result goes from %rax or %xmm0 to dst.
    private void call(Type.Func sig, List<org.jbm.mycc.cc.backend.lower.tac.Operand> operands, Var into, Var dst, Runnable emitCall) {
        List<Arg> args = new ArrayList<>();
        if (into != null) {
            args.add(new Arg(into, RegClass.INT, false));
        }
        for (int k = 0; k < operands.size(); k++) {
            org.jbm.mycc.cc.backend.lower.tac.Operand o = operands.get(k);
            RegClass c;
            if (o instanceof Var v) {
                c = module.target.classOf(v.type);
            } else if (o instanceof org.jbm.mycc.cc.backend.lower.tac.Operand.FloatImm) {
                c = RegClass.FLOAT;
            } else {
                c = RegClass.INT;
            }
            boolean single = k < sig.params().size() && sig.params().get(k) instanceof Type.Float f && f.width() == 32;
            args.add(new Arg(o, c == RegClass.NONE ? RegClass.INT : c, single));
        }
        List<Arg> onStack = new ArrayList<>();
        List<String> intRegs = new ArrayList<>();
        List<Arg> intArgs = new ArrayList<>();
        List<String> floatRegs = new ArrayList<>();
        List<Arg> floatArgs = new ArrayList<>();
        for (Arg a : args) {
            if (a.klass() == RegClass.FLOAT && floatRegs.size() < X86Abi.FLOAT_ARGS.size()) {
                floatRegs.add(X86Abi.FLOAT_ARGS.get(floatRegs.size()));
                floatArgs.add(a);
            } else if (a.klass() == RegClass.INT && intRegs.size() < X86Abi.INT_ARGS.size()) {
                intRegs.add(X86Abi.INT_ARGS.get(intRegs.size()));
                intArgs.add(a);
            } else {
                onStack.add(a);
            }
        }
        int pad = onStack.size() % 2 == 1 ? 8 : 0;
        if (pad > 0) {
            asm.insn("subq", imm(8), RSP);
        }
        for (int k = onStack.size() - 1; k >= 0; k--) {
            Arg a = onStack.get(k);
            if (a.klass() == RegClass.FLOAT) {
                readFloat(a.operand(), "xmm0");
                asm.insn("subq", imm(8), RSP);
                if (a.single()) {
                    asm.insn("cvtsd2ss", XMM0, XMM0);
                    asm.insn("movss", XMM0, mem(0, "rsp"));
                } else {
                    asm.insn("movsd", XMM0, mem(0, "rsp"));
                }
            } else {
                read(a.operand(), "rax");
                asm.insn("pushq", RAX);
            }
        }
        for (int k = 0; k < intArgs.size(); k++) {
            read(intArgs.get(k).operand(), intRegs.get(k));
        }
        for (int k = 0; k < floatArgs.size(); k++) {
            readFloat(floatArgs.get(k).operand(), floatRegs.get(k));
            if (floatArgs.get(k).single()) {
                asm.insn("cvtsd2ss", reg(floatRegs.get(k)), reg(floatRegs.get(k)));
            }
        }
        if (sig.variadic()) {
            asm.insn("movl", imm(floatRegs.size()), EAX);
        }
        emitCall.run();
        int popped = 8 * onStack.size() + pad;
        if (popped > 0) {
            asm.insn("addq", imm(popped), RSP);
        }
        if (dst != null) {
            if (module.target.classOf(dst.type) == RegClass.FLOAT) {
                if (width(sig.ret()) == 32) {
                    asm.insn("cvtss2sd", XMM0, XMM0);
                }
                writeFloat("xmm0", dst);
            } else {
                write("rax", dst);
            }
        }
    }
}

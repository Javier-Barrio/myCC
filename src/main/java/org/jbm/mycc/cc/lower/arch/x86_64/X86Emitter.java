package org.jbm.mycc.cc.lower.arch.x86_64;

import org.jbm.mycc.cc.codegen.Asm;
import org.jbm.mycc.cc.codegen.Backend;
import org.jbm.mycc.cc.codegen.Frame;
import org.jbm.mycc.cc.lower.tac.Block;
import org.jbm.mycc.cc.lower.tac.Function;
import org.jbm.mycc.cc.lower.tac.Instr;
import org.jbm.mycc.cc.lower.tac.Linkage;
import org.jbm.mycc.cc.lower.tac.Module;
import org.jbm.mycc.cc.lower.tac.Operand;
import org.jbm.mycc.cc.lower.tac.RegClass;
import org.jbm.mycc.cc.lower.tac.TacVisitor;
import org.jbm.mycc.cc.lower.tac.TacWriter;
import org.jbm.mycc.cc.lower.tac.Type;
import org.jbm.mycc.cc.lower.tac.Var;

import static org.jbm.mycc.cc.lower.arch.x86_64.X86Abi.part;
import static org.jbm.mycc.cc.lower.arch.x86_64.X86Abi.suffix;

/**
 * x86-64 in AT&T syntax, one method per TAC instruction. Every variable
 * lives in a frame slot; an instruction reads its operands into
 * {@code %rax}, {@code %rcx} or {@code %xmm0}, {@code %xmm1}, computes,
 * and writes the result to its slot. A scalar read extends per the
 * variable's type, so a register always holds a value the way the TAC
 * says it is held; a write stores the type's width. Floating values
 * are held as doubles in registers.
 */
public final class X86Emitter implements Backend, TacVisitor<Void> {

    private Asm asm;
    private Module module;
    private Function function;
    private Frame frame;

    // Floating constants, emitted after the code as 8-byte words in
    // .rodata and loaded PC-relative: the instruction set has no
    // floating immediates.
    private final java.util.LinkedHashMap<Long, String> pool = new java.util.LinkedHashMap<>();

    private String constant(double d) {
        long bits = Double.doubleToRawLongBits(d);
        return pool.computeIfAbsent(bits, b -> ".LC" + pool.size());
    }

    private void emitPool() {
        if (pool.isEmpty()) {
            return;
        }
        asm.directive(".section .rodata");
        asm.directive(".balign 8");
        pool.forEach((bits, label) -> {
            asm.label(label);
            asm.directive(".quad " + bits + "   # " + Double.longBitsToDouble(bits));
        });
        asm.directive(".text");
        pool.clear();
    }

    @Override
    public String target() {
        return "x86_64-sysv";
    }

    @Override
    public void function(Asm asm, Module module, Function function) {
        this.asm = asm;
        this.module = module;
        this.function = function;
        this.frame = new Frame(module, function);
        asm.note(TacWriter.print(function).split("\n")[0].replace(" {", ""));
        if (function.linkage == Linkage.EXTERNAL) {
            asm.directive(".globl " + function.name);
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

    private String label(Block b) {
        return ".L_" + function.name + "_" + b.name;
    }

    // ---- frame ---------------------------------------------------------------------------------

    private void prologue() {
        asm.insn("pushq", "%rbp");
        asm.insn("movq", "%rsp", "%rbp");
        StringBuilder slots = new StringBuilder();
        for (Var v : frame.variables()) {
            if (slots.length() > 0) {
                slots.append(", ");
            }
            slots.append(v).append(" at ").append(slot(v));
        }
        asm.insn("subq", "$" + frame.size(), "%rsp");
        asm.comment(slots.toString());
        spillParameters();
    }

    // Each parameter from where the caller put it into its slot: the
    // integer and floating registers in order; the rest is step 5.
    private void spillParameters() {
        int ints = 0;
        int floats = 0;
        for (Var p : function.params) {
            RegClass c = module.target.classOf(p.type);
            if (c == RegClass.FLOAT) {
                asm.comment(p + " from its argument register");
                writeFloatFromAbi(X86Abi.FLOAT_ARGS.get(floats++), p);
            } else if (c == RegClass.INT) {
                asm.comment(p + " from its argument register");
                write(X86Abi.INT_ARGS.get(ints++), p);
            } else {
                throw new UnsupportedOperationException("aggregate parameters: step 5");
            }
        }
    }

    private String slot(Var v) {
        return frame.offset(v) + "(%rbp)";
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
    private void read(Var v, String reg) {
        String at = slot(v);
        if (v.type instanceof Type.Int i) {
            extendFrom(at, i.width(), i.signed(), reg);
        } else if (v.type instanceof Type.Ptr) {
            asm.insn("movq", at, "%" + reg);
        } else {
            throw new IllegalStateException(v + " is not an integer");
        }
    }

    // `src` (a memory operand or a register part) extended from `width` bits into a 64-bit register.
    private void extendFrom(String src, int width, boolean signed, String reg) {
        switch (width) {
            case 64 -> asm.insn("movq", src, "%" + reg);
            case 32 -> {
                if (signed) {
                    asm.insn("movslq", src, "%" + reg);
                } else {
                    asm.insn("movl", src, "%" + part(reg, 32));
                }
            }
            default -> asm.insn((signed ? "movs" : "movz") + suffix(width) + "q", src, "%" + reg);
        }
    }

    private void read(Operand o, String reg) {
        if (o instanceof Operand.IntImm imm) {
            immediate(imm.value(), reg);
        } else {
            read((Var) o, reg);
        }
    }

    private void immediate(long value, String reg) {
        if (value == (int) value) {
            asm.insn("movq", "$" + value, "%" + reg);
        } else {
            asm.insn("movabsq", "$" + value, "%" + reg);
        }
    }

    // A register's low bits into a scalar variable's slot, at the type's width.
    private void write(String reg, Var v) {
        int w = width(v.type);
        asm.insn("mov" + suffix(w), "%" + part(reg, w), slot(v));
    }

    // The low N bits of a register extended in place, as a modifier says.
    private void extend(String reg, Type.Int mod) {
        if (mod.width() == 64) {
            return;
        }
        extendFrom("%" + part(reg, mod.width()), mod.width(), mod.signed(), reg);
    }

    private void readFloat(Operand o, String xmm) {
        if (o instanceof Operand.FloatImm imm) {
            asm.insn("movsd", constant(imm.value()) + "(%rip)", "%" + xmm);
            return;
        }
        Var v = (Var) o;
        if (width(v.type) == 32) {
            asm.insn("movss", slot(v), "%" + xmm);
            asm.insn("cvtss2sd", "%" + xmm, "%" + xmm);
        } else {
            asm.insn("movsd", slot(v), "%" + xmm);
        }
    }

    private void writeFloat(String xmm, Var v) {
        if (width(v.type) == 32) {
            asm.insn("cvtsd2ss", "%" + xmm, "%" + xmm);
            asm.insn("movss", "%" + xmm, slot(v));
        } else {
            asm.insn("movsd", "%" + xmm, slot(v));
        }
    }

    // A floating value as the ABI passes it, single or double per the variable's own type.
    private void writeFloatFromAbi(String xmm, Var v) {
        if (width(v.type) == 32) {
            asm.insn("movss", "%" + xmm, slot(v));
        } else {
            asm.insn("movsd", "%" + xmm, slot(v));
        }
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
                asm.insn("cvtsd2ss", "%xmm0", "%xmm0");
                asm.insn("cvtss2sd", "%xmm0", "%xmm0");
            }
            writeFloat("xmm0", i.dst());
        }
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfVar i) {
        throw new UnsupportedOperationException("addrof: step 3");
    }

    @Override
    public Void visit(Instr.AddrOfGlobal i) {
        throw new UnsupportedOperationException("addrof: step 3");
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
            asm.insn(op, "%xmm1", "%xmm0");
            if (((Type.Float) i.mod()).width() == 32) {
                asm.insn("cvtsd2ss", "%xmm0", "%xmm0");
                asm.insn("cvtss2sd", "%xmm0", "%xmm0");
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
            case WADD, ADD -> asm.insn("addq", "%rcx", "%rax");
            case WSUB, SUB -> asm.insn("subq", "%rcx", "%rax");
            case WMUL, MUL -> asm.insn("imulq", "%rcx", "%rax");
            case SDIV, SREM -> {
                asm.insn("cqto");
                asm.insn("idivq", "%rcx");
                if (op == Instr.BinOp.SREM) {
                    asm.insn("movq", "%rdx", "%rax");
                }
            }
            case UDIV, UREM -> {
                extend("rax", unsigned);
                extend("rcx", unsigned);
                asm.insn("xorl", "%edx", "%edx");
                asm.insn("divq", "%rcx");
                if (op == Instr.BinOp.UREM) {
                    asm.insn("movq", "%rdx", "%rax");
                }
            }
            case AND -> asm.insn("andq", "%rcx", "%rax");
            case OR -> asm.insn("orq", "%rcx", "%rax");
            case XOR -> asm.insn("xorq", "%rcx", "%rax");
            case SHL -> asm.insn("shlq", "%cl", "%rax");
            case LSHR -> {
                extend("rax", unsigned);
                asm.insn("shrq", "%cl", "%rax");
            }
            case ASHR -> {
                extend("rax", signed);
                asm.insn("sarq", "%cl", "%rax");
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
            asm.insn("cmpq", "%rcx", "%rax");
            String set = switch (i.op()) {
                case EQ -> "sete";
                case NE -> "setne";
                case SLT -> "setl";
                case SLE -> "setle";
                case ULT -> "setb";
                case ULE -> "setbe";
                default -> throw new IllegalStateException();
            };
            asm.insn(set, "%al");
        }
        asm.insn("movzbq", "%al", "%rax");
        write("rax", i.dst());
        return null;
    }

    // ucomisd sets CF for below and PF for unordered: `a < b` is `b above a`
    // so that a NaN makes it false; equality also needs the parity clear.
    private void floatingCompare(Instr.CmpOp op) {
        switch (op) {
            case FEQ -> {
                asm.insn("ucomisd", "%xmm1", "%xmm0");
                asm.insn("sete", "%al");
                asm.insn("setnp", "%cl");
                asm.insn("andb", "%cl", "%al");
            }
            case FNE -> {
                asm.insn("ucomisd", "%xmm1", "%xmm0");
                asm.insn("setne", "%al");
                asm.insn("setp", "%cl");
                asm.insn("orb", "%cl", "%al");
            }
            case FLT -> {
                asm.insn("ucomisd", "%xmm0", "%xmm1");
                asm.insn("seta", "%al");
            }
            case FLE -> {
                asm.insn("ucomisd", "%xmm0", "%xmm1");
                asm.insn("setae", "%al");
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
                asm.insn("cvtsi2sdq", "%rax", "%xmm0");
                writeFloat("xmm0", i.dst());
            }
            case U2F -> {
                read(i.src(), "rax");
                asm.insn("testq", "%rax", "%rax");
                asm.insn("js", "1f");
                asm.insn("cvtsi2sdq", "%rax", "%xmm0");
                asm.insn("jmp", "2f");
                asm.label("1");
                asm.insn("movq", "%rax", "%rcx");
                asm.insn("shrq", "$1", "%rcx");
                asm.insn("andl", "$1", "%eax");
                asm.insn("orq", "%rax", "%rcx");
                asm.insn("cvtsi2sdq", "%rcx", "%xmm0");
                asm.insn("addsd", "%xmm0", "%xmm0");
                asm.label("2");
                writeFloat("xmm0", i.dst());
            }
            case F2I -> {
                readFloat(i.src(), "xmm0");
                asm.insn("cvttsd2siq", "%xmm0", "%rax");
                extend("rax", (Type.Int) i.dst().type);
                write("rax", i.dst());
            }
            case F2U -> {
                readFloat(i.src(), "xmm0");
                asm.insn("movsd", constant(9223372036854775808.0) + "(%rip)", "%xmm1");
                asm.insn("ucomisd", "%xmm1", "%xmm0");
                asm.insn("jae", "1f");
                asm.insn("cvttsd2siq", "%xmm0", "%rax");
                asm.insn("jmp", "2f");
                asm.label("1");
                asm.insn("subsd", "%xmm1", "%xmm0");
                asm.insn("cvttsd2siq", "%xmm0", "%rax");
                asm.insn("btcq", "$63", "%rax");
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
        throw new UnsupportedOperationException("load: step 3");
    }

    @Override
    public Void visit(Instr.Store i) {
        throw new UnsupportedOperationException("store: step 3");
    }

    // ---- control -------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Br i) {
        asm.insn("jmp", label(i.target()));
        return null;
    }

    @Override
    public Void visit(Instr.CondBr i) {
        read(i.cond(), "rax");
        asm.insn("testq", "%rax", "%rax");
        asm.insn("jne", label(i.then()));
        asm.insn("jmp", label(i.otherwise()));
        return null;
    }

    // A chain of compares; a case value beyond 32 bits goes through %rcx.
    @Override
    public Void visit(Instr.Switch i) {
        read(i.value(), "rax");
        for (Instr.Case c : i.cases()) {
            if (c.value() == (int) c.value()) {
                asm.insn("cmpq", "$" + c.value(), "%rax");
            } else {
                asm.insn("movabsq", "$" + c.value(), "%rcx");
                asm.insn("cmpq", "%rcx", "%rax");
            }
            asm.insn("je", label(c.target()));
        }
        asm.insn("jmp", label(i.dflt()));
        return null;
    }

    @Override
    public Void visit(Instr.Ret i) {
        if (i.value() != null) {
            RegClass c = module.target.classOf(function.sig.ret());
            if (c == RegClass.FLOAT) {
                readFloat(i.value(), "xmm0");
                if (width(function.sig.ret()) == 32) {
                    asm.insn("cvtsd2ss", "%xmm0", "%xmm0");
                }
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
        throw new UnsupportedOperationException("call: step 5");
    }

    @Override
    public Void visit(Instr.ICall i) {
        throw new UnsupportedOperationException("icall: step 5");
    }
}

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
            throw new UnsupportedOperationException("floating immediates: step 2");
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
        throw new UnsupportedOperationException("bin: step 2");
    }

    @Override
    public Void visit(Instr.Cmp i) {
        throw new UnsupportedOperationException("cmp: step 2");
    }

    @Override
    public Void visit(Instr.Cvt i) {
        throw new UnsupportedOperationException("cvt: step 2");
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
        throw new UnsupportedOperationException("br: step 4");
    }

    @Override
    public Void visit(Instr.CondBr i) {
        throw new UnsupportedOperationException("condbr: step 4");
    }

    @Override
    public Void visit(Instr.Switch i) {
        throw new UnsupportedOperationException("switch: step 4");
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
        throw new UnsupportedOperationException("trap: step 4");
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

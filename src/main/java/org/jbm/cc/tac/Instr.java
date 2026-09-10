package org.jbm.cc.tac;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One instruction. Every instruction carries the C token it came from,
 * for diagnostics. Families that differ only by operation ({@link Bin},
 * {@link Cmp}, {@link Cvt}) carry an operation code. An integer
 * instruction that computes narrower than the register carries a
 * modifier, an {@link Type.Int} narrower than the register whose width
 * and signedness say how the result is extended; a floating instruction
 * carries its precision as a {@link Type.Float}. The width of a memory
 * instruction is on the instruction.
 */
public sealed interface Instr {

    Token token();

    <R> R accept(TacVisitor<R> v);

    default boolean isTerminator() {
        return false;
    }

    // ---- variables and addresses ---------------------------------------------------------------

    /**
     * {@code mov.sN %dst, src}, {@code mov.uN}, {@code mov.P}: the low N
     * bits of the source extended as the modifier says, or a floating
     * value at precision P. Every mov states its width; a 64-bit one is
     * {@code mov.s64} or {@code mov.u64}.
     */
    record Mov(@NonNull Var dst, @NonNull Operand src, @NonNull Type mod, @NonNull Token token) implements Instr {

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code %dst = addrof %var}: the address of a variable. */
    record AddrOfVar(@NonNull Var dst, @NonNull Var var, @NonNull Token token) implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code %dst = addrof @name}: the address of a global or function. */
    record AddrOfGlobal(@NonNull Var dst, @NonNull String name, @NonNull Token token) implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- arithmetic, comparison, conversion ----------------------------------------------------

    enum BinOp {
        WADD, WSUB, WMUL, ADD, SUB, MUL, SDIV, UDIV, SREM, UREM, AND, OR, XOR, SHL, LSHR, ASHR,
        FADD, FSUB, FMUL, FDIV;

        public boolean isFloating() {
            return this == FADD || this == FSUB || this == FMUL || this == FDIV;
        }

        public String spelling() {
            return name().toLowerCase();
        }
    }

    /**
     * {@code %dst = op.mod a, b}: an integer operation at the width and
     * signedness of its modifier ({@code .s64}/{@code .u64} for the whole
     * register), or a floating operation at the precision of its modifier.
     */
    record Bin(@NonNull BinOp op, @NonNull Var dst, @NonNull Operand a, @NonNull Operand b, @NonNull Type mod,
               @NonNull Token token) implements Instr {

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    enum CmpOp {
        EQ, NE, SLT, SLE, ULT, ULE, FEQ, FNE, FLT, FLE;

        public boolean isFloating() {
            return this == FEQ || this == FNE || this == FLT || this == FLE;
        }

        public String spelling() {
            return name().toLowerCase();
        }
    }

    /** {@code %dst = op a, b}: {@code dst} is a {@code W} holding 0 or 1. */
    record Cmp(@NonNull CmpOp op, @NonNull Var dst, @NonNull Operand a, @NonNull Operand b, @NonNull Token token)
            implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    enum CvtOp {
        I2F, U2F, F2I, F2U, FCVT;

        public String spelling() {
            return name().toLowerCase();
        }
    }

    /** {@code %dst = op.P src}: between an integer and a floating value, or a rounding to precision P. */
    record Cvt(@NonNull CvtOp op, @NonNull Var dst, @NonNull Var src, @NonNull Type.Float precision, @NonNull Token token)
            implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- memory ------------------------------------------------------------------------------

    /** How a load extends what it read, or that it read a floating value. */
    enum Ext {
        SIGNED("s"), UNSIGNED("u"), FLOAT("f");

        public final String prefix;

        Ext(String prefix) {
            this.prefix = prefix;
        }
    }

    /** {@code %dst = load.eW ptr}: {@code width} bits from the address, extended per {@code ext}. */
    record Load(@NonNull Var dst, @NonNull Var ptr, int width, @NonNull Ext ext, boolean isVolatile, @NonNull Token token)
            implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code store.W ptr, value}: the low {@code width} bits of the value to the address; {@code isFloat} for {@code .fW}. */
    record Store(@NonNull Var ptr, @NonNull Operand value, int width, boolean isFloat, boolean isVolatile,
                 @NonNull Token token) implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code copy T dst, src}: the bytes of aggregate type {@code type} from one address to another. */
    record Copy(@NonNull Type type, @NonNull Var dst, @NonNull Var src, @NonNull Token token) implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code zero T ptr}: the bytes of aggregate type {@code type} at the address to zero. */
    record Zero(@NonNull Type type, @NonNull Var ptr, @NonNull Token token) implements Instr {
        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- control -------------------------------------------------------------------------------

    record Br(@NonNull Block target, @NonNull Token token) implements Instr {
        @Override
        public boolean isTerminator() {
            return true;
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code condbr cond, then, else}: a nonzero condition takes {@code then}. */
    record CondBr(@NonNull Operand cond, @NonNull Block then, @NonNull Block otherwise, @NonNull Token token)
            implements Instr {
        @Override
        public boolean isTerminator() {
            return true;
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    record Case(long value, @NonNull Block target) {
    }

    /** {@code switch value, default, [ N -> block, ... ]} with distinct values. */
    record Switch(@NonNull Var value, @NonNull Block dflt, @NonNull List<Case> cases, @NonNull Token token)
            implements Instr {
        public Switch {
            cases = List.copyOf(cases);
        }

        @Override
        public boolean isTerminator() {
            return true;
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code ret} or {@code ret value}; an aggregate is returned as the {@code ptr} to its bytes. */
    record Ret(@Nullable Operand value, @NonNull Token token) implements Instr {
        @Override
        public boolean isTerminator() {
            return true;
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    record Trap(@NonNull String message, @NonNull Token token) implements Instr {
        @Override
        public boolean isTerminator() {
            return true;
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- calls ---------------------------------------------------------------------------------

    /**
     * {@code %dst = call sig @callee(args) [into %into]}: {@code dst} is
     * absent for a void or aggregate result, {@code into} present exactly
     * for an aggregate result.
     */
    record Call(@Nullable Var dst, @NonNull Type.Func sig, @NonNull String callee, @NonNull List<Operand> args,
                @Nullable Var into, @NonNull Token token) implements Instr {
        public Call {
            args = List.copyOf(args);
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code %dst = icall sig %callee(args) [into %into]}: through a pointer to function. */
    record ICall(@Nullable Var dst, @NonNull Type.Func sig, @NonNull Var callee, @NonNull List<Operand> args,
                 @Nullable Var into, @NonNull Token token) implements Instr {
        public ICall {
            args = List.copyOf(args);
        }

        @Override
        public <R> R accept(TacVisitor<R> v) {
            return v.visit(this);
        }
    }
}

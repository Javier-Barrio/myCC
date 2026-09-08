package org.jbm.cc.tast;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.types.CType;

/**
 * A typed expression (C2y 6.5). The three value categories of 6.3.3.1 are
 * the sealed sub-interfaces, and every node kind implements exactly one,
 * so a node's category is known statically wherever it is used:
 * {@code Assign} takes an {@link Lvalue} target, {@code Add} takes
 * {@link Rvalue} operands, and a constructor that cannot be called is a
 * constraint that cannot be violated. Every implicit conversion is a
 * {@link Conversion} node; nothing downstream re-derives one.
 * <p>
 * Nodes keep the {@link Token} they came from for diagnostics but no
 * reference to any AST node, so the AST can be dropped after typing.
 */
public sealed interface TExpr permits TExpr.Lvalue, TExpr.Rvalue, TExpr.FunctionDesignator {

    CType type();

    Token token();

    <R> R accept(TVisitor<R> visitor);

    // ---- value categories ----------------------------------------------------------

    /** Designates an object (6.3.3.1p1). */
    sealed interface Lvalue extends TExpr permits VarRef {
    }

    /** Designates a function (6.3.3.1p4). */
    sealed interface FunctionDesignator extends TExpr permits FuncRef {
    }

    /** A value. */
    sealed interface Rvalue extends TExpr permits Constant, Conversion, Arithmetic {
    }

    // ---- families -------------------------------------------------------------------------

    sealed interface Constant extends Rvalue permits IntConst, FloatConst {
    }

    /** One of the conversions of 6.3, implicit or written as a cast. */
    sealed interface Conversion extends Rvalue
            permits LvalueToRvalue, ArrayDecay, FunctionDecay, IntToInt, IntToFloat, FloatToInt, FloatToFloat {
        TExpr operand();
    }

    /** A binary arithmetic operator whose operands both have the node's own type. */
    sealed interface Arithmetic extends Rvalue permits Add, Sub, Mul, Div {
        Rvalue left();

        Rvalue right();
    }

    // ---- lvalues and function designators -------------------------------------------------

    /** A reference to an object; type is the symbol's type. */
    record VarRef(@NonNull Symbol symbol, @NonNull CType type, @NonNull Token token) implements Lvalue {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** A reference to a function; type is the function type. */
    record FuncRef(@NonNull Symbol symbol, @NonNull CType type, @NonNull Token token) implements FunctionDesignator {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- constants ----------------------------------------------------------------------------

    /** An integer constant of an integer type; value is its two's-complement bits in a long. */
    record IntConst(long value, @NonNull CType type, @NonNull Token token) implements Constant {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * A floating constant. The value is held as a double: exact for
     * {@code float} and {@code double}, and the nearest double for a
     * {@code long double} spelling, whose extra precision is not modeled.
     */
    record FloatConst(double value, @NonNull CType type, @NonNull Token token) implements Constant {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- conversions --------------------------------------------------------------------------

    /** Lvalue conversion (6.3.3.1p2): the value stored in the object, qualifiers dropped. */
    record LvalueToRvalue(@NonNull Lvalue operand, @NonNull CType type, @NonNull Token token) implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Array to pointer to its first element (6.3.3.1p3). */
    record ArrayDecay(@NonNull Lvalue operand, @NonNull CType type, @NonNull Token token) implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Function designator to pointer to function (6.3.3.1p4). */
    record FunctionDecay(@NonNull FunctionDesignator operand, @NonNull CType type, @NonNull Token token)
            implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Integer to integer of another type (6.3.2.3). */
    record IntToInt(@NonNull Rvalue operand, @NonNull CType type, @NonNull Token token) implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Integer to floating (6.3.2.4). */
    record IntToFloat(@NonNull Rvalue operand, @NonNull CType type, @NonNull Token token) implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Floating to integer, truncating toward zero (6.3.2.4p1). */
    record FloatToInt(@NonNull Rvalue operand, @NonNull CType type, @NonNull Token token) implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Floating to floating of another rank (6.3.2.5). */
    record FloatToFloat(@NonNull Rvalue operand, @NonNull CType type, @NonNull Token token) implements Conversion {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    // ---- arithmetic -------------------------------------------------------------------------------

    record Add(@NonNull Rvalue left, @NonNull Rvalue right, @NonNull CType type, @NonNull Token token)
            implements Arithmetic {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    record Sub(@NonNull Rvalue left, @NonNull Rvalue right, @NonNull CType type, @NonNull Token token)
            implements Arithmetic {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    record Mul(@NonNull Rvalue left, @NonNull Rvalue right, @NonNull CType type, @NonNull Token token)
            implements Arithmetic {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }

    record Div(@NonNull Rvalue left, @NonNull Rvalue right, @NonNull CType type, @NonNull Token token)
            implements Arithmetic {
        @Override
        public <R> R accept(TVisitor<R> v) {
            return v.visit(this);
        }
    }
}

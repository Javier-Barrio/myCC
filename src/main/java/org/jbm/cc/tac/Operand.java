package org.jbm.cc.tac;

/** What an instruction reads: a variable, or an immediate in the instruction's class. */
public sealed interface Operand permits Var, Operand.IntImm, Operand.FloatImm {

    record IntImm(long value) implements Operand {
    }

    record FloatImm(double value) implements Operand {
    }
}

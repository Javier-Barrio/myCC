package org.jbm.mycc.cc.backend.lower.tac;

/** One visit per instruction kind: a consumer's dispatch. */
public interface TacVisitor<R> {
    R visit(Instr.Mov i);
    R visit(Instr.AddrOfVar i);
    R visit(Instr.AddrOfGlobal i);
    R visit(Instr.Bin i);
    R visit(Instr.Cmp i);
    R visit(Instr.Cvt i);
    R visit(Instr.Load i);
    R visit(Instr.Store i);
    R visit(Instr.Br i);
    R visit(Instr.CondBr i);
    R visit(Instr.Switch i);
    R visit(Instr.Ret i);
    R visit(Instr.Trap i);
    R visit(Instr.Call i);
    R visit(Instr.ICall i);
    R visit(Instr.VaStart i);
    R visit(Instr.VaArg i);
}

package org.jbm.mycc.cc.backend.codegen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.jbm.mycc.cc.backend.codegen.Operand.got;
import static org.jbm.mycc.cc.backend.codegen.Operand.imm;
import static org.jbm.mycc.cc.backend.codegen.Operand.mem;
import static org.jbm.mycc.cc.backend.codegen.Operand.plt;
import static org.jbm.mycc.cc.backend.codegen.Operand.reg;
import static org.jbm.mycc.cc.backend.codegen.Operand.rip;
import static org.jbm.mycc.cc.backend.codegen.Operand.sym;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The assembly IR and its AT&T spelling, one case per operand and item kind. */
class AsmIrTest {

    @Test
    void operands() {
        assertEquals("%rax", AttPrinter.operand(reg("rax")));
        assertEquals("$-5", AttPrinter.operand(imm(-5)));
        assertEquals("-4(%rbp)", AttPrinter.operand(mem(-4, "rbp")));
        assertEquals("(%rcx)", AttPrinter.operand(mem(0, "rcx")));
        assertEquals("8(%rax, %rcx, 4)", AttPrinter.operand(new Operand.Mem(8, "rax", "rcx", 4)));
        assertEquals("f", AttPrinter.operand(sym("f")));
        assertEquals("a+4", AttPrinter.operand(sym("a", 4)));
        assertEquals("a-4", AttPrinter.operand(sym("a", -4)));
        assertEquals("printf@PLT", AttPrinter.operand(plt("printf")));
        assertEquals(".LC0(%rip)", AttPrinter.operand(rip(".LC0")));
        assertEquals("stdout@GOTPCREL(%rip)", AttPrinter.operand(got("stdout")));
        assertEquals("  call *%r10", AttPrinter.line(new Item.Insn("icall", List.of(reg("r10")))), "the IR's icall");
        assertEquals("  jmp *8(%rbp)", AttPrinter.line(new Item.Insn("ijmp", List.of(mem(8, "rbp")))));
        assertEquals("  call f", AttPrinter.line(new Item.Insn("call", List.of(sym("f")))));
        assertEquals("  movq %r10, %rax", AttPrinter.line(new Item.Insn("movq", List.of(reg("r10"), reg("rax")))));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Item.Insn("call", List.of(reg("r10"))), "a direct call takes a symbol");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Item.Insn("icall", List.of(sym("f"))), "an indirect call takes a value");
    }

    @Test
    void items() {
        Asm asm = new Asm(true);
        asm.section(".text");
        asm.global("f");
        asm.note("define @f");
        asm.label("f");
        asm.insn("pushq", reg("rbp"));
        asm.insn("movq", reg("rsp"), reg("rbp"));
        asm.comment("%x at -4(%rbp)");
        asm.insn("ret");
        asm.section(".data");
        asm.align(4);
        asm.bytes(new byte[] {1, 2, (byte) 255});
        asm.address(sym("a", 4));
        asm.word(4612811918334230528L, "2.5");
        asm.zero(16);
        assertEquals("""
                  .text
                  .globl f
                # define @f
                f:
                  pushq %rbp
                  movq %rsp, %rbp
                  # %x at -4(%rbp)
                  ret
                  .data
                  .balign 4
                  .byte 1, 2, 255
                  .quad a+4
                  .quad 4612811918334230528   # 2.5
                  .zero 16
                """, asm.text());
        assertEquals(14, asm.items().size());
        assertTrue(asm.items().get(4) instanceof Item.Insn i && i.mnemonic().equals("pushq") && i.operands().equals(List.of(reg("rbp"))));
    }

    @Test
    void commentsOnlyWhenAnnotating() {
        Asm asm = new Asm(false);
        asm.note("n");
        asm.comment("c");
        asm.insn("ret");
        assertEquals("  ret\n", asm.text());
        assertEquals(1, asm.items().size());
    }

    @Test
    void theModuleIrIsWhatThePrinterSpells() {
        var compiled = org.jbm.mycc.cc.Compiler.compile("int main(void) { return 42; }", org.jbm.mycc.cc.cpp.BundledHeaders.INSTANCE, "t.c",
                new org.jbm.mycc.cc.sema.types.Types(org.jbm.mycc.cc.backend.lower.arch.X86_64SysV.INSTANCE));
        List<Item> items = Codegen.build(compiled.tac(), false);
        assertEquals(AttPrinter.print(items), Codegen.emit(compiled.tac(), false));
        long insns = items.stream().filter(i -> i instanceof Item.Insn).count();
        assertEquals(9, insns, "pushq, movq, subq, movq, movslq, movl, movslq, leave, ret");
    }
}

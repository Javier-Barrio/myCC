package org.jbm.mycc.cc.codegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exact assembly of small functions, annotated so each expectation reads as TAC then its instructions. */
class CodegenTest {

    /** The assembly of the last function, from its label to its last line. */
    static String function(String source) {
        String all = Native.assembly(source);
        int start = all.lastIndexOf("\n# define ");
        int end = all.indexOf("\n  .bss", start);
        if (end < 0) {
            end = all.indexOf("\n  .section .note", start);
        }
        return all.substring(start + 1, end + 1);
    }

    @Test
    void aConstantReturn() {
        assertEquals("""
                # define @main() -> i32
                  .globl main
                main:
                  pushq %rbp
                  movq %rsp, %rbp
                  subq $16, %rsp
                  # %t0 at -4(%rbp)
                .L_main_entry:
                  # mov.s32 %t0, 42
                  movq $42, %rax
                  movslq %eax, %rax
                  movl %eax, -4(%rbp)
                  # ret %t0
                  movslq -4(%rbp), %rax
                  leave
                  ret
                """, function("int main(void) { return 42; }"));
    }

    @Test
    void parametersAreSpilledAtTheirWidths() {
        assertEquals("""
                # define @f(i8 %c, u16 %s, i64 %l, ptr %p, f64 %d, f32 %g) -> i8
                  .globl f
                f:
                  pushq %rbp
                  movq %rsp, %rbp
                  subq $48, %rsp
                  # %c at -1(%rbp), %s at -4(%rbp), %l at -16(%rbp), %p at -24(%rbp), %d at -32(%rbp), %g at -36(%rbp)
                  # %c from its argument register
                  movb %dil, -1(%rbp)
                  # %s from its argument register
                  movw %si, -4(%rbp)
                  # %l from its argument register
                  movq %rdx, -16(%rbp)
                  # %p from its argument register
                  movq %rcx, -24(%rbp)
                  # %d from its argument register
                  movsd %xmm0, -32(%rbp)
                  # %g from its argument register
                  movss %xmm1, -36(%rbp)
                .L_f_entry:
                  # ret %c
                  movsbq -1(%rbp), %rax
                  leave
                  ret
                """, function("char f(char c, unsigned short s, long l, int *p, double d, float g) { return c; }"));
    }

    @Test
    void plainModeHasNoComments() {
        String plain = Codegen.emit(org.jbm.mycc.cc.Compiler.compile("int main(void) { return 1; }",
                org.jbm.mycc.cc.cpp.BundledHeaders.INSTANCE, "t.c",
                new org.jbm.mycc.cc.sema.types.Types(org.jbm.mycc.cc.lower.arch.X86_64SysV.INSTANCE)).tac(), false);
        assertFalse(plain.contains("#"), plain);
        assertTrue(plain.contains("main:\n  pushq %rbp\n"), plain);
    }
}

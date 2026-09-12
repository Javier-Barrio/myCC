package org.jbm.mycc.cc.backend.codegen;

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
    void integerArithmeticExtendsPerTheModifier() {
        String asm = function("int f(int a, int b) { return a * b; }");
        assertTrue(asm.contains("""
                  # %t0 = mul.s32 %a, %b
                  movslq -4(%rbp), %rax
                  movslq -8(%rbp), %rcx
                  imulq %rcx, %rax
                  movslq %eax, %rax
                  movl %eax, -12(%rbp)
                """), asm);
        String div = function("unsigned f(unsigned a, unsigned b) { return a / b; }");
        assertTrue(div.contains("""
                  # %t0 = udiv.u32 %a, %b
                  movl -4(%rbp), %eax
                  movl -8(%rbp), %ecx
                  movl %eax, %eax
                  movl %ecx, %ecx
                  xorl %edx, %edx
                  divq %rcx
                  movl %eax, %eax
                  movl %eax, -12(%rbp)
                """), div);
        String shift = function("char f(char c) { return c >> 1; }");
        assertTrue(shift.contains("sarq %cl, %rax"), shift);
    }

    @Test
    void comparisonsSetAByte() {
        String asm = function("int f(int a, int b) { return a < b; }");
        assertTrue(asm.contains("""
                  # %t0 = slt %a, %b
                  movslq -4(%rbp), %rax
                  movslq -8(%rbp), %rcx
                  cmpq %rcx, %rax
                  setl %al
                  movzbq %al, %rax
                  movl %eax, -12(%rbp)
                """), asm);
        String fl = function("int f(double a, double b) { return a < b; }");
        assertTrue(fl.contains("ucomisd %xmm0, %xmm1\n  seta %al"), fl);
    }

    @Test
    void conversionsAndFloatingConstants() {
        String asm = function("double f(int i) { return i * 2.5; }");
        assertTrue(asm.contains("cvtsi2sdq %rax, %xmm0"), asm);
        assertTrue(asm.contains("movsd .LC0(%rip), %xmm0"), asm);
        assertTrue(asm.contains(".LC0:\n  .quad 4612811918334230528   # 2.5"), asm);
        String back = function("unsigned f(double d) { return d; }");
        assertTrue(back.contains("btcq $63, %rax"), back);
    }

    @Test
    void memoryInstructions() {
        String asm = function("int f(int x) { int *p = &x; *p = 5; return *p; }");
        assertTrue(asm.contains("# %t0 = addrof %x\n  leaq -4(%rbp), %rax\n  movq %rax, -24(%rbp)"), asm);
        assertTrue(asm.contains("movq $5, %rax\n  movslq %eax, %rax\n  movl %eax, -28(%rbp)\n  # store.32 %p, %t1\n  movslq -28(%rbp), %rax\n  movq -16(%rbp), %rcx\n  movl %eax, (%rcx)"), asm);
        assertTrue(asm.contains("# %t2 = load.s32 %p\n  movq -16(%rbp), %rcx\n  movslq (%rcx), %rax"), asm);
        String global = function("int g; int f(void) { return g; }");
        assertTrue(global.contains("leaq g(%rip), %rax"), global);
        String agg = function("struct P { int a; int b; }; struct P f(struct P *p) { struct P q = *p; struct P z = { 0 }; return q; }");
        assertTrue(agg.contains("movq $8, %rcx\n  movq -8(%rbp), %rsi\n  rep movsb"), agg);
        assertTrue(agg.contains("movq $8, %rcx\n  xorl %eax, %eax\n  rep stosb"), agg);
        String fl = function("float f(float *p) { return *p; }");
        assertTrue(fl.contains("movss (%rcx), %xmm0\n  cvtss2sd %xmm0, %xmm0\n  cvtsd2ss %xmm0, %xmm0\n  movss %xmm0, -12(%rbp)"), fl);
    }

    @Test
    void callsFollowSysV() {
        String asm = function("int g(int a, int b, int c, int d, int e, int f, int h, double x, int i); int f(void) { return g(1, 2, 3, 4, 5, 6, 7, 2.5, 9); }");
        assertTrue(asm.contains("subq $16, %rsp\n  movslq -28(%rbp), %rax\n  movq %rax, (%rsp)\n  movslq -44(%rbp), %rax\n  movq %rax, 8(%rsp)"), "the stack arguments in order from (%rsp): " + asm);
        assertTrue(asm.contains("movslq -24(%rbp), %r9"), asm);
        assertTrue(asm.contains("call g@PLT\n  addq $16, %rsp"), asm);
        String var = function("int printf(const char *, ...); int f(void) { return printf(\"%d %f\", 1, 2.5); }");
        assertTrue(var.contains("leaq .str."), var);
        assertTrue(var.contains("movl $1, %eax\n  call printf@PLT"), var);
        String local = function("static int h(void) { return 1; } int f(void) { return h(); }");
        assertTrue(local.contains("call h\n"), local);
        String ptr = function("int (*fp)(int); int f(void) { return fp(3); }");
        assertTrue(ptr.contains("movq -16(%rbp), %r10\n  call *%r10"), ptr);
    }

    @Test
    void aggregatesCrossCallsByAddress() {
        String small = function("struct P { int a; int b; }; struct P mk(int a) { struct P p = { a, a }; return p; }");
        assertTrue(small.contains("# ret %t3\n  movq -32(%rbp), %r11\n  movq (%r11), %rax\n  leave"), "an 8-byte struct comes back in %rax: " + small);
        assertTrue(small.contains("%t1 at -32(%rbp), %t2 at -32(%rbp), %t3 at -32(%rbp)"), "temporaries share a slot once their last read is past: " + small);
        String asm = function("struct Q { long a, b, c; }; struct Q mk(long a) { struct Q q = { a, a, a }; return q; }");
        assertTrue(asm.contains("# the result's address from its argument register\n  movq %rdi, -56(%rbp)"), "a 24-byte struct is written where the caller asked: " + asm);
        assertTrue(asm.contains("rep movsb\n  movq -56(%rbp), %rax\n  leave"), asm);
        String mixed16 = function("struct M { int a; double b; }; struct M mk(int a) { struct M m = { a, a }; return m; }");
        assertTrue(mixed16.contains("movq (%r11), %rax\n  movsd 8(%r11), %xmm0\n  leave"), "an int and a double: %rax and %xmm0: " + mixed16);
        String table = Native.assembly("int f(void) { return 1; } int (*const table[1])(void) = { f }; const int k = 3; const char *const s = \"x\";");
        assertTrue(table.contains(".section .data.rel.ro\n  .globl table\n  .balign 8"), "read-only data with addresses is relocated: " + table);
        assertTrue(table.contains(".section .rodata\n  .globl k\n  .balign 4"), table);
        assertTrue(table.indexOf(".globl s") > table.indexOf(".data.rel.ro"), table);
        String param = function("struct P { int a; int b; }; int sum(struct P p) { return p.a + p.b; }");
        assertTrue(param.contains("# %p from its argument registers\n  movq %rdi, -8(%rbp)"), param);
        String big = function("struct Q { long a, b, c; }; long sum(struct Q q, int b) { return q.a + b; }");
        assertTrue(big.indexOf("movl %edi, -28(%rbp)") < big.indexOf("# %q copied from the stack\n  leaq 16(%rbp), %rsi\n  leaq -24(%rbp), %rdi\n  movq $24, %rcx\n  rep movsb"), "a 24-byte struct lies on the caller's stack; scalars are spilled before the copy clobbers their registers\n" + big);
        String odd = function("struct C5 { char v[5]; }; int f(struct C5 c) { return c.v[4]; }");
        assertTrue(odd.contains("movl %edi, -5(%rbp)\n  shrq $32, %rdi\n  movb %dil, -1(%rbp)"), "a 5-byte struct: its low four bytes, then its fifth: " + odd);
        String call = function("struct M { int a; double b; }; struct Q { long a, b, c; }; double g(struct M m, struct Q q); double f(void) { struct M m = { 1, 2.5 }; struct Q q = { 1, 2, 3 }; return g(m, q); }");
        assertTrue(call.contains("subq $32, %rsp") && call.contains("rep movsb") && call.contains("movl (%r11), %edi\n  movsd 8(%r11), %xmm0\n  call g@PLT"), "the small struct in %rdi and %xmm0, the large one copied to the stack: " + call);
    }

    @Test
    void globalsAreByteImages() {
        String all = Native.assembly("int a[4] = { 1, 2, 3, 4 }; int *p = a + 1; const char *s = \"hi\"; double d = 2.5; int z; struct B { unsigned lo : 4; int hi : 4; } b = { 15, -3 }; int main(void) { return 0; }");
        assertTrue(all.contains(".data\n  .globl a\n  .balign 4\na:\n  .byte 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0\n"), all);
        assertTrue(all.contains("p:\n  .quad a+4\n"), all);
        assertTrue(all.contains("s:\n  .quad .str."), all);
        assertTrue(all.contains(".section .rodata\n  .balign 1\n.str."), all);
        assertTrue(all.contains(".byte 104, 105, 0\n"), all);
        assertTrue(all.contains("d:\n  .byte 0, 0, 0, 0, 0, 0, 4, 64\n"), all);
        assertTrue(all.contains(".bss\n  .globl z\n  .balign 4\nz:\n  .zero 4\n"), all);
        assertTrue(all.contains("b:\n  .byte 223, 0, 0, 0\n"), all);
    }

    @Test
    void plainModeHasNoComments() {
        String plain = Codegen.emit(org.jbm.mycc.cc.Compiler.compile("int main(void) { return 1; }",
                org.jbm.mycc.cc.cpp.BundledHeaders.INSTANCE, "t.c",
                new org.jbm.mycc.cc.sema.types.Types(org.jbm.mycc.cc.backend.arch.X86_64SysV.INSTANCE)).tac(), false);
        assertFalse(plain.contains("#"), plain);
        assertTrue(plain.contains("main:\n  pushq %rbp\n"), plain);
    }
}

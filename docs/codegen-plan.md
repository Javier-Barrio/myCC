# Code generation: TAC to x86-64 assembly

`org.jbm.mycc.cc.codegen`: a `Module` compiled for `x86_64-sysv` to
GNU assembler text in AT&T syntax, assembled and linked by `gcc`. The
smallest correct scheme, the same one the VM uses: every variable has a
slot in the frame, every instruction loads its operands from their
slots into scratch registers, computes, and stores the result back. No
register allocation, no optimization; those come later, if ever, as a
pass over the TAC, not here.

```
define @sq(i32 %x) -> i32 {            sq:
  i32 %t0                                  pushq %rbp
.entry:                                    movq %rsp, %rbp
  %t0 = mul.s32 %x, %x                     subq $16, %rsp
  ret %t0                                  movl %edi, -4(%rbp)        # %x from its argument register
}                                        .L_sq_entry:
                                           movslq -4(%rbp), %rax
                                           movslq -4(%rbp), %rcx
                                           imull %ecx, %eax
                                           movslq %eax, %rax
                                           movl %eax, -8(%rbp)        # %t0
                                           movslq -8(%rbp), %rax
                                           leave
                                           ret
```

AT&T syntax: source before destination, registers with `%`, immediates
with `$`, memory as `offset(base, index, scale)`, and the operand width
as the mnemonic's suffix (`b w l q`), so `movl %eax, -8(%rbp)` writes 4
bytes and `movslq` extends a signed 4-byte value to 8.

## Rules

- **Slots.** A `Frame` gives each parameter and local an `[rbp - N]`
  slot of its type's size and alignment, aggregates included; the frame
  is rounded to 16. Reading a scalar variable extends it per its type
  (`movsbq`, `movzwq`, `movslq`, `movl` for a `u32`, which zero-extends),
  so a value in a register is always held extended, as the TAC promises;
  writing stores the type's width. `addrof %x` is `leaq -N(%rbp), %rax`.
- **Registers.** `%rax` and `%rcx` for integers and pointers, `%xmm0`
  and `%xmm1` for floating values, `%rdx` for division; nothing lives in
  a register across instructions. `f80` is computed as `f64`, as the VM
  does; the slot keeps its 16 bytes.
- **Instructions.** `mov.sN/uN` extends from N bits; `bin` computes at
  64 bits and extends per its modifier (`addq`, `subq`, `imulq`, `idivq`
  and `divq` after `cqto` or `xorl %edx, %edx`, `andq`, `orq`, `xorq`,
  `shlq`, `shrq`, `sarq` with the count in `%cl`; `addsd` and friends at
  `.64`, `addss` at `.32`); `cmp` sets `%al` with `setcc` and
  zero-extends; `cvt` is `cvtsi2sdq`, `cvttsd2siq` and their `ss` forms,
  unsigned through the usual shift trick; `load` and `store` at the
  instruction's width, an aggregate store as `rep movsb` or `rep stosb`.
- **Control.** A block is a label `.L_<function>_<block>`; `br` is
  `jmp`; `condbr` is `testq %rax, %rax` then `jne`/`jmp`; `switch` is a
  chain of `cmpq`/`je` then `jmp` to the default; `ret` puts the value
  in `%rax` or `%xmm0`, an aggregate's address in `%rax`, then `leave;
  ret`; `trap` is `ud2`.
- **Calls, SysV.** Integer and pointer arguments in `%rdi %rsi %rdx
  %rcx %r8 %r9`, floating in `%xmm0-7`, the rest pushed right to left;
  `%al` holds the count of vector registers for a variadic callee; the
  stack is 16-aligned at the call. The return value in `%rax` or `%xmm0`
  goes to `dst`. An aggregate argument is passed as the pointer the TAC
  provides and copied by the callee at entry; an aggregate result is
  returned through `into`, passed as a hidden first argument. That is
  SysV's convention only for aggregates larger than 16 bytes; small
  ones are passed in registers by real C code, so a call to a library
  function with a struct by value is rejected at compile time.
  `icall` is `call *%rax`. A parameter's value is moved from its
  argument register or its stack position into its slot in the prologue.
- **Data.** A `Global` is a byte image built from its items exactly as
  the VM's loader builds it, emitted as `.byte` runs with each `AddrItem`
  as a `.quad name+addend` at its offset, in `.data`, `.rodata` for
  read-only, `.bss` with `.zero` when there is no initializer.
  `internal` linkage omits `.globl`. Symbols are emitted as they are;
  `.str.<hash>` and `$N` are valid to the GNU assembler, `.file` is not,
  and a script is not compiled to assembly anyway.

## Components

```
Codegen      emit(Module) -> String: the sections, each Global through Data, each Function through Emitter
Frame        slot offsets for one Function's parameters and locals; frame size
Emitter      implements TacVisitor<Void>: one method per instruction, writing lines; load(Operand, reg),
             store(reg, Var), address(Var), the width suffixes; prologue with parameter spills, epilogue
Abi          the SysV register order, argument classification, the call sequence, the return
Data         a Global's byte image and its directives
Asm          the text: labels, instructions, comments, indentation
```

`Main -S file.c` prints the assembly. Then:

```
gcc -o prog file.s        # links the C library, so printf and malloc are the real ones
```

## Tests

- `CodegenTest`: the exact assembly of small functions, one per
  instruction family and for the prologue, a call, a global with each
  item kind, as `LowerTest` does for the TAC.
- `NativeTest`: every self-asserting program of `VmGrammarTest`,
  `VmDataflowTest`, `VmMemoryTest` and `LibcTest`, compiled to
  assembly, assembled with `gcc`, run, and expected to exit 0 or print
  what the VM printed. Skipped when `gcc` is not on the path.
  `Main.SOURCE` exits with 49 natively.

## Steps

1. [ ] `Asm`, `Frame`, `Emitter` with `mov` and `ret`, prologue and
   epilogue; a function returning a constant runs natively.
2. [ ] `bin`, `cmp`, `cvt`.
3. [ ] `addrof`, `load`, `store`, aggregates.
4. [ ] `br`, `condbr`, `switch`, `trap`.
5. [ ] `Abi`: calls with integer, floating, stack and variadic arguments,
   `icall`, aggregate arguments and results, parameter spills.
6. [ ] `Data`; `NativeTest` over the program corpus.
7. [ ] `Main -S`.

Deferred: register allocation; `f80` on the x87; small aggregates by
value across the library boundary; `setjmp`; other targets, which get
their own `Emitter` and `Abi` behind the same `Codegen`.

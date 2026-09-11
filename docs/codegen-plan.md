# Code generation: TAC to x86-64 assembly

A `Module` compiled for `x86_64-sysv` to
GNU assembler text in AT&T syntax, assembled and linked by `gcc`. The
smallest correct scheme, the same one the VM uses: every variable has a
slot in the frame, every instruction loads its operands from their
slots into scratch registers, computes, and stores the result back. No
register allocation, no optimization; those come later, if ever, as a
pass over the TAC, not here.

```
define @sq(i32 %x) -> i32 {        # define @sq(i32 %x) -> i32
  i32 %t0                          sq:
.entry:                              pushq %rbp
  %t0 = mul.s32 %x, %x               movq %rsp, %rbp
  ret %t0                            subq $16, %rsp                 # %x at -4(%rbp), %t0 at -8(%rbp)
}                                    movl %edi, -4(%rbp)            # %x from its argument register
                                   .L_sq_entry:
                                     # %t0 = mul.s32 %x, %x
                                     movslq -4(%rbp), %rax
                                     movslq -4(%rbp), %rcx
                                     imull %ecx, %eax
                                     movslq %eax, %rax
                                     movl %eax, -8(%rbp)
                                     # ret %t0
                                     movslq -8(%rbp), %rax
                                     leave
                                     ret
```

AT&T syntax: source before destination, registers with `%`, immediates
with `$`, memory as `offset(base, index, scale)`, and the operand width
as the mnemonic's suffix (`b w l q`), so `movl %eax, -8(%rbp)` writes 4
bytes and `movslq` extends a signed 4-byte value to 8.

**Annotation.** In annotated mode, shown above, every TAC instruction
is printed as a comment, as `TacWriter` spells it, before the assembly
it became, with the function's signature at its label and the slot
table after the prologue. Since an instruction's assembly depends on
nothing but itself, the comment delimits exactly its lines. The plain
mode prints the assembly alone. `Main -S` is plain, `Main -S -a`
annotated, and the shell's `/asm name` is annotated.

## Rules

- **Slots.** A `Frame` gives each parameter and local a `-N(%rbp)`
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

Target-independent, in `org.jbm.mycc.cc.codegen`:

```
Codegen      build(Module, annotate) -> List<Item>, the assembly IR; emit(...) -> String, the IR printed.
             Picks the Backend for the module's target; the sections, each Global through Data, each
             Function through the backend
Backend      what a target provides: the assembly of one Function
Frame        slot offsets for one Function's parameters and locals, from the module's sizes; frame size
Data         a Global's byte image as IR items: bytes, addresses, zeros
Item         the assembly IR, one record per line: Insn(mnemonic, operands), Label, Section, Global,
             Align, Bytes, Address, Word, Zero, Comment, Note
Operand      Reg, Imm, Mem(disp, base, index, scale), Sym(name, addend, reloc), RipRel(name, reloc),
             Indirect(reg); reloc is PLAIN, PLT or GOT
Asm          builds the IR in order; comments only when annotating
AttPrinter   the IR in AT&T syntax for the GNU assembler; an assembler would encode the same IR
```

CPU-specific, in `org.jbm.mycc.cc.lower.arch.x86_64`, beside `X86_64SysV`:

```
X86Emitter   implements Backend and TacVisitor<Void>: one method per instruction, writing lines;
             load(Operand, reg), store(reg, Var), address(Var), the width suffixes; prologue with
             parameter spills, epilogue
X86Abi       the SysV register order, argument classification, the call sequence, the return
```

`Main -S file.c` prints the assembly, `Main -S -a file.c` with the TAC
in comments. Then:

```
gcc -o prog file.s        # links the C library, so printf and malloc are the real ones
```

## Tests

- `CodegenTest`: the exact assembly of small functions, one per
  instruction family and for the prologue, a call, a global with each
  item kind, as `LowerTest` does for the TAC; annotated, so each
  expectation reads as TAC followed by its assembly, and one plain case.
- `NativeTest`: every self-asserting program of `VmGrammarTest`,
  `VmDataflowTest`, `VmMemoryTest` and `LibcTest`, compiled to
  assembly, assembled with `gcc`, run, and expected to exit 0 or print
  what the VM printed. Skipped when `gcc` is not on the path.
  `Main.SOURCE` exits with 49 natively.

## Steps

1. [x] `Asm`, `Frame`, `Emitter` with `mov` and `ret`, prologue and
   epilogue; a function returning a constant runs natively.
2. [x] `bin`, `cmp`, `cvt`.
3. [x] `addrof`, `load`, `store`, aggregates.
4. [x] `br`, `condbr`, `switch`, `trap`.
5. [x] `Abi`: calls with integer, floating, stack and variadic arguments,
   `icall`, aggregate arguments and results, parameter spills.
6. [x] `Data`; `NativeTest` over the program corpus.
7. [x] `Main -S` and `-a`; `/asm name` in the shell.

Deferred: register allocation; `f80` on the x87; small aggregates by
value across the library boundary; `setjmp`; other targets, which get
their own emitter and ABI in their own `arch` package behind the same `Codegen`.

# The assembler: assembly IR to an ELF object for x86-64 Linux

The assembly IR (`codegen-plan.md`) to a relocatable object that `gcc`
links with glibc, so a program is built without GNU `as`. The emitter
uses a closed set of mnemonics and operand forms; the assembler encodes
exactly those and rejects anything else by name. No relaxation: every
jump and call takes a 32-bit displacement, so one pass lays the bytes
out and a second resolves the labels.

```
Item list ──Assembler──> ObjectFile { sections, symbols, relocations } ──Elf64──> file.o
              └── X86Encoder: one Insn to bytes plus the fixups inside them
```

This is the third of three ways a program runs, all from the one TAC:

```
TAC ──VM──────────────────────────────> the shell, a line at a time, builtins for the library
TAC ──Emitter──> IR ──AttPrinter──> .s ──gcc──> prog       text for the GNU assembler; glibc linked
TAC ──Emitter──> IR ──Assembler───> .o ──gcc──> prog       this plan; the same IR, no text
```

The emitter and the IR are shared by the last two, so the assembler
never sees text and the printer stays the way to read what it encodes.

## Encoding

`[prefix] [REX] opcode [ModR/M] [SIB] [disp] [imm]`, built from the
operand kinds: registers are numbers 0-15 whose fourth bit goes to REX;
`disp(base, index, scale)` is ModR/M and, when needed, SIB; `name(%rip)`
is mod 00 r/m 101 with a 32-bit displacement left for a fixup;
immediates are 8, 32 or 64 bits as the form allows; SSE is its prefix
(`F2`, `F3`, `66`) then `0F` and the opcode. One table, keyed by
mnemonic and operand kinds, holds the forms the emitter produces: the
`mov` family with the extensions, `lea`, `push`, arithmetic, shifts by
`%cl` and by 8, `cmp`, `test`, `setcc`, `cqto`, `idiv`, `div`, `rep
movsb`, `rep stosb`, `leave`, `ret`, `ud2`, the SSE moves, conversions,
arithmetic and `ucomisd`, `jmp` and the conditional jumps, `call`,
`icall`, `ijmp`.

## Symbols and relocations

Sections `.text`, `.data`, `.rodata`, `.bss` in the order the items
switch to them, labels noted at their offsets. A symbol operand is a
fixup, resolved at the end:

| Reference | Resolution |
|---|---|
| a label of the same section: blocks, `1f`, `.LC0` | the displacement, no relocation |
| `leaq g(%rip)` to a symbol of this object | `R_X86_64_PC32` |
| `movq g@GOTPCREL(%rip)` | `R_X86_64_REX_GOTPCRELX` |
| `call f`, `call f@PLT` | `R_X86_64_PLT32` |
| `.quad a+4` | `R_X86_64_64` |

Labels are local symbols; a `Global` item makes one global; an
undefined name is a global undefined symbol. Functions are `FUNC`,
objects `OBJECT` with their size.

## The object

`Elf64` writes the header, the sections above, `.symtab`, `.strtab`,
`.shstrtab`, a `.rela` for each section with relocations, and
`.note.GNU-stack`. `gcc -o prog file.o` does the linking; a linker is
not part of this.

## Components

```
org.jbm.mycc.cc.backend.codegen
  Assembler     assemble(List<Item>) -> ObjectFile, through an Encoder
  ObjectFile    Section(name, bytes, align), Symbol(name, section, offset, size, global, kind),
                Relocation(section, offset, type, symbol, addend)
  Encoder       what a target provides: encode(Insn) -> bytes and fixups
  Elf64         ObjectFile -> byte[]
org.jbm.mycc.cc.backend.arch.x86_64
  X86Encoder    the table; REX, ModR/M, SIB, displacements, immediates, SSE prefixes
```

`Main -c file.c` writes `file.o`.

## Tests

- `X86EncoderTest`: the bytes of each table row as hex, taken once from
  GNU `as`, plus the REX corner cases (`sil`, `r8`, `rsp`, `r13`).
- `AssemblerTest`: labels forward, back and numeric; a small module's
  symbols and relocations.
- `ObjectTest`: the corpus assembled by us, linked with `gcc`, run to
  exit 0, and each `.text` compared byte for byte with `as` on our own
  printed assembly, so a wrong encoding fails with the instruction
  named. Skipped without `gcc`.

## Steps

1. [ ] The encoding core and `movq`, `pushq`, `leave`, `ret`; the test from `as`.
2. [ ] The rest of the integer table.
3. [ ] SSE and the wide immediates.
4. [ ] `Assembler` with labels; `ObjectFile`.
5. [ ] Relocations, `Elf64`, `gcc` links it, the corpus runs and matches `as`; `Main -c`.

Deferred: short jumps and relaxation; a linker; debug info.

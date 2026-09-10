# cshell: architecture and plan

An interactive C shell in the spirit of `jshell`: type a declaration, a
statement or an expression at a prompt and see it defined, run or evaluated
against everything typed before. The shell is a loop around two things
that exist independently of it: the **compiler**, which turns C source into
a `Module` of TAC, and a **VM**, which executes TAC. Both live in this
repository: the compiler under `org.jbm.cc`, the VM under `org.jbm.vm`
and the shell under `org.jbm.cshell`, so the names say which is a
consumer of which. The
shell adds no pass and no state to either; it concatenates what has been
typed, compiles all of it, loads the result into the VM and calls one
function. This document says what the compiler has to be for that loop to
work, what the TAC is, what the VM is asked to do, and how the shell itself
is built.

```
cshell> int sq(int x) { return x * x; }
|  defined function sq
cshell> int v[3] = { 1, 4, 9 };
v ==> {1, 4, 9}
cshell> v[1] + sq(v[2])
$1 ==> 85
cshell> struct P { int x, y; } p = { .y = 2 };
p ==> {.x = 0, .y = 2}
cshell> p.y = v[2]
$2 ==> 9
```

## Decision: lower to TAC, then either compile or execute

The compiler lowers the typed tree to a TAC `Module`. From there a
`Module` goes one of two ways: `Codegen` compiles it to assembly for a real
target, or the VM executes it. Alternatives considered for where the shell
should branch off: (a) interpret the typed tree, which needs no backend but
bypasses the compiler and duplicates C's semantics; (b) generate machine
code and execute a model of the machine, which means maintaining a second
machine. We branch at the TAC because:

1. [ ] **The TAC is where the compiler's semantic work is finished.** After
   `Lower` every conversion is an instruction with a width, every member
   access is an address plus a constant, every control structure is a
   branch, every struct copy is a `copy` of a known size. What remains is
   target-specific. A VM that executes the TAC runs the compiler's output
   with only the machine-specific tail missing.
2. [ ] **One representation, two consumers.** `Codegen` and the VM read the
   same `Module`, so lowering is tested by running programs long before
   `Codegen` exists, and the two can be compared on the same programs once
   it does.
3. [ ] **The VM sees only the TAC.** `org.jbm.vm` depends on `org.jbm.cc.tac`
   and `org.jbm.cc.types` and on nothing in the front end, so it could be
   lifted out as its own artifact at any time; `org.jbm.cshell` is the
   only place the compiler and the VM meet.

The cost is that the TAC has to be designed as a public, serializable
instruction set with a written contract, rather than as whatever `Lower`
finds convenient. That contract is the largest part of this plan.

## Decision: the shell recompiles the whole program on every line

Alternatives: (a) make each pass incremental, with entries seeded from the
previous line's state and copy-and-commit of the scope tables; (b) keep
the source of everything typed so far and compile all of it, unchanged
pipeline, every time. We chose (b):

1. [ ] **No pass changes.** The scanner, parser, resolver, typer and `Lower`
   are used exactly as `Main` uses them. There is no session state, no
   rollback: a line that fails is simply not appended.
2. [ ] **Names resolve consistently.** Every line is typed against the same
   file scope the compiler would build for that text as a file, including
   typedef names and tags, which is what makes classifying a line as a
   declaration or a statement correct (`T * x;` is a declaration exactly
   when `T` is a typedef in the accumulated text).
3. [ ] **Redefinition comes for free.** Redefining `f` is removing the line
   that defined it before concatenating. Everything that referred to `f`
   is re-resolved and re-lowered against the new one, which is what
   `jshell` re-implements by hand as dependency tracking.
4. [ ] **The cost is compile time proportional to the session**, which for a
   shell is milliseconds, and a map from lines of the concatenated text
   back to the snippet they came from, for error messages.

Execution is still strictly incremental: the VM runs exactly one function
per line, the synthetic one for what was just typed. Nothing is ever
re-run; see "Loading with merge semantics".

## Decision: the TAC is layout-explicit and ABI-neutral

- **Layout is fixed by the compiler.** Sizes, alignments, member offsets,
  bit-field placements and integer widths come from the `Target` the typer
  used and appear in the TAC as constants. The VM does not compute layouts;
  it is told them. A `Module` records the `Target` it was compiled for, and
  a VM refuses a module for a target it does not implement. This is what
  makes `sizeof`, unions, `offsetof` idioms and `char *` views of a struct
  mean the same in the VM as in compiled code.
- **The calling convention is not fixed by the compiler.** A TAC `call`
  carries typed arguments and a typed result, including aggregates by
  value. Whether an argument goes in `rdi` or on the stack is the code
  generator's or the VM's business. The x86-64 backend applies SysV; the VM
  applies its own frames. The ABI lives in exactly one place per consumer.

## Decision: names are bound late, by the loader

Every reference in the TAC to a file-scope object or function is **by
name**. The consumer binds names when it loads a module or later: the VM
resolves a name when the instruction runs, as a dynamic linker's GOT would;
`Codegen` emits the symbol and lets the assembler and linker resolve it.
Late binding is what makes loading a recompiled program cheap: a function
whose definition changed is replaced under its name, and every earlier
call site sees it. An undefined name faults at the reference, with the
name, which is how the shell reports a declared-but-undefined function
without refusing the line.

## Decision: one build, packages with one-way dependencies

Everything is in this repository and one Gradle module. The layering is
by package, and it is what would become the module boundaries if the VM
were ever split out:

```
org.jbm.cc.types, org.jbm.cc.tac     the compiler's output: model, reader, writer, invariants, Target
org.jbm.cc.cpp ... org.jbm.cc.lower  the compiler; Compiler.compile is its one entry
org.jbm.vm                           the VM: loader, interpreter, builtins; depends on cc.tac and cc.types only
org.jbm.cshell                       the loop; the only package that depends on both the compiler and the VM
```

`sema.Symbol` is referenced from the typed tree but not from the TAC,
which names things by string, so `tac` does not depend on `sema`, and the
VM never sees the front end. The shell uses the typed tree only to learn
the C type of a result for printing.

## Pipeline

The compiler, one entry used by `Main`, the tests and the shell:

```
Compiler.compile(source, headers) :
  Scanner ──> Parser ──> Desugar ──> Resolver ──> Typer ──> Lower ──> Compiled(TUnit typed, Module tac)
                                                                          ├──Codegen──> AsmPrinter ──> .s
                                                                          └──TacWriter──> .tac   (the VM's input)
```

The shell, per line:

```
text = declarations so far + the new line, as typed
compiled = Compiler.compileScript(text, headers)   on error: report, keep the old text
engine.load(compiled.tac())                         the merge rule
engine.call(".file")                                the synthetic file function, if the module has one
print($N, type from compiled.typed())               when the line was an expression
```

## The TAC

The TAC is specified in `tac-plan.md`; it is the contract between `Lower`,
the VM and any code generator. The properties the shell and the VM rely on:
typed virtual registers and explicit memory, not SSA and no `phi`;
layout fixed by the compiler as constants; calls that carry their full
signature and by-value aggregates with their member types, so the calling
convention is the consumer's; initializers as typed values, so the byte
order is the consumer's; every file-scope name a string bound by the
consumer; a `static` local named `function.variable` so the same source
gives the same name every time; string literals named by content so a
loader can share them; a text form that round-trips, which is what `/tac`
prints and what the lowering tests compare.

## What the shell asks of the VM: `Engine`

One interface, in `org.jbm.vm`, that the VM implements and the shell
consumes; the merge semantics are what make the shell's loop work:

```
interface Engine {
    void  load(Module module);                     // merge semantics below
    Value call(String function, List<Value> args); // faults as ExecutionException
    byte[] read(long address, int size);           // for ValuePrinter
    long  addressOf(String global);
    Optional<String> nameOf(long address);         // of a function, for printing a function pointer
    void  drop(String name);                       // unbind; storage and code stay for what still refers to them
}
```

**Loading with merge semantics.** `load` receives the whole program every
time and must behave like a dynamic loader given a rebuilt library. The
loader never asks whether a definition changed; it asks, per name, whether
what is bound under it can stay:

- a **function** is always bound under its name, replacing whatever was
  there. Code has no state, callers reach it by name at call time, and the
  name keeps its address slot, so rebinding an unchanged function is
  harmless and a changed one takes effect at its next call. No comparison
  of TAC is made (the instructions carry source tokens, so two compiles
  of the same text are not even `equals`);
- a **data item** that is new is allocated and initialized once; one already
  bound under that name with the **same TAC type keeps its storage and its
  contents**, whatever earlier calls stored there, and its initializer is
  ignored; one whose type changed is reallocated and initialized afresh.
  The type check is `equals` on the `Type` records;
- a **string literal** already present by name is shared;
- an **external** name is bound to a builtin if the VM has one, and left
  unbound otherwise, to fault at first use.

Nothing is executed by `load`. The shell calls exactly one function per
line, so a global's value survives from line to line and a redefined
function takes effect at its next call.

`Value` is a scalar (`long` bits or `double`) with its TAC type, or an
aggregate as an address the VM owns. The VM provides the libc as builtins
bound to the usual names (`printf`, `malloc`, `strlen`, ...). The compiler
provides the **headers** that declare them (`stdio.h`, `stdlib.h`,
`string.h`, `math.h`, `stdbool.h`, `stddef.h`, `stdint.h`, `limits.h`)
as resources of `cc` served through its `HeaderProvider`, so `#include
<stdio.h>` means the same in the shell and in the compiler, and the VM's
builtins are written against those declarations.

## The shell

**State.** A list of the lines kept so far, each tagged as a directive or
a declaration with the range it occupies in the concatenation, and a
counter for `$N`. That is all; there is no session object.

**Statements at file scope: the file function.** In script mode the
parser accepts a statement wherever an external declaration may appear.
It tells the two apart the way it already does for block items, so
`T * x;` is a declaration exactly when `T` is a typedef in scope. The
file-scope statements, in textual order, become the body of one synthetic
function appended at the end of the unit:

```
void .file(void) { <the file-scope statements> }
```

Its name cannot be spelled in C, so it cannot collide, and it follows the
`.str.` and `.lit.` scheme of the other compiler-made names. From the
parser on it is an ordinary function: resolved, typed and lowered like any
other, a `Function` in `Module.functions` under the name `.file`. Nothing
in the `Module` marks it; the shell calls it by name when it is present.
A unit without file-scope statements has no `.file`.

Being last in the unit, the body sees every file-scope name. In the shell
that is exact, since the kept text is declarations only and the new line
comes after them; in a script file it means a statement may use a name
declared below it, which is accepted.

**Classifying a line.** The line is appended to the accumulated text and
compiled in script mode. If the parser fails at end of input, the line is
incomplete and the REPL asks for more. Otherwise the typed tree says what
it was: if the unit has no `.file`, the line was a directive or a
declaration and it is kept. If `.file`'s body is one expression statement
of non-`void` type, the line was an expression, and it is compiled once
more as

```
typeof_unqual((<line>)) $N;
$N = (<line>);
```

which gives `$N` the expression's type without the shell having to spell
it (anonymous struct types have no spelling), and without qualifiers so
that it is assignable. `typeof` at file scope is unevaluated, so any
expression over file-scope names is allowed. The declaration of `$N` is
kept in the accumulated text; the statement is not, since it ran once.
Statements and `void` expressions keep nothing.

**Running.** `engine.load` with the module, then `engine.call(".file")`
when the module has it. The shell reads `$N` back through `addressOf` and
`read` and prints it by its `CType` from the typed tree. A declaration of
an object prints its value the same way; a function definition prints
`|  defined function f`.

**Redefinition.** A declaration whose file-scope names are already
declared by an earlier kept line replaces that line before compiling.
If the result compiles, the old definition is gone and every dependent was
re-resolved; the loader replaces what changed. If it does not compile, the
old line is restored and the errors are shown. This is `jshell`'s
behavior, including that a redeclared object is re-initialized. `/drop f`
removes the line that declares `f` and tells the engine to unbind it; if
that leaves other lines uncompilable the shell says which and refuses.

**Errors.** Compile errors carry a token with a line in the concatenated
text; the line map turns that into the snippet and the line within it,
and the message is printed with a caret under the token. A run-time fault
from `call` is printed with the VM's backtrace mapped to C lines through
the instruction tokens. In both cases the accumulated text is as it was
before the line.

## Printing values

Driven by the `CType`, reading bytes through `Engine.read`:

- integers as decimal; `char` as `'a' (97)`; `bool` as `true`/`false`;
  enumerators by name when the value has one;
- floating values with `%g`-like shortest round-trip output;
- pointers as hex; a `char *` into readable memory additionally shows the
  string, as `jshell` shows a `String`; a function pointer by name through
  `Engine.nameOf`;
- arrays as `{1, 2, 3}` and structs as `{.x = 1, .y = 2}` recursively,
  unions as their first member with a note, nested past a depth limit as
  `...`.

## The REPL

No dependency at first: lines from `System.in`, results to `System.out`,
the program's `stdout` through the same stream. Continuation prompt when
the parser fails at `EOF` or a cheap pre-scan finds unbalanced braces,
brackets, parentheses or quotes. Commands:

```
/help                     /list [name]          the kept lines, with their numbers
/vars  /funcs  /types     /macros               what the file scope holds
/tac <name>               the TAC of a function or global, as TacWriter prints it
/asm <name>               its x86-64 assembly, once Codegen exists
/drop <name>              /reset                forget a name, or everything
/load <file>              /save <file>          run a file line by line, or write the kept text
/exit
```

`/tac` and `/asm` are what make the shell a compiler workbench. Line
editing, history and completion come later through JLine behind a
`LineReader` interface so the transcript tests never need a terminal.

## Packages and classes

```
org.jbm.cc.tac                Module, Symbol (Function, Global and the declarations), Block, Type; Instr (sealed, one record
                              per instruction, each with its token); TacVisitor; TacWriter; TacReader;
                              TacInvariants (temps typed and assigned before use, branch targets in the
                              function, names defined or external, widths consistent)
org.jbm.cc.lower              Lower (the pass), ExprLower, StmtLower (JumpTarget to Block, switch tables),
                              the global items straight from TInit
org.jbm.cc.Compiler           compile(source, headers) -> Compiled(typed, tac); the one entry; Main uses it
org.jbm.cc.cpp.HeaderProvider headers by name; the bundled ones as resources
org.jbm.vm                    Engine, Value, ExecutionException (the contract);
                              Memory (segments: unmapped null page, data, stack, heap; typed load/store; faults);
                              Loader (symbol table, placement, relocations, merge on reload, late binding);
                              Interpreter (a TacVisitor over Instr with a frame per call);
                              Builtins, Libc (printf family, malloc family, string and memory functions,
                              exit and abort, math.h through java.lang.Math)
org.jbm.cshell                Repl (the loop, the kept lines, the line map, classification, commands),
                              ValuePrinter, LineReader, CShell (main)
```

## Changes to existing code

- **`cpp.Scanner`**: `#include <name>` through `HeaderProvider` (resources
  for the bundled headers, the file system for the compiler). Not
  implemented today. Nothing else in the front end changes.
- **`parse`**: a script mode, `Parser.parseScript`, that accepts
  statements at file scope and collects them into the `.file` function
  definition; `Compiler.compileScript` is the entry that uses it. The
  ordinary `parse` still rejects them, so a file compiled as C is still C.
- **`cpp`**: `$` accepted in identifiers, as GCC does, for `$N`.
- **`sema`**: no change. The names `Lower` emits are `Symbol` names, which
  the resolver already makes unique for names with linkage; `static` names
  and locals get the `function.variable` scheme in `Lower`.
- **`tast`**: no change. `TFunction.locals` is already the frame,
  `JumpTarget` the block identity, `DirectCall`/`IndirectCall` already
  `call`/`icall`, `Materialize` already a slot, `TInit` already the data.
- **Build**: a `cshell` Gradle task; no module split.

## Testing

- **Lowering corpus**: `src/test/resources/tac/*.c` with a `.tac` golden
  beside each, like the typed corpus; `TacInvariants` over every module;
  a round trip through `TacWriter` and `TacReader`.
- **VM unit tests**: `Memory` (segments, widths, faults), `Interpreter` on
  hand-built modules (each instruction kind, conversions on both targets,
  `switch` ranges, by-value aggregates), `Loader` (relocations, merge on
  reload: a global of unchanged type keeps its contents, one of a changed
  type is reallocated, a function is rebound),
  `printf` formats, `malloc` reuse.
- **Run corpus**: `src/test/resources/run/*.c`, programs with `main` and a
  `.out` file with the expected stdout and exit status, produced by `gcc`
  on the host and checked in, compiled through `Compiler.compile` and run
  on the VM. `Main.SOURCE` is the first entry (exit status 49). Programs
  are chosen to cover every `tast` node kind and every `Instr`.
- **Shell transcripts**: `src/test/resources/shell/*.cshell`, a script of
  input lines and the exact expected output, through `Repl` with the VM
  and a fake `LineReader`: each line kind, continuation, every command,
  redefinition (compatible and not), a failing line leaving the kept text
  intact, a run-time fault mid-expression, a global's value surviving a
  recompile.
- **Classification tests**: `Parser.parseScript` on declaration versus
  statement versus expression for the ambiguous spellings, with and without
  the typedef in scope; the `.file` body in order; no `.file` without
  statements; `parse` still rejecting a file-scope statement.

## Steps

Each step is one commit with the suite green and `Main` still running.
A is the TAC and lowering, needed by the compiler regardless of the shell.
B is the compiler entry and headers. C is the VM and needs only A. D is
the shell and needs all three; E and F follow D.

### A - the TAC and lowering
1. [ ] **The TAC model** in `org.jbm.cc.tac`: the records, `TacVisitor`,
   `TacWriter`, `TacInvariants`; unit tests on hand-built modules.
2. [ ] **Expressions.** `ExprLower` for scalars: constants, arithmetic,
   comparisons, conversions, `Cond` and `Comma` as blocks, `Logical` as
   short-circuit blocks; the lowering corpus starts.
3. [ ] **Objects and addresses.** Slots and globals, `VarRef`, `Deref`,
   `AddrOf`, `Member` with offsets, bit-fields, `ArrayDecay`, `PtrAdd`,
   `PtrDiff`, assignments including `CompoundAssign` and `PostfixAssign`
   with `TargetValue`, `Materialize` and compound literals as slots.
4. [ ] **Calls and functions.** `DirectCall`, `IndirectCall`, by-value
   aggregates through copies and result addresses, variadic extras,
   `Return`; `Function` with its frame and signature.
5. [ ] **Statements.** Blocks, `If`, loops, `Break`, `Continue`, `Goto` and
   labels as blocks, `Switch` with ranges; `LocalDecl` through `TInit`.
6. [ ] **Data.** Global items straight from `TInit`, strings by
   hash, statics by the naming scheme, tentatives; `Module`;
   `Lower.lower(TUnit)`; `Main` prints the TAC.
7. [ ] **`TacReader`** and the round trip.

### B - the compiler entry and headers
1. [ ] **`Compiler.compile`** as the one entry; `Main` and the pipeline tests
   use it.
2. [ ] **`#include`** in `cpp` with `HeaderProvider`; the bundled headers as
   resources.

### C - the VM
1. [ ] **`Memory` and `Value`**: segments, aligned allocation, typed load
   and store, faults; unit tests on both targets.
2. [ ] **`Interpreter`** over `Instr`: temporaries and slots in a frame,
   arithmetic, comparisons, conversions, addresses, `load`/`store`/`copy`/
   `zero`, branches and `switch`, `call` and `icall` with by-value
   aggregates, `ret`; hand-built module tests.
3. [ ] **`Loader`**: data items with relocations, strings by name, function
   addresses, late binding of names, merge semantics on reload; `Engine`.
4. [ ] **Builtins**: `Libc` with the string and memory functions, `exit` and
   `abort`, the `malloc` family on the heap; then the `printf` family
   (`%d %i %u %x %o %c %s %p %f %g %e %ld %lu %lld %zu %%`, width,
   precision, flags); then `math.h`.
5. [ ] **Run corpus**; milestone: `Main.SOURCE` exits with 49.

### D - the loop
1. [ ] **`Repl` core**: kept lines, line map, compile, `load`, `call`;
   declarations only; the first transcripts with the VM.
2. [ ] **Statements and expressions**: `Parser.parseScript` and the `.file`
   function, `$` in identifiers, the `typeof_unqual` declaration of `$N`,
   `ValuePrinter`, continuation on `EOF`.
3. [ ] **Redefinition and `/drop`**: replacing a kept line, restoring it on
   failure, the refusal when a drop breaks dependents.
4. [ ] **Transcripts** for everything above.

### E - the terminal
1. [ ] **`LineReader`**, prompts, the pre-scan, error display with the caret,
   `CShell` main and a `cshell` Gradle task.
2. [ ] **Commands**: `/help /list /vars /funcs /types /macros /tac /reset
   /exit`; `/asm` when `Codegen` exists.
3. [ ] **`/load` and `/save`**; a file given on the command line is loaded
   first.

### F - closing
1. [ ] **Non-constant initializers at file scope** (`int v[3] = { sq(1), ...
   }`, accepted as `jshell` accepts them): the shell rewrites the line as
   the declaration plus an initializer function using a compound literal
   and `copy`, run once after loading.
2. [ ] **Faults with a C backtrace** from the instruction tokens.
3. [ ] **`exit` inside a line** ends the line, not the shell.
4. [ ] **JLine**: history, editing, completion of names and commands.

### Deferred
- `setjmp`/`longjmp`, signals, `volatile` beyond ordinary access
- variable-length arrays (blocked on the typer)
- calling real native libraries from the VM (FFI); until then every external function is a VM builtin
- SSA in the TAC; a code generator that wants it builds it
- incremental front-end entries, if a session ever grows large enough for recompilation to be felt

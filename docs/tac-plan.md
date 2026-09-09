# TAC, target-specific variant: architecture and plan

One of two alternative designs for the three-address code. This one
carries the **target's widths, sizes, offsets and alignments** as
constants; `tac-agnostic-plan.md` is the other, in which the TAC is
flattened typed C and every consumer computes layout from its own
`Target`. The two share the register-style, non-SSA shape, the block
structure, the calling model and the lowering steps; they differ in what
a type is and who computes layout. Choose one.

The three-address code is the compiler's output and the boundary between
the front end (`Lower` produces it from the typed tree) and everything
that consumes a program: the VM of `cshell-plan.md`, the x86-64 code
generator, and any other code generator later. It is **target-specified**:
a module is compiled for one named target and carries that target's
integer widths, sizes, member offsets and alignments as constants, so no
consumer computes a layout. It is **simple to interpret** and **sufficient
to generate good code from**, and it stays neutral on the two things a
target fixes only at the very end: the calling convention and the byte
order. This document is the contract; `org.jbm.cc.tac` implements it.

```
target x86_64-sysv                       ; ptr 64, little, long double f80
type %P = { i32, i32 }                   ; size 8, align 4
global @counter : i32 align 4 = i32 0
global @greeting : [6 x i8] align 1 readonly = bytes "hello\00"
declare @printf(ptr, ...) -> i32

define @sq(i32 %x) -> i32 {
  slot $x : i32 align 4
  temp %t0 : i32, %t1 : i32
.entry:
  store i32 %x, $x                       ; the parameter is an object; the typer gave it an address
  %t0 = load i32 $x
  %t1 = mul i32 %t0, %t0
  ret i32 %t1
}
```

## Decision: a typed, register-style three-address code, not a stack machine or a tree

Alternatives considered: (a) a stack bytecode, as the JVM uses: compact,
trivial to interpret, but a code generator has to reconstruct expressions
and a register allocator has nothing to work with; (b) a tree IR like
GCC's GENERIC or the typed tree itself: easy to lower from, but neither an
interpreter nor a code generator wants trees; (c) three-address code over
typed virtual registers and explicit memory, the shape of LLVM IR, GIMPLE
and Cranelift IR. We chose (c):

1. [ ] **An interpreter is a loop over instructions** with one array of
   registers per frame; every instruction reads at most two operands and
   writes at most one, so the dispatch is uniform and there is no operand
   stack to keep consistent.
2. [ ] **A code generator gets what it needs and no more**: a control-flow
   graph of basic blocks, virtual registers with types and computable
   liveness, explicit loads and stores, explicit conversions with widths,
   and calls that carry their full signatures. Instruction selection is a
   walk; register allocation is a pass over a known form.
3. [ ] **Every value has exactly one type and every operation one meaning.**
   There are no implicit conversions and no context-dependent operations.
   Signed and unsigned division are different instructions, not one
   instruction plus a type flag a consumer might forget to read.

## Decision: not SSA; there is no `phi`

Every temporary has one type, and `Lower` may assign a temporary more
than once. Control flow is blocks and branches, and a value that differs
by path is a temporary assigned on each path before the join, or a slot.
Alternatives: (a) SSA everywhere, which puts dominance and phi placement
into `Lower` and phi evaluation into the VM; (b) SSA as an optional form
with `phi` defined in the IR, which still makes every consumer handle it.
We chose neither:

1. [ ] **`Lower` is a direct walk of the typed tree**: a C variable is a slot,
   an expression is a chain of temporaries, a loop is three blocks. No
   dominance, no renaming.
2. [ ] **The VM evaluates a condition, jumps, and interprets the block it
   lands in.** There is nothing to do at a join; a temporary holds what
   the path that arrived assigned to it. `phi` is a code generator's
   device for register allocation and optimization, and it stays inside
   the code generator.
3. [ ] **A code generator that wants SSA builds it in its own
   representation**, as GCC builds GIMPLE-SSA from GIMPLE and LLVM's
   backends build machine IR from LLVM IR. The TAC is its input, not its
   working form, so nothing here constrains how it works.

## Decision: types are explicit and structural, and aggregates are first class

The IR has integer types by width, floating types by format, a pointer
type, arrays and structures with the offsets of their members, and
function types. Alternatives: (a) scalars only, with aggregates as
untyped byte ranges; (b) LLVM's full type system. We chose structural
types with member offsets because:

1. [ ] **Calling conventions classify by structure.** SysV puts a `struct {
   double, double }` in two vector registers and a `struct { long, long }`
   in two integer registers; AArch64 has its own rules. A code generator
   cannot be correct for calls to external functions unless a by-value
   aggregate argument or result arrives with its member types. The C ABI
   lives in the code generator, so the code generator must see what C saw.
2. [ ] **Offsets are given, not computed.** The typer laid every structure
   out for the `Target`; the IR records the result (each member's offset)
   so no consumer re-derives it and every consumer agrees with the typer.
   Member addresses are still `wadd` of a constant byte offset; the
   structure types exist for calls, copies and initializers.
3. [ ] **Data initializers can be structured**, a sequence of typed values
   rather than bytes, which is what keeps the IR independent of byte order.

## Decision: layout-explicit, ABI-neutral, endianness-neutral

- **Layout is fixed.** Integer widths, sizes, alignments, member offsets
  and bit-field placements come from the `Target` the typer used and are
  constants in the IR. A `Module` names its target; a consumer refuses a
  module for a target whose layout it does not implement. This is what
  makes `sizeof`, unions and `char *` views of a struct mean the same in
  the VM as in generated code.
- **The calling convention is not fixed.** `call` carries every argument
  with its type and the callee's signature; `ret` carries the value with
  its type; a function's parameters are typed values it receives. Where an
  `i64` goes, how a 24-byte structure is returned, what a variadic call
  must set: each consumer decides for its target, and the IR never
  mentions a register or a stack.
- **Endianness is not fixed.** Values are values; memory operations are
  typed; initializers are typed values, and only a consumer turns them
  into bytes. The one place byte order could leak, a `char` view of a
  wider object, is the program's own doing, and the `Module`'s target
  says which order the program will see.

## Decision: names are strings, bound by the consumer

Globals and functions are referred to by name. There is no symbol object
shared with `sema`; the IR is self-contained and serializable, and a
consumer binds names when it loads a module (the VM) or leaves them to
the linker (a code generator). Within a function, temporaries, slots and
blocks are named too, but their names are local and the text form
reproduces them exactly, so a round trip through `TacWriter` and
`TacReader` is the identity.

## Types

```
type    ::= int | float | ptr | array | struct | func
int     ::= i1 | i8 | i16 | i32 | i64          two's complement bit patterns; signedness is in the operation
float   ::= f32 | f64 | f80 | f128             IEEE binary32/64/128 and the x87 extended format
ptr     ::= ptr                                the integer type of the target's pointer width, under another name
array   ::= [ N x type ]                       N elements, contiguous, no padding beyond the element's
struct  ::= { member, ... }                    each member: type, byte offset; size and alignment given
func    ::= ( type, ... [, ...] ) -> type      parameter types, variadic flag, return type or void
```

`ptr` is not a distinct kind of value: it is the target's pointer-width
integer type, spelled `ptr` where a pointer is meant so the text reads
well, and every integer instruction and comparison applies to it. The
null pointer is `ptr 0`. `i1` is the result of comparisons and the
operand of `condbr`; it is never stored, a C `bool` in memory is `i8`. `char`,
enums, `bool` and `_BitInt` up to 64 bits are integers of their storage
width. `f80` is what x86's `long double` is; a consumer that has no such
format may execute it as `f64` and must say so. Structures are
**named** in the module (`type %P = { ... }`) so a consumer can intern
them and calls can refer to them; a union is a structure whose members all
have offset zero. The type of a member that is a bit-field is the storage
unit's integer type; bit-fields exist only in the operations `Lower`
emits.

Scalar types (`int`, `float`, `ptr`) are the only types a temporary may
have. Aggregates live in memory: in a slot, in a global, or wherever a
pointer points. Where an instruction takes an aggregate **value** (a call
argument, a return, an initializer) the operand is an address and the
instruction states the aggregate type, so the consumer knows both where
the bytes are and what they mean.

## Operands

```
operand ::= %name            a temporary, typed at its declaration
          | const            an integer or floating constant, written with its type: i32 7, f64 1.5, i1 true
          | @name            the address of a global or function
          | $name            the address of a slot of the current function
```

`@name` and `$name` are addresses and have type `ptr`. Allowing them as
operands directly, rather than requiring a separate instruction to take
the address, is what keeps `load i32 $x` one instruction for the VM and
one addressing mode for a code generator.

## Instructions

Every instruction has the form `%t = op type operands` or `op type
operands`. The type is the type of the operation; operands whose type
differs are stated separately. Semantics are those of C on a two's
complement machine, made explicit: nothing is undefined in the IR's own
terms except where marked `UB`, and a consumer may then do anything,
including trap.

The set is deliberately small: anything that is one other instruction
with a constant operand is not an instruction. There is no negate
(`wsub type 0, x`), no bitwise not (`xor type x, -1`), no greater-than
(swap the operands of a less-than), no select (a branch), no pointer
arithmetic apart from integer arithmetic (`ptr` is an integer type), no
`bitcast` (reinterpretation goes through memory, as C's own does), and no
`unreachable` apart from `trap`.

**Integer arithmetic and logic** (`type` is an int, `ptr` included; both
operands and the result have it):

```
wadd wsub wmul                 wrap modulo 2^width: the low bits of the mathematical result
add sub mul                    overflow is UB: the result must fit the width
sdiv udiv srem urem            truncate toward zero; division by zero and INT_MIN / -1 are UB
and or xor
shl lshr ashr                  the amount is an operand of the same type; amounts >= width are UB
```

Both forms of the first two rows exist so that `Lower` can say what C
said: unsigned arithmetic is defined to wrap, so it becomes `wadd`;
signed overflow is undefined, so it becomes `add`, and a consumer may
exploit that (a code generator folding `x + 1 > x` to true, a VM trapping
on overflow to report the bug) or simply wrap. Pointer arithmetic is
`wadd ptr`: `s.m` is `wadd ptr %s, 4`, and `p[i]` is `wmul i64 %i, 8`
then `wadd ptr %p, %o`, with every offset already scaled by `Lower`.

**Floating arithmetic** (`type` is a `float`): `fadd fsub fmul fdiv`, IEEE
round-to-nearest. Negation is `fsub type -0.0, x`, which is exact under
IEEE. There is no `frem`; C's `fmod` is a library call.

**Comparisons** (`type` is the operand type; the result is `i1`):

```
eq ne                          any int, ptr included
slt sle                        signed
ult ule                        unsigned, and ptr
feq flt fle                    ordered: false if either operand is NaN
fne                            unordered or not equal: true if either operand is NaN
```

`a > b` is `slt b, a`; `a >= b` is `sle b, a`; likewise for the others.

**Conversions** (`%t = op from-type operand to to-type`):

```
trunc sext zext                int to narrower / wider int; ptr to and from int is one of these, or nothing
fptosi fptoui sitofp uitofp    between float and int; out-of-range float-to-int is UB
fpext fptrunc                  between float formats
```

There is no conversion to `i1`; a test is `ne type x, 0` (or `fne`),
which `Lower` emits.

**Memory** (each carries the type of what moves and the alignment the
typer guarantees; `volatile` marks accesses that must happen as written):

```
%v = load type [volatile] addr [align N]
store type [volatile] value, addr [align N]
copy aggtype dst, src                        bytes of the aggregate type, non-overlapping
zero aggtype addr                            all bytes of the aggregate type to zero
```

Bit-fields are `load` of the storage unit, `and`/`or`/`shl`/`lshr`, and
`store`, with the masks and shifts computed by `Lower` from the typer's
placement. A struct assignment is `copy`; the zero part of an initializer
is `zero` followed by stores.

**Control** (each block ends with exactly one of these):

```
br .block
condbr i1 cond, .then, .else
switch type value, .default, [ const -> .block, ... ]      distinct values
ret                                              from a void function
ret type value                                   value; for an aggregate type, the address of the bytes
trap "message"                                   stops the program; also where control cannot arrive
```

`switch` lists single values; `Lower` turns a `case low ... high` into a
comparison chain ahead of it.

**Calls**, direct and indirect:

```
%r = call sig @name ( type arg, ... )            a named function: the callee is known statically
%r = icall sig ptr %f ( type arg, ... )          a computed pointer to function: the callee is a run-time value
     call / icall ...                            void result
     call / icall ... into aggtype addr          aggregate result, written to addr
```

The two are distinct instructions, as `DirectCall` and `IndirectCall`
are distinct nodes in the typed tree, so a consumer dispatches on the
instruction and never inspects an operand to learn which it has. For the
VM, `call` is a lookup of the name in the loader's table, bound late, and
`icall` is a lookup of the pointer value in the code segment; for a code
generator, `call` is a call to a symbol and `icall` a call through a
register. `sig` is the callee's function type as the caller sees it: the
prototype for `call`, the pointer's type for `icall`. Each argument is a
scalar operand or, for an aggregate parameter, the aggregate type and the
address of the value; the callee receives its own copy, made by the
consumer. For a variadic call `sig` ends in `...` and the arguments past
the fixed ones are already promoted by the typer. A consumer applies its
target's calling convention to exactly this information and nothing else.

**Variadic functions** are defined with `...` in their signature and use
two instructions on a `va_list` object, whose type `%va_list` the target
descriptor gives as an aggregate:

```
va_start ptr list
%v = va_arg ptr list, type
```

`va_end` is nothing and `va_copy` is `copy %va_list dst, src`.

## Functions

```
define [linkage] @name ( type %param, ... [, ...] ) -> type {
  slot $name : type align N ...        the frame: one per parameter, local, compound literal, temporary object
  temp %name : type ...                every temporary the body uses, with its type
.block:                                the first block is the entry; every block has a name
  instruction ...
}
declare [linkage] @name sig            a function defined elsewhere or by the consumer (the VM's builtins)
```

Parameters are values, received as temporaries. Because C treats a
parameter as an object with an address, `Lower` gives each one a slot and
stores the incoming value to it at entry, exactly as shown in the example.
An aggregate parameter's slot is initialized by the consumer at entry with
the argument's bytes, so its temporary is the slot's address and there is
no store. A consumer that promotes slots to registers may, and the code
generator will.

Slots are named, never addressed by a frame offset: a code generator lays
the frame out and a VM allocates slots as it likes. Their lifetime is the
call. A slot's size and alignment are its type's.

`linkage` is `external` (visible to other modules, the default) or
`internal` (`static` in C). A `static` local of C is a global with
`internal` linkage named `function.variable[.ordinal]`, so the same source
gives the same name every time.

## Globals

```
global [linkage] @name : type align N [readonly] [= initializer]
initializer ::= scalar-const | addr @name [+ N] | bytes "..." | zero type
              | { initializer, ... }               a struct or array, one item per member or element
```

A global without an initializer is zero (a tentative definition, or
`extern` resolved elsewhere: an `extern` declaration that is not defined
in the module is `declare @name : type`). `addr @name + N` is a
relocation. `bytes` is for string literals and is the one initializer that
is already bytes. A consumer turns the initializer into memory in its own
byte order.

String literals are globals named `@.str.<hash>` with `internal` linkage
and `readonly`, so a loader can share one copy across modules compiled
from the same text.

## Module

```
target name                    x86_64-sysv, ilp32-test, ...: pointer width, endianness, f80 or f128 long double,
                               va_list size and alignment; the consumer checks it against what it implements
type %name = { ... }           named structure types, in dependency order
global / declare / define      in any order
```

A `Module` is the output of one compilation, whether of a file or of the
shell's accumulated text. Nothing in it depends on any other module
except through names.

## Well-formedness (`TacInvariants`)

- every temporary is declared once with a scalar type, and every use
  and every assignment agrees with that type;
- every operand of every instruction has the type the instruction
  requires; conversions state both types and they differ as the
  conversion demands;
- every block ends with exactly one terminator (`br`, `condbr`, `switch`,
  `ret`, `trap`) and has none elsewhere; every branch target is a block of
  the same function; the entry block has no predecessors;
- every `@name` is defined or declared in the module; every `$name` is a
  slot of the function; every named type is defined before use;
- a `call`'s name is a defined or declared function and an `icall`'s
  callee is a temporary of type `ptr`; the arguments match `sig` in count
  and type (variadic extras are scalars or aggregates by address); `into`
  is present exactly when the result is an aggregate;
- `switch` values are distinct and typed as the value;
- a `readonly` global has an initializer; `bytes` initializers are only
  for `[N x i8]`.

The invariants run on every module in the test suites and, optionally, on
load in the VM.

## What makes it easy to interpret

- A frame is one array of `long` (integers, pointers, `i1`) and one of
  `double` per function, indexed by temporary number, plus the slot
  addresses computed once at entry from the sizes and alignments.
- `@name` and `$name` operands resolve to addresses once: slots at entry,
  globals at load.
- Every instruction is a record with resolved operand references, so
  dispatch is a `TacVisitor` call per instruction and blocks are arrays
  with a program counter.
- `switch` is a lookup; `call` is a name looked up in the loader's table
  and `icall` a pointer looked up in the code segment, then a new frame
  and a copy of arguments;
  `copy` and `zero` are `System.arraycopy` and `Arrays.fill`.
- A join needs nothing: the VM arrives at a block and runs it, and each
  temporary holds what the path that arrived assigned.
- Nothing requires analysis: no dominance, no liveness, no types to
  infer, no layouts to compute.

## What makes it easy to generate code from

- Basic blocks with explicit terminators are the control-flow graph;
  predecessors are computable in one pass.
- Typed virtual registers with single, known types; liveness is a
  standard dataflow pass over reassignable temporaries, and a code
  generator that prefers SSA constructs it in its own representation from
  this one, which has the blocks and the definitions it needs.
- Explicit `load`/`store` with alignment; `$name` and `@name` operands
  fold into addressing modes; `wadd ptr` with a constant folds into a
  displacement.
- Comparisons produce `i1` that `condbr` consumes, which maps onto flags
  or onto a compare-and-branch equally well.
- Calls carry the full signature and by-value aggregates with their
  member types, so a code generator has everything an ABI classifier
  needs, and nothing has been pre-lowered in a way that assumes one ABI.
- Signed and unsigned operations are distinct instructions; widths are
  explicit; there is no context to consult.

## Lowering from the typed tree

The mapping is direct; the table is the specification of `Lower`:

| Typed tree | TAC |
|---|---|
| `IntConst`, `FloatConst`, `NullptrConst` | constants; null is `ptr 0` |
| `AddrConst(symbol, offset)` | `@name`, or `wadd ptr @name, offset` |
| `VarRef` of a local or parameter | `$name`; of a global, `@name` |
| `LvalueToRvalue` | `load` of the lvalue's address; a bit-field: `load`, `lshr`, `and`, or `shl` then `ashr` for a signed one |
| `ArrayDecay`, `FunctionDecay` | the address itself |
| `Deref` | the pointer value is the address |
| `Member` | `wadd ptr base, offset` |
| `Materialize`, compound literal | a slot |
| `IntToInt`, `IntToFloat`, ... | `trunc`/`sext`/`zext`, `sitofp`/`uitofp`, ... by the typer's widths and signs; pointer conversions are nothing, `trunc` or `zext` |
| `ToBool` | `ne`/`fne` with zero, then `zext` to the C `int` when used as a value |
| `Arithmetic`, `Shift` | `wadd`/`wsub`/`wmul` for unsigned types, `add`/`sub`/`mul` for signed, the `f` forms for floating, `sdiv`/`udiv`/`srem`/`urem`, `shl`/`lshr`/`ashr` |
| `Unary` | negation `wsub 0, x` or `sub 0, x` or `fsub -0.0, x`; bitwise not `xor x, -1`; logical not `eq x, 0` |
| `Comparison` | `eq ne slt sle ult ule feq fne flt fle`, operands swapped for `>` and `>=`; `zext` to `int` |
| `Logical` | blocks with short circuit |
| `Cond` | blocks |
| `PtrAdd`, `PtrDiff` | `wmul` by the element size then `wadd ptr`; `wsub` then `sdiv` by the element size |
| `Assign`, `CompoundAssign`, `PostfixAssign`, `TargetValue` | address evaluated once into a temporary, `load`/`store`; aggregates `copy` |
| `DirectCall`, `IndirectCall` | `call @name` and `icall %p` respectively; aggregate arguments by address; `into` for aggregate results |
| `Comma` | in order |
| `Block`, `ExprStmt`, `LocalDecl` | instructions; `LocalDecl` with `TInit` is `zero` then stores |
| `If`, `While`, `DoWhile`, `For` | blocks; `JumpTarget` is a block |
| `Switch`, `Case`, `CaseRange` | `switch` on single values, each label a block; a range is a comparison chain ahead of the `switch` |
| `Labeled`, `Goto`, `Break`, `Continue` | `br` to the target's block |
| `Return` | `ret`, aggregates by address |
| `TUnit.Global` with `TInit` | `global` with a structured initializer built from the items, `zero` for the rest |
| `StringData` | `@.str.<hash>` with `bytes` |
| `TFunction` | `define` with slots from `parameters` and `locals`, temporaries as allocated |

## Packages and classes

```
org.jbm.cc.tac
  Type                        sealed: Int(width), Float(format), Ptr, Array(element, count), Struct(name, members), Func(params, variadic, ret)
  Operand                     sealed: Temp, IntConst, FloatConst, Global(name), Slot(name)
  Instr                       sealed, one record per instruction above, each carrying the C token it came from
  Block, Function, Global, Module, Target descriptor
  TacVisitor<R>               one visit per instruction kind; the VM's and the code generator's dispatch
  TacWriter, TacReader        the text form, round-trip exact
  TacInvariants               the rules above; run in tests and optionally on load
org.jbm.cc.lower
  Lower                       TUnit to Module
  ExprLower                   expressions to temporaries; lvalues to addresses
  StmtLower                   statements to blocks; JumpTarget to Block; switch tables
  DataLower                   TInit to structured initializers; strings; statics
```

`tac` depends on nothing in `sema` or `tast`; `lower` depends on both and
on `tac`. The VM depends on `tac` only.

## Testing

- **Unit tests on hand-built modules**: every instruction through
  `TacInvariants`, `TacWriter` and `TacReader`, including each rejection
  the invariants make.
- **Lowering corpus**: `src/test/resources/tac/*.c` with a `.tac` golden
  beside each, like the typed corpus, on both targets where the source is
  target-neutral; `TacInvariants` on every module; the round trip.
- **Coverage**: every `Instr` kind and every row of the mapping table
  appears in the corpus.
- **Execution** is the VM's suite (`cshell-plan.md`): the run corpus
  against `gcc`'s output.

## Steps

Each step is one commit with the suite green and `Main` still running.

1. [ ] **The model**: `Type`, `Operand`, `Instr`, `Block`, `Function`,
   `Global`, `Module`, the target descriptor, `TacVisitor`; hand-built
   module tests.
2. [ ] **`TacWriter`** and **`TacInvariants`**; the text form fixed by tests.
3. [ ] **`ExprLower`, scalars**: constants, arithmetic, comparisons,
   conversions, `Logical`, `Cond`, `Comma`; the lowering corpus starts.
4. [ ] **Objects and addresses**: slots and globals, `VarRef`, `Deref`,
   `AddrOf`, `Member`, bit-fields, decay, `PtrAdd`, `PtrDiff`, the
   assignments, `Materialize` and compound literals.
5. [ ] **Calls and functions**: `call` and `icall` in every form, by-value aggregates,
   variadic arguments, `Return`, `define` with its frame; `va_*` for
   variadic definitions.
6. [ ] **Statements**: blocks, `If`, loops, `Break`, `Continue`, `Goto`,
   labels, `Switch`, `LocalDecl` initialization.
7. [ ] **Data**: `DataLower`, string literals, statics, tentatives; `Module`;
   `Lower.lower(TUnit)`; `Main` prints the TAC.
8. [ ] **`TacReader`** and the round trip on the whole corpus.

### Deferred
- `i128` and `_BitInt` above 64 bits
- atomics and memory ordering
- dynamic `alloca` for variable-length arrays
- thread-local globals
- `f128` arithmetic in the VM (carried, not executed)
- inline assembly, which is by definition not architecture-agnostic

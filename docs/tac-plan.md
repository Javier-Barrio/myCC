# TAC: architecture and plan

The three-address code is the compiler's output and the boundary between
the front end (`Lower` produces it from the typed tree) and everything
that consumes a program: the VM of `cshell-plan.md`, the x86-64 code
generator, and any other code generator later. It is **target-specified**:
a module is compiled for one named target and carries that target's
widths, sizes, member offsets and alignments as constants, so no consumer
computes a layout. It is a **load/store register machine** in the manner
of ARM: values live in registers of a few classes, only `load` and
`store` touch memory, every instruction is one a RISC machine has, and
what a wider instruction set would do with a special instruction
(extend, select, negate) is done with shifts, masks and branches. It
stays neutral on the two things a target fixes only at the very end: the
calling convention and the byte order. This document is the contract;
`org.jbm.cc.tac` implements it.

```
target x86_64-sysv                       ; w 32, l 64, ptr l, little, long double x87
type %P = { i32, i32 }                   ; size 8, align 4
global @counter : i32 align 4 = i32 0
global @greeting : [6 x i8] align 1 readonly = bytes "hello\00"
declare @printf(ptr, ...) -> i32

define @sq(i32 %x) -> i32 {
  slot $x : i32 align 4
  wtemp %x, %t0, %t1
  ltemp %a
.entry:
  %a = addr $x                           ; the parameter is an object; the typer gave it an address
  store.32 %x, [%a]
  %t0 = load.s32 [%a]
  %t1 = mul %t0, %t0
  ret i32 %t1
}
```

## Decision: a load/store register machine, not a typed-value IR

Alternatives considered: (a) an IR whose temporaries carry integer widths
from `i8` to `i64` and whose conversions are instructions that name both
types, the shape of LLVM IR; (b) a stack bytecode; (c) a register machine
with a few register classes, memory reached only through loads and
stores, and extension done by shifts and masks. We chose (c):

1. [ ] **The instruction set is the smallest that is complete**, and every
   instruction is one a RISC machine has. There is no family of
   conversions: sign extension is a shift left and a shift right, zero
   extension is a mask, truncation is a store of the narrow width, and a
   move between register classes is one instruction. No instruction's
   meaning depends on a pair of types.
2. [ ] **Register classes match C's widths.** `w` registers are the width
   of `int`, `l` registers the width of `long long`, so `int` arithmetic
   wraps in `w` without a mask and `long` arithmetic has registers of its
   own. Narrower C types (`char`, `short`, `bool`) are kept **canonical**
   in `w`, sign- or zero-extended per their type, so a `w` operation gives
   the result the narrower C operation would.
3. [ ] **An interpreter is a loop over instructions** with one array per
   register class per frame and no per-width dispatch inside an
   instruction: `add` on `w` is an `int` add, on `l` a `long` add;
   `load.s16` is a `short` read.
4. [ ] **A code generator gets a form close to its own.** Register classes,
   widths only on memory access, comparisons that produce 0 or 1, and
   explicit extensions map onto ARM directly and onto x86-64 with a
   peephole for the forms it has.

## Decision: not SSA; there is no `phi`

Every temporary may be assigned more than once. Control flow is blocks
and branches; a value that differs by path is a temporary assigned on
each path before the join, or a slot. `Lower` stays a direct walk of the
typed tree. The VM evaluates a condition, jumps, and interprets the block
it lands in; there is nothing to do at a join. A code generator that
wants SSA builds it in its own IR.

## Decision: memory is typed and structural, registers have classes

Memory has types: slots, globals, loads and stores, struct definitions,
signatures, copies. Those types carry the target's widths, sizes, member
offsets and alignments as constants. Registers have a class, because
that is what a machine register has. Structure types are first class in
memory and in signatures because a calling convention classifies a
by-value aggregate by its member types, and the code generator, where
the C ABI lives, must see what C saw. Initializers are typed values
rather than bytes, which keeps the IR independent of byte order.

## Decision: layout-explicit, ABI-neutral, endianness-neutral

- **Layout is fixed.** Widths, sizes, alignments, member offsets and
  bit-field placements come from the `Target` the typer used and are
  constants in the IR. A `Module` names its target; a consumer refuses a
  module for a target whose layout it does not implement.
- **The calling convention is not fixed.** `call` carries every argument
  with its memory type and the callee's signature; `ret` carries the
  value with its type; a function's parameters are typed values it
  receives. Where an argument goes, how a structure is returned, what a
  variadic call must set: each consumer decides, and the IR never
  mentions a physical register or a stack.
- **Endianness is not fixed.** Values are values; memory operations are
  typed; initializers are typed values, and only a consumer turns them
  into bytes.

## Decision: names are strings, bound by the consumer

Globals and functions are referred to by name. There is no symbol object
shared with `sema`; the IR is self-contained and serializable, and a
consumer binds names when it loads a module (the VM) or leaves them to
the linker (a code generator). Temporaries, slots and blocks are named
locally, and the text form reproduces them exactly, so a round trip
through `TacWriter` and `TacReader` is the identity.

## Registers

A temporary is a virtual register of one class, declared once per
function:

```
wtemp %name ...     w: the width of int (W bits, 32 on every current target)
ltemp %name ...     l: the width of long long (L bits, 64)
stemp %name ...     s: single precision floating (float)
dtemp %name ...     d: double precision floating (double, and long double where it is double)
xtemp %name ...     x: extended precision, only on targets whose long double is the x87 format
```

The target descriptor says which integer class holds a pointer (`ptr l`
on x86-64, `ptr w` on an ILP32 target). Every integer instruction takes
operands of one class and produces that class; every floating
instruction likewise. A comparison result is a `w` holding 0 or 1.

**Canonical form.** A C value narrower than its register is kept in the
extension its type implies: `char` and `short` sign-extended in `w`,
their unsigned forms and `bool` zero-extended. `int` and `unsigned` fill
`w` exactly; `long` and `unsigned long` fill `l`. `Lower` maintains this,
so that `w` operations give the narrower C results and a value can be
stored with the narrow width without adjustment.

## Operands

```
operand ::= %name            a register; the instruction's class is the class of its registers
          | N                an integer immediate, in the class of the instruction
          | N.N              a floating immediate
[address] ::= [ %reg ]       a register holding an address, of the pointer class
            | [ %reg, N ]    plus a constant byte offset
```

Immediates are allowed as the second operand of an arithmetic, logic or
comparison instruction and as the source of `mov`. Nothing else names a
constant, and nothing but `addr`, `call` and initializers names a global
or a slot.

## Instructions

Every instruction is `%t = op operands` or `op operands`. The width of an
integer instruction is its class; the precision of a floating one is
its class; memory instructions carry the width in memory. Semantics are
a two's complement machine's, made explicit: nothing is undefined in the
IR's own terms except where marked `UB`, and a consumer may then do
anything.

The set is what a RISC has and no more. There is no negate (`wsub 0,
x`), no bitwise not (`xor x, -1`), no greater-than (swap the operands of
a less-than), no select (a branch), no sign or zero extension inside a
class (shifts and masks), no pointer arithmetic apart from integer
arithmetic, no reinterpretation (store and load), and no `unreachable`
apart from `trap`.

**Moves**:

```
%t = mov %s | N                 a register of the same class, or an immediate
%l = widen %w                   w to l, zero-extending; a sign extension is widen then shl, ashr by L-W
%w = narrow %l                  the low W bits
```

**Integer arithmetic and logic** (one class in, the same class out):

```
wadd wsub wmul                 wrap modulo 2^width of the class
add sub mul                    overflow beyond the class is UB
sdiv udiv srem urem            truncate toward zero; division by zero and the most negative value / -1 are UB
and or xor
shl lshr ashr                  the amount is a register or immediate of the same class; amounts >= width are UB
```

Both forms of the first two rows exist so that `Lower` can say what C
said: unsigned arithmetic wraps, signed overflow is undefined, and a
consumer may exploit the difference or simply wrap. `unsigned int`
addition is `wadd` in `w` and needs nothing more; `unsigned char`
addition is `wadd` in `w` then `and 0xff` to restore canonical form;
`int` addition is `add` alone. Pointer arithmetic is `wadd` in the
pointer class with the offset already scaled by `Lower`: `p[i]` is `wmul
%i, 8` then `wadd %p, %o`; `s.m` is usually no instruction at all, since
the member's offset goes into the load or store.

**Extension** is two sequences, listed because `Lower` emits them and a
code generator pattern-matches them:

```
sign-extend from N bits       %t = shl %x, width-N  then  %t = ashr %t, width-N
zero-extend from N bits       %t = and %x, 2^N - 1
```

**Floating arithmetic** (one class in, the same class out): `fadd fsub
fmul fdiv`, IEEE round-to-nearest at the class's precision. Negation is
`fsub -0.0, x`, exact under IEEE. There is no `frem`; C's `fmod` is a
library call.

**Class conversions** are the only conversions, because the classes are
different registers:

```
%f = i2f %x                    signed integer register to a floating register; the classes are those of the registers
%f = u2f %x                    unsigned integer register to floating
%x = f2i %f                    floating to signed integer, truncating toward zero; out of range UB
%x = f2u %f                    floating to unsigned integer; out of range UB
%f = fcvt %g                   between floating classes, rounding or exact
```

A narrower C integer result of `f2i` is made canonical by the extension
sequences.

**Comparisons** (a `w` holding 0 or 1; the operands one integer or one
floating class):

```
eq ne
slt sle                        signed
ult ule                        unsigned, and pointers
feq flt fle                    ordered: 0 if either operand is NaN
fne                            unordered or not equal: 1 if either operand is NaN
```

`a > b` is `slt b, a`; `a >= b` is `sle b, a`. A test for zero is `ne x,
0`. Canonical values make a `w` comparison the narrower C comparison.

**Memory**, the only instructions that touch it; the address is a
register of the pointer class plus a constant offset:

```
%x = load.s8  [addr]           sign-extended into the destination's class; also .s16 .s32 .s64
%x = load.u8  [addr]           zero-extended; also .u16 .u32 .u64
%f = load.f32 [addr]           into an s register; .f64 into d; .f80 into x
store.8 %x, [addr]             the low 8 bits of the register; also .16 .32 .64 .f32 .f64 .f80
copy %P [dst], [src]           the bytes of the named aggregate type, non-overlapping
zero %P [addr]                 all bytes of the aggregate type to zero
%a = addr $slot | @name        the address of a slot or a global into a pointer-class register
```

`align N` and `volatile` may follow a load or store. `load.s64` and
`load.u64` are the same into `l`; a `load.s32` into `l` is how a `long`
is read from an `int` object. Bit-fields are a load of the storage unit,
`and`/`or`/`shl`/`lshr`, and a store, with the masks and shifts computed
by `Lower` from the typer's placement. A struct assignment is `copy`; the
zero part of an initializer is `zero` followed by stores.

**Control** (each block ends with exactly one of these):

```
br .block
condbr %w, .then, .else                         nonzero takes .then
switch %x, .default, [ N -> .block, ... ]       distinct constants, in the register's class
ret                                             from a void function
ret type %x                                     the value with its memory type; an aggregate by its address
trap "message"                                  stops the program; also where control cannot arrive
```

`switch` lists single values; `Lower` turns a `case low ... high` into a
comparison chain ahead of it.

**Calls**, direct and indirect:

```
%r = call sig @name ( type %arg, ... )          a named function: the callee is known statically
%r = icall sig %f ( type %arg, ... )            a pointer to function in a register: the callee is a run-time value
     call / icall ...                           void result
     call / icall ... into %P [addr]            aggregate result, written to the address
```

The two are distinct instructions, as `DirectCall` and `IndirectCall`
are distinct nodes in the typed tree, so a consumer dispatches on the
instruction and never inspects an operand to learn which it has. For the
VM, `call` is a lookup of the name in the loader's table, bound late, and
`icall` a lookup of the register's value in the code segment; for a code
generator, `call` is a call to a symbol and `icall` a call through a
register. `sig` is the callee's function type as the caller sees it: the
prototype for `call`, the pointer's type for `icall`. Each argument is a
register with its memory type, which is what the calling convention
classifies by, or for an aggregate parameter the aggregate type and a
register holding its address; the callee receives its own copy, made by
the consumer. For a variadic call `sig` ends in `...` and the arguments
past the fixed ones are already promoted by the typer. A consumer applies
its target's calling convention to exactly this information and nothing
else.

**Variadic functions** are defined with `...` in their signature and use
two instructions on a `va_list` object, whose type `%va_list` the target
descriptor gives as an aggregate:

```
va_start [addr]
%x = va_arg.s32 [addr]                          the widths and extensions of load; an aggregate type into [addr]
```

`va_end` is nothing and `va_copy` is `copy %va_list`.

## Functions

```
define [linkage] @name ( type %param, ... [, ...] ) -> type {
  slot $name : type align N ...        the frame: one per parameter, local, compound literal, temporary object
  wtemp / ltemp / stemp / dtemp / xtemp %name ...
.block:                                the first block is the entry; every block has a name
  instruction ...
}
declare [linkage] @name sig            a function defined elsewhere or by the consumer (the VM's builtins)
```

Parameters are values received in registers of the class their memory
type implies, canonical. Because C treats a parameter as an object with
an address, `Lower` gives each one a slot and stores the incoming value
at entry, as in the example. An aggregate parameter's slot is
initialized by the consumer at entry with the argument's bytes, and its
register holds the slot's address. A consumer that promotes slots to
registers may, and the code generator will.

Slots are named, never addressed by a frame offset: a code generator lays
the frame out and a VM allocates slots as it likes. Their lifetime is the
call. `linkage` is `external` (the default) or `internal` (`static`). A
`static` local is a global with `internal` linkage named
`function.variable[.ordinal]`, so the same source gives the same name
every time.

## Globals

```
global [linkage] @name : type align N [readonly] [= initializer]
initializer ::= scalar-const | addr @name [+ N] | bytes "..." | zero type
              | { initializer, ... }               a struct or array, one item per member or element
```

A scalar constant in an initializer is written with its memory type
(`i32 0`, `f64 1.5`), since bytes are what it becomes. A global without an
initializer is zero; an `extern` not defined in the module is `declare
@name : type`. `addr @name + N` is a relocation. `bytes` is for string
literals and is the one initializer that is already bytes. A consumer
turns the initializer into memory in its own byte order.

String literals are globals named `@.str.<hash>` with `internal` linkage
and `readonly`, so a loader can share one copy across modules compiled
from the same text.

## Types

Types describe memory and interfaces, never registers:

```
type    ::= int | float | ptr | array | struct | func
int     ::= i8 | i16 | i32 | i64                 storage widths
float   ::= f32 | f64 | f80 | f128               IEEE binary32/64/128 and the x87 extended format
ptr     ::= ptr                                  the target's pointer width
array   ::= [ N x type ]
struct  ::= { member, ... }                      each member: type, byte offset; size and alignment given
func    ::= ( type, ... [, ...] ) -> type        parameter types, variadic flag, return type or void
```

A C `bool`, `char`, enum or `_BitInt` up to 64 bits is the `int` of its
storage width. Each scalar memory type has a register class: `i8`, `i16`
and `i32` load into `w` or `l` as the instruction says, `i64` into `l`,
`ptr` into the pointer class, `f32` into `s`, `f64` into `d`, `f80` into
`x`. Structures are named in the module (`type %P = { ... }`); a union is
a structure whose members all have offset zero; a bit-field member has
the type of its storage unit.

## Module

```
target name                    x86_64-sysv, ilp32-test, ...: W, L, the pointer class, endianness, the long
                               double class, %va_list; the consumer checks it against what it implements
type %name = { ... }           named structure types, in dependency order
global / declare / define      in any order
```

A `Module` is the output of one compilation, whether of a file or of the
shell's accumulated text. Nothing in it depends on any other module
except through names.

## Well-formedness (`TacInvariants`)

- every register is declared once in one class, and every instruction's
  registers are of the classes it requires, all operands of an arithmetic,
  logic or comparison instruction in one class;
- an address register is of the pointer class; a `widen` goes from `w` to
  `l` and a `narrow` from `l` to `w`; `fcvt` joins two different floating
  classes; `i2f`/`f2i` join an integer and a floating class;
- every block ends with exactly one terminator (`br`, `condbr`, `switch`,
  `ret`, `trap`) and has none elsewhere; every branch target is a block of
  the same function; the entry block has no predecessors;
- every `@name` in `addr`, `call` or an initializer is defined or
  declared in the module; every `$name` is a slot of the function; every
  named type is defined before use;
- a `call`'s name is a defined or declared function and an `icall`'s
  callee is a pointer-class register; the arguments match `sig` in count
  and memory type, and each register's class matches its memory type;
  `into` is present exactly when the result is an aggregate; `ret` carries
  the function's return type in a register of the matching class;
- memory widths and classes are ones the target has; `switch` values are
  distinct;
- a `readonly` global has an initializer; `bytes` initializers are only
  for `[N x i8]`.

Canonical form is not an invariant the checker can see; it is `Lower`'s
obligation, tested by running programs.

## What makes it easy to interpret

- A frame is an `int[]` for `w`, a `long[]` for `l`, a `float[]` and a
  `double[]` (and a `double[]` standing in for `x`), indexed by register
  number, plus the slot addresses computed once at entry. Every integer
  instruction is the Java operation of its class; there is no width
  logic inside an instruction.
- Memory is touched by `load`, `store`, `copy` and `zero` only, each with a
  fixed width, so bounds and alignment checks live in four places.
- `addr` resolves a slot at entry and a global at load; nothing else
  names either.
- Every instruction is a record with resolved operand references, so
  dispatch is a `TacVisitor` call per instruction and blocks are arrays
  with a program counter; `switch` is a lookup; `call` is a name looked up
  in the loader's table and `icall` a register looked up in the code
  segment, then a new frame and a copy of arguments.
- A join needs nothing. Nothing requires analysis.

## What makes it easy to generate code from

- Basic blocks with explicit terminators are the control-flow graph.
- Virtual registers in classes that correspond to physical register
  files (`w`/`l` to the integer file, `s`/`d` to the vector file), with
  computable liveness; SSA construction is the code generator's to do in
  its own IR.
- Widths only on memory access, addresses as register plus constant,
  which is the addressing mode every machine has; `addr` of a slot is a
  frame-pointer offset and of a global a symbol.
- Comparisons produce 0 or 1 and `condbr` tests a register, which fuse
  into flags and a conditional branch, or a compare-and-branch.
- The extension sequences are recognizable patterns: `shl`/`ashr` by the
  same constant is `movsx`, `and` with `2^N - 1` is `movzx`, `widen` is a
  32-bit move on x86-64 and `narrow` is free.
- Calls carry the full signature and by-value aggregates with their
  member types, so a code generator has everything an ABI classifier
  needs, and nothing has been pre-lowered for one ABI.

## Lowering from the typed tree

The mapping is direct; the table is the specification of `Lower`:

| Typed tree | TAC |
|---|---|
| `IntConst`, `FloatConst`, `NullptrConst` | `mov` of an immediate in the class of the C type; null is `0` |
| `AddrConst(symbol, offset)` | `addr @name`, then `wadd` of the offset |
| `VarRef` of a local or parameter | `addr $name`; of a global, `addr @name`; folded into the load's or store's offset where possible |
| `LvalueToRvalue` | `load.sN` or `load.uN` into the class of the C type; a bit-field: load the unit, `lshr`, `and`, or `shl` then `ashr` for a signed one |
| `ArrayDecay`, `FunctionDecay` | the address itself |
| `Deref` | the pointer register is the address |
| `Member` | the member's offset in the address operand of the load or store, or `wadd` when the address escapes |
| `Materialize`, compound literal | a slot |
| `IntToInt` | nothing within a class when the value is canonical for the destination; the extension or mask sequence otherwise; `widen` then, for a signed source, `shl`/`ashr`; `narrow` |
| `IntToFloat`, `FloatToInt` | `i2f`/`u2f`, `f2i`/`f2u` by the integer type's signedness, then the canonical sequence for a narrow integer |
| `FloatToFloat` | `fcvt` |
| pointer conversions | nothing, or `widen`/`narrow` and the mask sequence |
| `ToBool` | `ne x, 0` or `fne` |
| `Arithmetic`, `Shift` | `wadd`/`wsub`/`wmul` for unsigned types, then the mask for types narrower than `w`; `add`/`sub`/`mul` for signed; `sdiv`/`udiv`/`srem`/`urem`; `shl`, `lshr` or `ashr` by signedness, then the canonical sequence for narrow types; the `f` forms in the class of the C type |
| `Unary` | negation `wsub 0, x` or `sub 0, x` or `fsub -0.0, x`; bitwise not `xor x, -1` then the mask for unsigned narrow types; logical not `eq x, 0` |
| `Comparison` | `eq ne slt sle ult ule feq fne flt fle`, operands swapped for `>` and `>=`; the result is already a canonical `int` |
| `Logical` | blocks with short circuit |
| `Cond` | blocks |
| `PtrAdd`, `PtrDiff` | `wmul` by the element size then `wadd`; `wsub` then `sdiv` by the element size |
| `Assign`, `CompoundAssign`, `PostfixAssign`, `TargetValue` | address evaluated once into a register, `load`/`store`; aggregates `copy` |
| `DirectCall`, `IndirectCall` | `call @name` and `icall %p` respectively; aggregate arguments by address; `into` for aggregate results |
| `Comma` | in order |
| `Block`, `ExprStmt`, `LocalDecl` | instructions; `LocalDecl` with `TInit` is `zero` then stores |
| `If`, `While`, `DoWhile`, `For` | blocks; `JumpTarget` is a block |
| `Switch`, `Case`, `CaseRange` | `switch` on single values, each label a block; a range is a comparison chain ahead of the `switch` |
| `Labeled`, `Goto`, `Break`, `Continue` | `br` to the target's block |
| `Return` | `ret`, aggregates by address |
| `TUnit.Global` with `TInit` | `global` with a structured initializer built from the items, `zero` for the rest |
| `StringData` | `@.str.<hash>` with `bytes` |
| `TFunction` | `define` with slots from `parameters` and `locals`, registers as allocated |

## Packages and classes

```
org.jbm.cc.tac
  Type                        sealed: Int(width), Float(format), Ptr, Array(element, count), Struct(name, members), Func(params, variadic, ret)
  RegClass                    W, L, S, D, X
  Operand                     sealed: Reg(class, number), IntImm, FloatImm; Address(reg, offset)
  Instr                       sealed, one record per instruction above, each carrying the C token it came from
  Block, Function, Global, Module, Target descriptor
  TacVisitor<R>               one visit per instruction kind; the VM's and the code generator's dispatch
  TacWriter, TacReader        the text form, round-trip exact
  TacInvariants               the rules above; run in tests and optionally on load
org.jbm.cc.lower
  Lower                       TUnit to Module
  ExprLower                   expressions to registers, with canonical form; lvalues to addresses
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
- **Canonical form**: programs whose results depend on it (narrow
  unsigned wrap, the sign of `char`, mixed-width comparisons, `int` to
  `long` widening, shifts of narrow types) in the run corpus, checked
  against `gcc`'s output by the VM's suite (`cshell-plan.md`).
- **Coverage**: every `Instr` kind and every row of the mapping table
  appears in the corpus.

## Steps

Each step is one commit with the suite green and `Main` still running.

1. [ ] **The model**: `Type`, `RegClass`, `Operand`, `Instr`, `Block`,
   `Function`, `Global`, `Module`, the target descriptor, `TacVisitor`;
   hand-built module tests.
2. [ ] **`TacWriter`** and **`TacInvariants`**; the text form fixed by tests.
3. [ ] **`ExprLower`, scalars**: constants, arithmetic with canonical form,
   comparisons, the extension sequences, `widen`/`narrow`, the class
   conversions, `Logical`, `Cond`, `Comma`; the lowering corpus starts.
4. [ ] **Objects and addresses**: slots and globals through `addr`, offsets
   folded into loads and stores, `VarRef`, `Deref`, `AddrOf`, `Member`,
   bit-fields, decay, `PtrAdd`, `PtrDiff`, the assignments, `Materialize`
   and compound literals.
5. [ ] **Calls and functions**: `call` and `icall` in every form, by-value
   aggregates, variadic arguments, `Return`, `define` with its frame;
   `va_*` for variadic definitions.
6. [ ] **Statements**: blocks, `If`, loops, `Break`, `Continue`, `Goto`,
   labels, `Switch`, `LocalDecl` initialization.
7. [ ] **Data**: `DataLower`, string literals, statics, tentatives; `Module`;
   `Lower.lower(TUnit)`; `Main` prints the TAC.
8. [ ] **`TacReader`** and the round trip on the whole corpus.

### Deferred
- `i128` and `_BitInt` above 64 bits (two `l` registers)
- atomics and memory ordering
- dynamic `alloca` for variable-length arrays
- thread-local globals
- `f128` arithmetic in the VM (carried, not executed)
- inline assembly, which is by definition not architecture-agnostic

# TAC: architecture and plan

The three-address code is the compiler's output and the boundary between
the front end (`Lower` produces it from the typed tree) and everything
that consumes a program: the VM of `cshell-plan.md`, the x86-64 code
generator, and any other code generator later. It is **target-specified**:
a module is compiled for one named target and carries that target's
widths, sizes, member offsets and alignments as constants, so no consumer
computes a layout. It is a **load/store machine over typed variables**:
a function declares its variables with their types; `mov` writes a
variable and any other instruction reads variables and writes one;
`load` and `store`, each with its width, go through a pointer and are
the only instructions that touch memory; `addrof` is the only way a
variable's storage is observed. Where a variable lives, in a register or
in the frame, is the consumer's decision. There is one integer register
width and one floating one; an instruction that works on a narrower
value says so with a **modifier**, `add.s32`, the way x86 names `eax`
inside `rax`. Everything a wider instruction set would do with a special
instruction (extend, select, negate) is done with the modifiers, shifts,
masks and branches. The IR stays neutral on the two things a target
fixes only at the very end: the calling convention and the byte order.
This document is the contract; `org.jbm.cc.tac` implements it.

```
target x86_64-sysv                       ; registers 64, ptr 64, little, long double x87
type %P = { i32 @0, i32 @4 }             ; size 8, align 4
global @counter : i32 align 4 = { 0 : i32 0 }
global @greeting : [6 x i8] align 1 readonly = { 0 : bytes "hello\00" }
declare @printf(ptr, ...) -> i32

define @sq(i32 %x) -> i32 {              ; int sq(int x) { return x * x; }
  i32 %t0
.entry:
  %t0 = mul.s32 %x, %x
  ret %t0
}

define @f(i32 %c) -> i32 {               ; int f(int c) { int x = 1; int *p = &c; *p = 5; return x; }
  i32 %x
  ptr %p
.entry:
  mov %x, 1
  %p = addrof %c
  store.32 %p, 5
  ret %x
}
```

## Decision: typed variables, storage decided by the consumer

Alternatives considered: (a) an IR with virtual registers and explicit
frame slots, where the compiler decides which C objects live in memory
and inserts the loads and stores, the shape of LLVM IR before `mem2reg`;
(b) an IR whose only notion is a typed variable, read as an operand,
written by `mov` or by being an instruction's result, and given an
address only by `addrof`. We chose (b):

1. [x] **The compiler makes no storage decision.** Whether `x` needs memory
   depends on whether `addrof %x` appears anywhere in the function, which
   is a property of the whole body. In (a) `Lower` would have to know it
   before lowering the first use; in (b) it emits `mov %x, 1` and `addrof
   %x` where the tree says so and is done.
2. [x] **Each consumer places variables as it can.** A code generator
   promotes every variable that is never under `addrof` to a virtual
   register and gives the rest frame slots, which is the `mem2reg` every
   backend has. A VM may put every variable in its frame region of
   simulated memory, or keep them in arrays and place only the
   address-taken ones in memory after one look at the function at load
   time. Both are correct; neither is the compiler's concern.
3. [x] **The common case costs nothing.** A scalar local or parameter
   whose address is never taken is used directly, so `sq` above is one
   instruction. In (a) it would be a store at entry and a load per use
   until a later pass removed them.
4. [x] **Aliasing has one rule.** After `addrof %x`, a `store` through the
   pointer is visible in `%x` and a `mov %x` is visible through the
   pointer. A consumer that keeps address-taken variables in memory gets
   this for free; a code generator's promotion excludes them.

## Decision: the width is on the memory instruction

`load.s8`, `load.u16`, `load.f64`, `store.32`: a memory instruction says
how many bytes it moves and, for a load, how it extends them. Pointers
have one type, `ptr`: an address of the target's pointer width, held in
an integer register and operated on by the integer instructions, so
that a pointer can be copied, compared, converted and added to like an
integer while a signature still says "pointer" to a code generator's
ABI classifier. Alternatives: typed pointers (`i32*`) from which a
consumer derives the width, which puts the information on the variable
rather than on the instruction that uses it; an address form `[%p,
4]`, which is an addressing mode the IR does not need since `p->m` is
`%q = wadd %p, 4` then `load.s32 %q`, and a code generator folds that
into its addressing mode with one peephole. A consumer reads the width
off the instruction and nothing else.

## Decision: one register width, and a modifier for narrower operations

There is one integer register file, as wide as the target's widest
integer (64 bits on every current target), and one floating file. An
integer instruction that computes at a narrower width says so with a
modifier: `add.s32 %a, %b` adds the low 32 bits of its operands and
writes the 32-bit result **sign-extended** into the register; `add.u32`
writes it **zero-extended**; `mov.s8 %d, %a` takes the low 8 bits of `%a`
sign-extended. Without a modifier an instruction uses the whole
register. Alternatives considered: (a) two integer classes, `int`-wide
and `long`-wide, which spares the modifier but puts the width of every
instruction into the declarations of its variables; (b) one width and no
modifier, which makes narrow arithmetic a full-width operation followed
by a mask or a shift pair. The modifier was chosen because:

1. [ ] **Every value is held extended as its own type implies**, and the
   instruction that writes it is what guarantees that: a `.sN` result is
   sign-extended, a `.uN` result zero-extended, a `load.s8` sign-extends,
   a `load.u8` zero-extends. No pass has to maintain an invariant the
   checker cannot see; the extension is part of the instruction's
   definition, as it is on RISC-V's `addw` and on x86's 32-bit writes.
2. [ ] **Widening is free and narrowing is one instruction.** A `char` is
   already the `int` it promotes to; an `int` is already the `long`;
   `unsigned` to `unsigned long` is already the zero-extended value. Only a
   conversion that must rewrite the bits above the new width costs an
   instruction, and it is a `mov.sN` or `mov.uN`.
3. [ ] **Only the instructions that can disturb the upper bits carry a
   modifier**: arithmetic, logic, shifts and `mov`. A comparison, a
   `switch` or a `condbr` on values that are already extended is correct
   at the full width, so those have none.
4. [ ] **An interpreter is one `long[]` and one `double[]` per frame**, and a
   `.s32` operation is `(long) (int) result`, one cast. A code generator
   maps `add.s32` onto its 32-bit add and inserts a sign-extending move
   only where a 64-bit use follows and its machine does not already
   extend, which its liveness information tells it.

Floating instructions carry their precision the same way: `fadd.32`
computes and rounds in single precision, `fadd.64` in double, `fadd.80`
in the x87 format on targets that have it, and the register holds the
value at the widest precision the target has. Widening between formats
is exact and costs nothing; `fcvt.32` rounds to single.

## Decision: not SSA; there is no `phi`

A variable may be written any number of times. Control flow is blocks
and branches; a value that differs by path is a variable written on each
path before the join. `Lower` is a direct walk of the typed tree. The VM
evaluates a condition, jumps, and interprets the block it lands in; there
is nothing to do at a join. A code generator that wants SSA builds it in
its own IR.

## Decision: layout-explicit, ABI-neutral, endianness-neutral

- **Layout is fixed.** Widths, sizes, alignments, member offsets and
  bit-field placements come from the `Target` the typer used and are
  constants in the IR. A `Module` names its target; a consumer refuses a
  module for a target whose layout it does not implement. Structure
  types are first class because a calling convention classifies a
  by-value aggregate by its member types, and the code generator, where
  the C ABI lives, must see what C saw.
- **The calling convention is not fixed.** `call` carries every argument
  with its type and the callee's signature; `ret` carries the value; a
  function's parameters are typed variables it receives. Where an
  argument goes, how a structure is returned, what a variadic call must
  set: each consumer decides, and the IR never mentions a physical
  register or a stack.
- **Endianness is not fixed.** Values are values; memory operations are
  typed; initializers are typed items at offsets, and only a consumer
  turns them into bytes.

## Decision: names are strings, bound by the consumer

Globals and functions are referred to by name. There is no symbol object
shared with `sema`; the IR is self-contained and serializable, and a
consumer binds names when it loads a module (the VM) or leaves them to
the linker (a code generator). Variables and blocks are named locally,
and the text form reproduces them exactly, so a round trip through
`TacWriter` and `TacReader` is the identity.

## Types

```
type    ::= int | float | ptr | array | struct | func | void
int     ::= i8 | i16 | i32 | i64                 signed, of that storage width
          | u8 | u16 | u32 | u64                 unsigned
ptr     ::= ptr                                  an address, of the target's pointer width
float   ::= f32 | f64 | f80 | f128               IEEE binary32/64/128 and the x87 extended format
array   ::= [ N x type ]
struct  ::= { type @offset, ... }                members with their byte offsets; size and alignment given
func    ::= ( type, ... [, ...] ) -> type        parameter types, variadic flag, return type or void
```

A C `bool`, `char`, enum or `_BitInt` up to 64 bits is the `int` of its
storage width and signedness (`bool` is `u8`; plain `char` is `i8` or
`u8` as the target says). A variable's type says how it is stored, what
a `load` or `store` of it moves, and how its value is extended in the
register; the operations say their width and signedness themselves
(`sdiv`/`udiv`, `wadd.u32`/`add.s32`, `slt`/`ult`, `load.s8`/`load.u8`)
so that no instruction's meaning depends on looking a type up. `f80` is
x86's `long double`; a consumer without the format may execute it as
`f64` and must say so. Structures are named in the module (`type %P = {
... }`); a union is a structure whose members all have offset zero; a
bit-field member has the type of its storage unit.

**Classes.** Every integer type and `ptr` is the integer class; every
floating type is the floating class. An aggregate has no class: a
variable of aggregate type is storage, and the only thing an
instruction can do with it is `addrof`.

**Extension.** A value narrower than the register is held sign-extended
if its type is signed and zero-extended if it is unsigned, and every
instruction that writes a narrow value extends it that way: the `.sN`
and `.uN` modifiers, `load.sN` and `load.uN`. A value of a 64-bit type
fills the register. A comparison result is 0 or 1 in the whole register.

## Variables and operands

```
declaration ::= [volatile] type %name          in the function's header; parameters in the signature
operand     ::= %name                          a variable, read
              | N | N.N                        an integer or floating immediate
```

Immediates are allowed as the second operand of an arithmetic, logic or
comparison instruction, as the source of `mov`, and as the value of
`store`. A `volatile` variable's reads and writes happen as written; a
consumer gives it storage.

## Instructions

Every instruction is `%t = op operands` or `op operands`. Semantics are
a two's complement machine's, made explicit: nothing is undefined in the
IR's own terms except where marked `UB`, and a consumer may then do
anything.

The set is what a RISC has and no more. There is no negate (`wsub 0,
x`), no bitwise not (`xor x, -1`), no greater-than (swap the operands of
a less-than), no select (a branch), no sign or zero extension apart from
the modifiers, no pointer arithmetic apart from integer arithmetic, no
reinterpretation (store and load), no addressing mode, and no
`unreachable` apart from `trap`.

**Variables and addresses**:

```
mov %x, %y | N                 write a variable: a copy of the whole register, or an immediate
mov.sN %x, %y                  the low N bits of %y, sign-extended; N is 8, 16 or 32
mov.uN %x, %y                  the low N bits, zero-extended
%p = addrof %x | @name         the address of a variable or a global, into a ptr variable
```

`mov.sN` and `mov.uN` are the integer conversions that cost an
instruction: a narrowing, or a change of signedness at the same width.
Every other integer conversion is nothing, because the value is already
held as the destination requires:

```
char to int, int to long          nothing: sign-extended already
unsigned char to int              nothing: zero-extended already, and non-negative
unsigned to unsigned long         nothing: zero-extended already
int to unsigned long              nothing: the sign extension is the 64-bit value
int to char                       mov.s8
int to unsigned                   mov.u32: the bits above 31 must become zero
signed char to unsigned short     mov.u16: wider, but a negative value must lose its sign extension
```

**Integer arithmetic and logic**, each with an optional modifier `.sN`
or `.uN` (N 8, 16 or 32) that computes in N bits and extends the
result; without one, the whole register:

```
wadd wsub wmul                 wrap modulo 2^N
add sub mul                    overflow beyond N bits is UB
sdiv udiv srem urem            truncate toward zero; division by zero and the most negative value / -1 are UB
and or xor
shl lshr ashr                  the amount is any integer operand; amounts >= N are UB
```

Both forms of the first two rows exist so that `Lower` can say what C
said: unsigned arithmetic wraps, signed overflow is undefined, and a
consumer may exploit the difference or simply wrap. `int` addition is
`add.s32`, `unsigned` addition `wadd.u32`, `long` addition `add`. A
division, `and`, `or` or `lshr` of extended values is correct without a
modifier and `Lower` emits one anyway for uniformity; `xor` and `shl`
need theirs, since they can set bits above the width. Pointer arithmetic
is `wadd` with the offset already scaled by `Lower`, with a `.u32`
modifier on a 32-bit target: `p[i]` is `wmul %i, 8` then `wadd %p, %o`;
`p->m` is `wadd %p, 4`. A `_BitInt` whose width is not a register width
is computed at its storage width and then masked or shifted to its own.

**Floating arithmetic**, each with the precision as its modifier:
`fadd.P fsub.P fmul.P fdiv.P`, IEEE round-to-nearest at P, which is 32,
64 or 80. Negation is `fsub.P -0.0, x`, exact under IEEE. There is no
`frem`; C's `fmod` is a library call.

**Between integer and floating**, the only conversions that are
instructions; each names the floating precision:

```
%f = i2f.P %x                  signed integer (the whole register) to floating at precision P
%f = u2f.P %x                  unsigned integer to floating
%x = f2i.P %f                  floating at P to a signed integer, truncating toward zero; out of range UB
%x = f2u.P %f                  floating to an unsigned integer; out of range UB
%f = fcvt.P %g                 rounds to precision P; a widening is exact and is no instruction
```

An integer narrower than 64 bits is already extended, so `i2f.64 %c`
converts a `char` directly; the result of `f2i.64` into an `int` fits,
or the conversion was undefined, so no narrowing follows it.

**Comparisons** (0 or 1 in the whole register; the operands both
integer or both floating, and already extended, so there is no
modifier):

```
eq ne
slt sle                        signed
ult ule                        unsigned, and pointers
feq flt fle                    ordered: 0 if either operand is NaN
fne                            unordered or not equal: 1 if either operand is NaN
```

`a > b` is `slt b, a`; `a >= b` is `sle b, a`. A test for zero is `ne x,
0`. Extended values make a full-width comparison the narrower C
comparison: two `int`s compare as `slt`, two `unsigned char`s as `ult`.

**Memory**, the only instructions that touch it, always through a `ptr`
variable, each saying its width:

```
%x = load.s8  %p               one byte, sign-extended into the register; also .s16 .s32 .s64
%x = load.u8  %p               zero-extended; also .u16 .u32 .u64
%f = load.f32 %p               single precision; also .f64 .f80
store.8 %p, %x | N             the low 8 bits of the variable or immediate; also .16 .32 .64 .f32 .f64 .f80
copy %P %q, %p                 the bytes of the named aggregate type, from %p to %q, non-overlapping
zero %P %p                     all bytes of the aggregate type to zero
```

`align N` and `volatile` may follow a `load` or `store`. `Lower` loads a
variable of type `i8` with `load.s8` and one of type `u16` with
`load.u16`, so the value arrives extended as its type requires; a
`load.s8` into an `i32` is legal and simply sign-extends a byte into an
`int`. Bit-fields are a `load` of the storage unit, `and`/`or`/`shl`/
`lshr` at the full width, and a `store`, with the masks and shifts
computed by `Lower` from the typer's placement. A struct assignment is
`copy`; the zero part of an initializer is `zero` followed by stores.

**Control** (each block ends with exactly one of these):

```
br .block
condbr %w, .then, .else                         nonzero takes .then
switch %x, .default, [ N -> .block, ... ]       distinct constants
ret                                             from a void function
ret %x                                          the function's return type says how; for an aggregate, %x is a
                                                ptr to the bytes, which may be this frame's: the consumer copies
                                                them before releasing it
trap "message"                                  stops the program; also where control cannot arrive
```

`switch` lists single values; `Lower` turns a `case low ... high` into a
comparison chain ahead of it.

**Calls**, direct and indirect:

```
%r = call sig @name ( operand, ... )            a named function: the callee is known statically
%r = icall sig %f ( operand, ... )              a pointer to function in a variable: the callee is a run-time value
     call / icall ...                           void result
     call / icall ... into %p                   aggregate result, written through %p
```

The two are distinct instructions, as `DirectCall` and `IndirectCall`
are distinct nodes in the typed tree, so a consumer dispatches on the
instruction and never inspects an operand to learn which it has. For the
VM, `call` is a lookup of the name in the loader's table, bound late, and
`icall` a lookup of the variable's value in the code segment; for a code
generator, `call` is a call to a symbol and `icall` a call through a
register. `sig` is the callee's function type as the caller sees it: the
prototype for `call`, the pointer's type for `icall`; it gives every
argument's type, which is what the calling convention classifies by. An
aggregate argument is passed as a pointer to its bytes, and the callee
receives its own copy, made by the consumer. For a variadic call `sig`
ends in `...` and the arguments past the fixed ones are already promoted
by the typer. A consumer applies its target's calling convention to
exactly this information and nothing else.

**Variadic functions** are defined with `...` in their signature and use
two instructions on a pointer to a `va_list` object, whose type
`%va_list` the target descriptor gives as an aggregate:

```
va_start %ap
%x = va_arg.s32 %ap                             the widths and extensions of load; for an aggregate, va_arg %P %ap into %p
```

`va_end` is nothing and `va_copy` is `copy`.

## Functions

```
define [linkage] @name ( type %param, ... [, ...] ) -> type {
  [volatile] type %name ...            every other variable the body uses, with its type
.block:                                the first block is the entry; every block has a name
  instruction ...
}
declare [linkage] @name sig            a function defined elsewhere or by the consumer (the VM's builtins)
```

Parameters are variables received extended for their type; an
aggregate parameter is storage the consumer fills at entry. A variable's
lifetime is the call. `linkage` is `external` (the default) or
`internal` (`static`). A `static` local is a global with `internal`
linkage named `function.variable[.ordinal]`, so the same source gives the
same name every time.

## Globals

```
global [linkage] @name : type align N [readonly] [= { item, ... }]
item ::= offset : scalar-const                    i32 7, f64 1.5, written with the type
       | offset : addr @name [+ N]                a relocation
       | offset : bit/width : int-const           a bit-field: width bits at bit from the offset's byte
       | offset : bytes "..."                     a run of bytes, for string literals
```

An initializer is a list of typed items at byte offsets, applied in
order into an object that starts as all zero, a later item overriding an
earlier one where they overlap. That is the typer's own model of an
initializer (`TInit`) and it needs no structure of its own: a union is
whatever items were written, a designated array element is an item at
its offset, a string is one `bytes` item. A consumer writes each item at
its offset in its own byte order and performs the read-modify-write for
a bit-field item. A global without an initializer is zero; an `extern`
not defined in the module is `declare @name : type`.

String literals are globals named `@.str.<hash>` with `internal` linkage
and `readonly`, so a loader can share one copy across modules compiled
from the same text.

## Module

```
target name                    x86_64-sysv, ilp32-test, ...: the register width, the pointer width,
                               endianness, the long double format, %va_list; the consumer checks it
                               against what it implements
type %name = { ... }           named structure types, in dependency order
global / declare / define      in any order
```

A `Module` is the output of one compilation, whether of a file or of the
shell's accumulated text. Nothing in it depends on any other module
except through names.

## Well-formedness (`TacInvariants`)

- every variable is declared once with a type, and every instruction's
  variables are of the class it requires, all operands of an arithmetic,
  logic or comparison instruction integer or all floating; an aggregate
  variable appears only under `addrof`;
- an integer modifier is `.s8 .u8 .s16 .u16 .s32 .u32` and appears only
  on an integer instruction; a floating modifier is `.32 .64 .80`, one
  the target has, and every floating instruction and every `i2f`, `u2f`,
  `f2i`, `f2u` and `fcvt` has one;
- `load` and `store` go through a `ptr` variable; `copy`, `zero`, `into`
  and an aggregate `ret` take `ptr` variables; `mov` joins two integer
  variables or two floating variables; `i2f`/`f2i` join an integer and a
  floating variable;
- every block ends with exactly one terminator (`br`, `condbr`, `switch`,
  `ret`, `trap`) and has none elsewhere; every branch target is a block of
  the same function; the entry block has no predecessors;
- every `@name` in `addrof`, `call` or an initializer is defined or
  declared in the module; every named type is defined before use;
- a `call`'s name is a defined or declared function and an `icall`'s
  callee is a `ptr` variable; the arguments match `sig` in count and
  class, an aggregate one being a pointer to it; `into` is present
  exactly when the result is an aggregate; `ret` matches the function's
  return type;
- widths are ones the target has; `switch` values are distinct;
- a `readonly` global has an initializer; `bytes` items are only within
  `i8`/`u8` arrays, and every item lies within the object.

The extension of every value is guaranteed by the instruction that
writes it, so there is no invariant the checker cannot see.

## What makes it easy to interpret

- A frame is one `long[]` and one `double[]`, indexed by variable
  number, for the variables the VM keeps out of memory, plus a region of
  simulated memory for those it gives storage: aggregates, `volatile`
  ones, and the ones under `addrof`, which it finds by one look at the
  function when it loads it. The simplest VM gives every variable storage
  and skips the look.
- Every integer instruction is the `long` operation followed, when it
  has a modifier, by one cast: `(long) (int)` for `.s32`, `& 0xffL` for
  `.u8`. `load.s16` is a `short` read, `store.8` writes one byte; the
  width is on the instruction.
- Memory is touched by `load`, `store`, `copy` and `zero` only, so bounds
  and alignment checks live in four places.
- `addrof` of a variable is its storage address, computed at entry; of a
  global, resolved at load; nothing else names either.
- Every instruction is a record with resolved operand references, so
  dispatch is a `TacVisitor` call per instruction and blocks are arrays
  with a program counter; `switch` is a lookup; `call` is a name looked up
  in the loader's table and `icall` a variable looked up in the code
  segment, then a new frame and a copy of arguments.
- A join needs nothing. Nothing requires analysis.

## What makes it easy to generate code from

- Basic blocks with explicit terminators are the control-flow graph.
- Variables in the two classes that are the machine's two register
  files; the ones never under `addrof` are virtual registers with
  computable liveness, the rest are frame slots, which is the promotion
  every backend does first; SSA construction is the code generator's to
  do in its own IR.
- A `wadd %p, N` feeding a `load` or `store` is a register-plus-offset
  addressing mode, and `addrof` of a slot is a frame-pointer offset; both
  are one peephole.
- Comparisons produce 0 or 1 and `condbr` tests a variable, which fuse
  into flags and a conditional branch, or a compare-and-branch.
- The modifiers are the machine's own sub-register instructions:
  `add.s32` is `add r32` followed by `movsxd` only where a 64-bit use
  follows, `mov.u8` is `movzx`, `mov.s16` is `movsx`.
- Calls carry the full signature and by-value aggregates with their
  member types, so a code generator has everything an ABI classifier
  needs, and nothing has been pre-lowered for one ABI.

## Packages and classes

```
org.jbm.cc.tac
  Type                        sealed: Int(width, signed), Float(format), Ptr, Array(element, count),
                              Struct(name, members), Func(params, variadic, ret), Void
  RegClass                    INT, FLOAT, and NONE for aggregates
  Var                         a declared variable: name, type, volatile, class
  Operand                     sealed: Var, IntImm, FloatImm
  Instr                       sealed: one record per instruction, except that the arithmetic, comparison and
                              conversion families are one record each carrying an operation code; the
                              integer modifier is an Int type narrower than the register, the floating one a
                              Float type; each carries the C token it came from
  Block, Function, Global, Module, Target descriptor
  TacVisitor<R>               one visit per instruction kind; the VM's and the code generator's dispatch
  TacWriter, TacReader        the text form, round-trip exact
  TacInvariants               the rules above; run in tests and optionally on load
org.jbm.cc.lower              see lower-plan.md
```

`tac` depends on nothing in `sema` or `tast`; `lower` depends on both and
on `tac`. The VM depends on `tac` only.

## Testing

- **Unit tests on hand-built modules**: every instruction through
  `TacInvariants`, `TacWriter` and `TacReader`, including each rejection
  the invariants make.
- **Lowering corpus**: `src/test/resources/tac/*.tac`, one golden per
  typed-corpus program (`lower-plan.md`).
- **Extension and aliasing**: programs whose results depend on them
  (narrow unsigned wrap, the sign of `char`, mixed-width comparisons,
  narrowing casts, a variable modified through a pointer while also used
  directly) in the run corpus, checked against `gcc`'s output by the
  VM's suite (`cshell-plan.md`).
- **Coverage**: every `Instr` kind and operation appears in the corpus.

## Steps

Each step is one commit with the suite green and `Main` still running.

1. [x] **The model**: `Type`, `RegClass`, `Var`, `Operand`, `Instr`, `Block`,
   `Function`, `Global`, `Module`, the target descriptor, `TacVisitor`;
   hand-built module tests.
2. [x] **`TacWriter`** and **`TacInvariants`**; the text form fixed by tests.
3. [x] **`Lower`**, by the steps of `lower-plan.md`.
4. [ ] **The modifier**: one register width with `.sN`/`.uN` and `.P`
   modifiers in place of the register classes, in the model, the writer,
   the invariants and the lowerer.
5. [ ] **`TacReader`** and the round trip on the whole corpus.

Steps 1 to 3 were implemented in September 2026 on the two-class design
that step 4 replaces. `TacInvariants` reports a violation as an
`IllegalStateException` naming the function and printing the
instruction.

### Deferred
- `i128` and `_BitInt` above 64 bits (two registers)
- atomics and memory ordering
- dynamic `alloca` for variable-length arrays
- thread-local globals
- `f128` arithmetic in the VM (carried, not executed)
- inline assembly, which is by definition not architecture-agnostic

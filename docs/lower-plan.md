# Lower: architecture and plan

`Lower` turns a `TUnit` (the typed tree of `typer-plan.md`) into a
`Module` of the TAC of `tac-plan.md`. It is the last pass of the
compiler proper and the first that produces something a machine could
run. It does no analysis: every decision it makes is read off a node's
type, its symbol, or the `Target` the typer used, and every node kind
lowers to a fixed instruction sequence given in this document. What the
typed tree made explicit (conversions, member offsets, jump targets,
call signatures, temporaries) is what makes that possible, and what the
TAC leaves to the consumer (where a variable lives) is what keeps it
short.

```
struct P { int x, y; };
int f(int c, struct P *s) { int x = 1; int *p = &c; *p = 5; s->y = x + c; return x; }

define @f(i32 %c, ptr %s) -> i32 {
  i32 %x
  ptr %p
  i32 %t0
  ptr %t1
.entry:
  mov %x, 1
  %p = addrof %c
  store.32 %p, 5
  %t0 = add %x, %c
  %t1 = wadd %s, 4
  store.32 %t1, %t0
  ret %x
}
```

## Decision: one direct pass, values carry their C type while being lowered

Alternatives considered: (a) lower to a typed intermediate form and
then "legalize" it into the register machine's classes and canonical
form; (b) one pass whose working value is a TAC variable **plus the C
type it holds**, so that canonical form and classes are decided locally
at each node. We chose (b):

1. [ ] **Every decision is local.** Whether a `wadd` needs a mask, whether
   an `int` to `long` needs a sign extension after its `mov`, whether a
   comparison is `slt` or `ult`: each is a function of the operand's C
   type and the node's C type, both of which the tree already carries.
   There is nothing to infer across nodes.
2. [ ] **Canonical form is an invariant of the working value.** A `Val(var,
   type)` promises that `var` holds `type` canonically. Nodes that can
   break the promise restore it before returning; every other node
   inherits it. That is a rule per node kind, not a pass.
3. [ ] **No second IR to maintain**, and the TAC printer is the pass's
   only debugging aid, which is enough because the output of every node is
   a few instructions next to each other.

## Decision: lvalues are either a variable or a pointer

An lvalue lowers to a `Place`, which is one of two things: a **variable**
(a C local or parameter that the typed tree names by symbol) or a
**pointer** (a `ptr` variable holding the object's address, with the
object's C type, its bit placement if it is a bit-field, and whether the
access is volatile). Reading a variable place is using the variable;
writing it is `mov`. Reading a pointer place is a `load` of the type's
width and signedness; writing it is a `store` of the type's width. A
variable place becomes a pointer place the moment its address is
needed, by `addrof`, which is what `&x`, `x.m` on a local struct and
`a[i]` on a local array do. That is the whole of the lvalue story, and
the choice between register and memory is never `Lower`'s.

## Decision: aggregates are pointers

An rvalue of struct, union or array type is a `Val` whose variable is a
**pointer** to the bytes, never the bytes. `LvalueToRvalue` of an
aggregate is the address of its place; a call returning an aggregate
writes `into` a pointer and yields it; `Assign` of aggregates is `copy`
and yields the target's pointer; an aggregate argument is passed as its
pointer. That is the TAC's rule for aggregates, so no node kind needs a
special case beyond choosing `copy` over `mov`/`store`.

## Decision: dead code is dropped, not emitted

After a terminator the builder is **closed**: `emit` discards
instructions until a block is opened by a label, a loop or a join.
Lowering still walks the dead code (so its blocks and jump targets
exist), but nothing is written for it. Every block in a module is
therefore reachable from the entry or from a `goto`.

## Structure

```
org.jbm.cc.lower
  Lower          the unit: names, string literals, globals and declarations in order, then each function
  Builder        the function under construction: blocks, the current block or closed, variables, emit
  Val            (Var var, CType type): a canonical scalar, or a pointer to an aggregate
  Place          sealed: Variable(Var, CType) | Memory(Var pointer, CType type, Optional<BitField> bits, boolean isVolatile)
  ExprLower      TVisitor<Val>: value(Rvalue); place(Lvalue); read(Place); write(Place, Val); pointer(Place);
                 initialize(Var pointer, TInit) for locals and compound literals
  StmtLower      TStmtVisitor<Void>: statements to blocks; JumpTarget to blocks
  Names          Symbol to TAC name, stable for the same source
```

`Lower.lower(TUnit, Types)` returns the `Module`. `Types` is the typer's,
for `width`, `size`, `align`, `isSigned`, `sizeT` and `ptrdiffT`; the
target descriptor of the module comes from `types.target()`. There is
no data lowering: a global's `TInit` items are already the TAC's items
(see Data).

## Types, classes and canonical form

`tacType(CType)` maps a C type to a TAC type: an integer to `iN`/`uN` by
`width` and `isSigned` (`bool` to `u8`, an enum to its underlying type, a
`_BitInt(N)` to the integer of its storage width); `float`, `double`,
`long double` to `f32`, `f64`, `f80` (or `f64` on a target without
`x`); every pointer to `ptr`; a struct or union to its named `%tag` type,
arrays to `[N x elem]`. The **class** of a scalar is the TAC's: 8-, 16- and 32-bit
integers `w`, 64-bit `l`, pointers the target's pointer class, `f32` `s`,
`f64` `d`, `f80` `x`.

| C type | on x86-64 | on ILP32 |
|---|---|---|
| `bool`, `char`, `short`, `int`, enums, `_BitInt(N)` with `N <= 32` | `w` | `w` |
| `long` | `l` | `w` |
| `long long`, `_BitInt(N)` with `32 < N <= 64` | `l` | `l` |
| pointers, `nullptr_t` | `l` | `w` |

A value of type `t` in its class is **canonical** when it is
sign-extended from `width(t)` to the class width if `t` is signed, and
zero-extended if unsigned. For a type as wide as its class that says
nothing; for `char`, `short`, `bool`, narrow enums and `_BitInt`, it is
a real condition. `canon(var, t)` emits what restores it:

```
signed t, width N < class width:    %v = shl %v, C-N ; %v = ashr %v, C-N
unsigned t, width N < class width:  %v = and %v, 2^N - 1
otherwise:                          nothing
```

Because C promotes every operand of arithmetic to at least `int`, the
typed tree's `Arithmetic`, `Shift`, `Comparison` and `Unary` nodes have
`int` or wider types except for `_BitInt`, so `canon` follows arithmetic
only there. Everywhere else it appears at conversions and bit-fields.

## Expressions

`ExprLower.value(Rvalue)` returns a `Val`; `ExprLower.place(Lvalue)`
returns a `Place`. Operands are evaluated left to right, and where an
instruction wants them in the other order (`Gt`), only the operands are
swapped. The sequences below are what each node kind emits; `%r` is a
fresh variable of the node's TAC type unless stated.

**Constants.** `IntConst`: `mov %r, N`, canonical because the typer
converted it. `FloatConst`: `mov` of the immediate. `NullptrConst`: `mov
%r, 0` in a `ptr`. `AddrConst(base, offset)`: `%r = addrof @name`
then, if the offset is not zero, `%r = wadd %r, offset`; a null base is
`mov %r, offset`.

**Places.** `VarRef` of a parameter or local of scalar type: `Variable`.
`VarRef` of a local of aggregate type, a global, a static local or a
string literal: `Memory` with `%p = addrof %x` or `addrof @name`.
`Deref(p)`: `Memory` with the pointer's variable. `Member(base, m)`:
`pointer(place(base))`, then `Memory` with `%q = wadd %p, m.offset()`
(the same pointer when the offset is zero), `bits = m.bits()`, volatile
if the member's type is. `Materialize` and `CompoundLit`: `Memory` with `addrof` of their
variable, after evaluating them (below). `pointer(Place)` is the pointer
of a `Memory` place, or `addrof` of a `Variable` place.

**Reads and writes.** `read(Place)`: a `Variable` is the variable; a
scalar `Memory` of type `t` is `%r = load.sN %p` or `load.uN` by
`isSigned(t)` and `N = width(t)`, or `load.fN`, canonical by
construction; a bit-field `(bitOffset, width)` of declared type `t`:
`load.uN` the storage unit, then for an unsigned field `lshr` by
`bitOffset` and `and` with `2^width - 1`, for a signed one `shl` by `C -
bitOffset - width` then `ashr` by `C - width`, which lands the value
canonical for `t`; an aggregate `Memory` is its pointer. `write(Place,
Val)`: a `Variable` is `mov %x, %v`; a scalar `Memory` is `store.N %p,
%v`; a bit-field: `load.uN` the unit, `and` it with the inverted placed
mask, `and` the value with `2^width - 1`, `shl` it by `bitOffset`, `or`,
`store.N`; an aggregate is `copy %T %p, %v`. `volatile` is passed through
on every `load` and `store` of a volatile place.

**Conversions**, one row per node, the operand's type `f`, the node's
type `t`:

| Node | Emits |
|---|---|
| `LvalueToRvalue` | `read(place(operand))` |
| `ArrayDecay`, `FunctionDecay` | `pointer(place(operand))`, or `addrof @f` for a function |
| `IntToInt`, same class | `mov %r, %v` into a variable of `t`'s type, then nothing when `f`'s canonical form implies `t`'s (`t` wider than `f` and `f` unsigned or `t` signed, or `t` and `f` of one width and signedness), otherwise `canon(%r, t)` |
| `IntToInt`, `w` to `l` | `mov %r, %v` (zero-extends), then `shl`/`ashr` by `L - W` when `f` is signed |
| `IntToInt`, `l` to `w` | `mov %r, %v` (the low `W` bits), then `canon(%r, t)` |
| `IntToFloat` | `i2f` or `u2f` by `isSigned(f)` |
| `FloatToInt` | `f2i` or `f2u` by `isSigned(t)`, then `canon(%r, t)` |
| `FloatToFloat` | `fcvt`, or `mov` when the classes agree |
| `ToBool` | `%r = ne %v, 0` or `fne %v, 0.0`, a `u8` holding 0 or 1 |
| `ToVoid` | evaluate the operand, return no value |
| `PtrToPtr`, `NullToPtr` | nothing: a `ptr` is a `ptr` |
| `IntToPtr`, `PtrToInt` | as `IntToInt` between the integer's type and an unsigned integer of the pointer width, the `ptr` itself needing no instruction |

The `IntToInt` rule covers the cases a simpler rule misses: `short` to
`unsigned short` is the same width and needs a mask, `signed char` to
`unsigned short` is wider and still needs a mask because the value may
be negative, and `unsigned char` to `short` needs nothing.

**Arithmetic** (`Add`, `Sub`, `Mul`, `Div`, `Rem`, `BitAnd`, `BitOr`,
`BitXor`), operands already of the node's type: `wadd`/`wsub`/`wmul` when
the type is unsigned, `add`/`sub`/`mul` when signed, `fadd`/`fsub`/`fmul`
for floating; `sdiv`/`udiv`/`srem`/`urem` by signedness, `fdiv`;
`and`/`or`/`xor`. `PtrAdd(p, i)`: the index is already `ptrdiff_t`, which
is in the pointer class, so `%o = wmul %i, size` unless the element size
is 1, then `%r = wadd %p, %o`. `PtrDiff(a, b)`: `wsub`, then `sdiv` by the
element size unless it is 1. `_BitInt` results get `canon`.

**Shifts.** `Shl`: `shl`; `Shr`: `ashr` when the left type is signed,
`lshr` otherwise. The right operand was promoted on its own by the typer
and is `int` or `unsigned` in `w`; when the left is in `l` it is
`mov`ed into an `l` first. `_BitInt` results get `canon`.

**Comparisons**, operands of one type, the result a `u8`: `eq`, `ne`;
`slt`/`sle` when the operand type is signed, `ult`/`ule` when unsigned or
a pointer; `feq`, `fne`, `flt`, `fle` for floating. `Gt` and `Ge` emit
`lt` and `le` with the operands swapped.

**Logical.** `And(a, b)`, whose operands are already `bool`:

```
  mov %r, 0
  %a = value(a); condbr %a, .rhs, .done
.rhs: %b = value(b); mov %r, %b; br .done
.done:
```

`Or` with `mov %r, 1` and `condbr %a, .done, .rhs`.

**Unary.** `Neg`: `wsub 0, x` when the type is unsigned, `sub 0, x` when
signed, `fsub -0.0, x` for floating. `BitNot`: `xor x, -1`, then `canon`
for `_BitInt`. `Not`: `eq x, 0` into a `u8`.

**Address-of.** `AddrOf(lv)`: `pointer(place(lv))`.

**Calls.** For each argument, `value(arg)`: a scalar is passed as its
variable; an aggregate as its pointer. `DirectCall` emits `call sig
@name (...)`, `IndirectCall` evaluates the callee to a pointer variable
and emits `icall sig %f (...)`; `sig` is the function type of the callee
symbol or of the pointer's target. A scalar result lands in a fresh
variable of the result type, canonical because the callee returned its
declared type. An aggregate result needs a destination: if the call is
the value of a `Materialize`, `addrof` of that node's variable; otherwise
`addrof` of a fresh anonymous aggregate variable the builder adds; the
call is emitted with `into %p` and the `Val` is `%p`. A `void` call
yields no value. A called function that the unit does not define is
recorded, and `Lower` emits a `declare` for each.

**Assignment.** `Assign(target, value)`: `a = place(target)`, `v =
value(value)`, `write(a, v)`. The yielded value is what the target now
holds: `v` for a scalar, `canon` of `v` to the bit-field's width for a
bit-field (`f = 300` on a 4-bit field yields 12), the target's pointer
for an aggregate. `CompoundAssign(target, newValue)` and
`PostfixAssign`: `a = place(target)` once (for a `Memory` place its
pointer is evaluated once; for a `Variable` there is nothing to
evaluate); `old = read(a)`, copied into a fresh variable when `a` is a
`Variable` so that the later write does not change it; the `TargetValue`
node inside `newValue` is lowered by looking `target` up in a map from
that node instance to `old`; `n = value(newValue)`; `write(a, n)`; yield
`n` (`canon`ed to a bit-field's width) for `CompoundAssign` and `old` for
`PostfixAssign`.

**Conditional.** `Cond(c, t, e)` with a scalar type:

```
  %c = value(c); condbr %c, .then, .else
.then: %t = value(t); mov %r, %t; br .done
.else: %e = value(e); mov %r, %e; br .done
.done:
```

With an aggregate type, `%r` is `addrof` of a fresh aggregate variable
and each arm ends with `copy %r, %arm`. With `void`, no variable.

**Comma.** `value(left)` discarded, then `value(right)`.

**Temporaries.** `Materialize(value, symbol)`: `%p = addrof %symbol`; if
`value` is a call, lower it with `into %p`; otherwise `copy %p,
value(value)`. The `Val` is `%p`. `CompoundLit(symbol, init)`: `%p =
addrof %symbol`, `initialize(%p, init)` each time the expression is
evaluated (C evaluates the initializer each time), then `%p`. Both
symbols are in `TFunction.locals`, so their variables are declared.

**Initialization** (`initialize(%p, TInit)`, `%p` a pointer to the
object): for an aggregate, `zero %T %p`, then each item in order: `%q = wadd %p,
item.offset`, then `write(Memory(%q, item's type, ...), value(item))`,
which is a `store.N`, a bit-field sequence, or a `copy` for an item that
is itself an aggregate. For a
scalar object (a local `int x = e;` is a `Variable` place, not a
pointer), the single item is a `mov`. The typer guarantees the items are
converted to the subobject's types and ordered so that a later one
overrides an earlier one, so applying them in sequence is correct.

**String literals** are `VarRef`s of the `StringData` symbol and lower
like any global: `addrof @.str.<hash>`.

## Statements

`StmtLower` walks the body with the `Builder`. Every `JumpTarget` maps to
a block, created on first sight; a loop's target maps to two, `break`
and `continue`, and a switch's to its `break` block.

- **`Block`**: items in order.
- **`ExprStmt`**: `value(expr)`, discarded.
- **`LocalDecl(symbol, init)`**: the variable is declared; a scalar with
  an initializer is `mov %x, value(item)`; an aggregate with one is
  `initialize(addrof %x, init)`; without an initializer, nothing.
- **`If`**: `condbr` on the condition (already `bool`), `then` block,
  optional `else` block, join block.
- **`While`**: `.cond: condbr c, .body, .break; .body: ... br .cond;
  .break:`; `continue` is `.cond`.
- **`DoWhile`**: `.body: ...; .cond: condbr c, .body, .break`; `continue`
  is `.cond`.
- **`For`**: the init statements, then `.cond` (or straight to the body
  without a condition), `.body`, `.step: step; br .cond`, `.break`;
  `continue` is `.step`.
- **`Switch(value, cases, default, body)`**: `%v = value(value)`; for each
  `CaseRange(low, high)`, in order: `%d = wsub %v, low; %c = ule %d, high
  - low; condbr %c, .case, .next` (one unsigned comparison per range, in
  the value's class); then `switch %v, .default-or-break, [ value ->
  .case ... ]` for the single-valued cases; the body follows, closed
  until its first label; `.break` after it.
- **`Labeled(target, body)`**: `br` to the target's block if the current
  block is open; open the target's block; continue in it.
- **`Goto`, `Break`, `Continue`**: `br` to the mapped block, which closes
  the builder.
- **`Return(value)`**: `ret %v`; an aggregate returns its pointer.
  Without a value, `ret`.

**The end of the body.** If the builder is open after the body: `ret`
for a `void` function, `mov %r, 0; ret %r` for `main` (5.1.2.2.3), and
`trap "end of non-void function"` otherwise.

## Data

A `TUnit.Global` that is a definition becomes `global @name : type align
N [readonly] [= { items }]` where the items are the `TInit` items as
they are: `ConstEval` has already folded each to an `IntConst`,
`FloatConst`, `AddrConst` or `NullptrConst`, and the TAC initializer is
the same list of `offset : typed constant`, with `addr @name + offset`
for an `AddrConst` and `offset : bit/width : value` for a bit-field
item. Nothing is walked, merged or packed; the consumer applies the
items in order. A `StringData` is `global internal readonly @.str.<hash>
: [N x i8] = { 0 : bytes "..." }` for a `char` string, and one item per
code unit for wider ones. A tentative definition or a definition without
an initializer has no `= { }`. A `Global` that is not a definition is
`declare @name : type`, as is every function called but not defined.
`readonly` is set for `const`-qualified objects and string literals.
Static locals are in `TUnit.globals` already, so only their name is
special.

## Names

`Names.of(Symbol)` gives the TAC name, computed once per unit:

- a function or an object with external or internal linkage: its C name;
- a static local: `function.name`, with `.2`, `.3` appended for a second
  and third static of the same name in the same function, in declaration
  order;
- an anonymous static object (a compound literal at file scope): `unit`
  or the function's name, then `.lit.N` in order of appearance;
- a string literal: `.str.` and a 64-bit hash of its units in hex; the
  loader shares by name, and a collision would be a wrong program, so the
  hash is a real one (SipHash or the low bits of SHA-256), not
  `hashCode`;
- variables: the symbol's C name, with `.N` for shadowed names, `.tmpN`
  for the typer's anonymous temporaries, and `%tN` for the ones `Lower`
  introduces.

## Functions

`Lower.function(TFunction f)`: `define` with the function's name, linkage
and signature; the parameters as typed variables in the signature; one
declared variable per `locals` entry with its TAC type, `volatile` when
its C type is; then the body; then the end-of-body rule. Variables that
`Lower` introduces (results, `Cond` and call temporaries) are declared as
they are allocated. Nothing is stored at entry.

## Status

Steps 1 to 28 are implemented (September 2026): `Lower.lower(TUnit,
Types)` produces the `Module` that `Main` prints, `LowerTest` holds the
per-step tests, `TacCorpusTest` compares every typed-corpus program with
a `.tac` golden on its target (regenerated with `-Dtac.update=true`),
checks the invariants, and asserts every instruction kind and operation
appears. What the implementation settled differently from the text:

- The end of `main` is `ret 0` rather than a `mov` and a `ret`, since
  `ret` takes an immediate.
- A static local is named `name.static`, with `.2`, `.3` for later ones
  of the same name in the unit, because the typer's `TUnit` does not say
  which function a static local belongs to. A file-scope compound
  literal is `.lit.N`; the typer's temporaries are `tmpN` and `litN`.
- The temporary object for a discarded aggregate call result is
  `call.N`, and for an aggregate conditional `cond.N`; a conditional
  under a `Materialize` is copied twice, once into `cond.N` and once
  into the temporary, which a later step may fold.
- `ToBool` of a comparison, a logical operator or `!` is the value
  itself, since those already yield 0 or 1.
- An assignment used as a statement does not compute the value it
  would yield; `ExprLower.effect` passes that down for the top node.
- Temporaries are declared only if an emitted instruction uses them,
  so dropped dead code leaves no declaration behind.
- `Names` hashes string units with SHA-256 truncated to 64 bits.
- The typed corpus gained `21-lowering-extras.c` so that `trap` and the
  unsigned and floating operations appear; the lowering goldens live in
  `src/test/resources/tac/` next to nothing else, named after the typed
  corpus programs.

## Changes to existing code

- **`TInit.Item`** carries an offset and a value. For a bit-field member it
  must also carry the bit placement, since two bit-fields in one storage
  unit share the byte offset and a consumer could not tell them apart.
  `Initializers` has the `Layout.Member` at hand when it produces the
  item; the item gains `Optional<BitField>` and `TypedPrinter` prints it
  as `offset:bit/width`. Today the typer produces an item at the byte
  offset with the declared type, which silently overwrites the whole
  storage unit; this is a bug in the typer that the change fixes.
- **`TUnit`** lists the functions the unit defines but not those it only
  declares; `Lower` collects the called-but-undefined ones from the
  tree. If that proves awkward, `TUnit` gains a `declarations` list from
  the typer, which knows them.

## Testing

Each step below is written **test first**: the test states the exact TAC
text expected for a small input, fails, and the step makes it pass.
Three helpers in `LowerTest` do the work, modelled on the typer's tests:

- `expr(decls, source)`: lowers the expression `source` over the
  declarations `decls` as the body of `void $t(void) { (void)(source); }`
  and returns the instructions of the entry block as text, without the
  end-of-body boilerplate;
- `stmt(decls, source)`: the same for a statement, returning every block;
- `unit(source)`: the whole module as `TacWriter` prints it.

Every test asserts `TacInvariants` on the module it built, so an
invariant violation fails the test that introduced it. Both targets are
available as `exprOn(types, ...)`. The corpus (`src/test/resources/tac/*.c`
with `.tac` goldens, regenerated with `-Dtac.update=true`) grows one
file per step, and the corpus test also asserts the invariants, the
round trip through `TacWriter` and `TacReader` once the reader exists,
and at the end that every typed-tree node kind and every `Instr` kind
appears.

## Steps

Each step is one commit: its tests written first and failing, then the
code, then the suite green with `Main` still running. Steps 1 and 2 of
`tac-plan.md` (the model, the writer and the invariants) come first.

**Harness and skeleton**
1. [x] **`LowerTest` helpers, `Builder`, `Names`, `Lower` skeleton.** Test:
   `unit("void f(void) {}")` prints `define @f() -> void { .entry: ret }`;
   a function with parameters and locals declares them with their types;
   a shadowed local gets `.2`; a `volatile` local is marked.
2. [x] **End-of-body rule.** Tests: `void`, `main`, and a non-void function
   whose body falls through, each ending as the rule says.

**Scalars**
3. [x] **Constants and variables.** Tests: `expr` of an `int`, a `long`, a
   `double`, a `float`, `nullptr`, and `(void *)0`, each one `mov` in the
   right type; `x` for a local is the variable itself with no
   instruction; `x = 1` is one `mov`.
4. [x] **Globals, loads and stores.** Tests: `g` for a global of each scalar
   type prints `addrof` and the right `load.sN`/`load.uN`/`load.fN`; `g =
   1` prints `addrof` and `store.N`; a `volatile` global marks both; `*p`
   and `*p = 1` through a pointer parameter.
5. [x] **Integer conversions.** Tests, one per row and per case of the
   `IntToInt` rule: `(unsigned short) s`, `(unsigned short) sc`, `(short)
   uc`, `(char) i`, `(unsigned char) i`, `(long) i`, `(long) u`, `(unsigned
   long) i`, `(int) l`, `(char) l`, on both targets.
6. [x] **Floating conversions and `ToBool`.** Tests: `(double) i`, `(double)
   u`, `(float) l`, `(int) d`, `(unsigned char) d`, `(float) d`, `(double)
   f`, `!!i`, `!!p`, `!!d`.
7. [x] **Pointer conversions.** Tests: `(long) p`, `(int) p`, `(void *) 5`,
   `(char *) l`, `(int *) vp`.
8. [x] **Arithmetic.** Tests: `a + b` for `int`, `unsigned`, `long`,
   `double`; `a / b` and `a % b` signed and unsigned; the bit operators;
   `_BitInt(7)` addition with its `canon`.
9. [x] **Shifts.** Tests: `i << n`, `i >> n`, `u >> n`, `l >> n` with the
   `mov` of the amount into an `l`.
10. [x] **Comparisons.** Tests: each operator on `int`, `unsigned`, pointers
    and `double`, including the swap for `>` and `>=` with side-effecting
    operands evaluated left to right.
11. [x] **Unary and logical.** Tests: `-i`, `-u`, `-d`, `~i`, `!i`; `a && b`
    and `a || b` print the two block shapes.

**Objects**
12. [x] **Pointers and members.** Tests: `p[i]`, `p + 3`, `p - q`, `s.m` on a
    local struct (`addrof`, `wadd`, `load.s32`), `p->m`, `s.in.y` with one
    `wadd` of the summed offset, `&s.m`, `&a[2]`, `&x` on a scalar local.
13. [x] **Bit-fields.** Tests: load of an unsigned and a signed field, store
    to each, on both targets.
14. [x] **Assignments.** Tests: `x = y` yields the stored value; `bf = 300`
    yields 12; `x += 1` and `x++` on a local (no memory), `p->m += 1`
    (pointer evaluated once), `++x`, `p++`, `a[k++] += 1` with
    `TargetValue` used once; `bf += 1`.
15. [x] **Aggregates.** Tests: `s = t` is a `copy` yielding `s`'s pointer;
    `(s = t).m` loads from `s`; `sizeof` is already a constant.

**Calls and conditionals**
16. [x] **Direct and indirect calls.** Tests: `f(1, 2.0)`, `fp(3)`, a
    variadic call with promoted extras, a `void` call as a statement, a
    call of an undeclared-in-unit function producing a `declare`.
17. [x] **Aggregate calls.** Tests: `g(s)` passes the pointer, `s = mk()`
    lands `into` the `Materialize` variable then copies, `mk().x` uses
    it, `mk();` as a statement uses a `.callN` variable.
18. [x] **Conditional, comma, void.** Tests: `c ? 1 : 2`, `c ? s : t`, `c ?
    f() : g()` with `void`, `(a, b)`.
19. [x] **Compound literals and local initializers.** Tests: `(struct P){1,
    2}.x`, `int v[3] = {1, 2}` as `zero` then two stores, `struct P p =
    {.y = 2}`, `char s[] = "ab"`, `struct Out o = {in, "z"}` with the
    `copy`, `int x = e` as one `mov`, an initializer in a loop body
    re-run each iteration.

**Statements**
20. [x] **`if`.** Tests: with and without `else`, nested, an empty branch.
21. [x] **Loops.** Tests: `while`, `do`, `for` with and without each clause,
    `break` and `continue` in each, a `for` with a declaration.
22. [x] **Labels and `goto`.** Tests: forward and backward `goto`, a label
    after a `return` opening a block, dead code after `return` dropped,
    a `goto` into a loop body.
23. [x] **`switch`.** Tests: cases and default, fallthrough, `break`, a
    statement before the first case dropped, a `case 1 ... 5` range,
    nested switches, a switch on a `long`.
24. [x] **`return`.** Tests: scalar, aggregate by pointer, `return` inside a
    loop, `main` without `return`.

**Data and the unit**
25. [x] **`TInit.Item` bit placement** in the typer, test first in
    `TyperTest` and the typed corpus (`08-records.c` gains a bit-field
    initializer).
26. [x] **Globals.** Tests: scalar, array, struct with designators, a union,
    a pointer to a global with an offset, a bit-field item, a tentative
    definition, an `extern`, `const` as `readonly`.
27. [x] **Strings and statics.** Tests: a string literal as `@.str.<hash>`
    shared by two uses, a `wchar_t` string, a static local named
    `f.count`, two statics of one name, a file-scope compound literal.
28. [x] **`Main` prints the TAC** of its program; the corpus gains it; both
    targets run the whole corpus; the coverage checks pass.

### Deferred
- omitting `zero` when an initializer covers every byte
- variable-length arrays (blocked on the typer)
- `_BitInt` above 64 bits

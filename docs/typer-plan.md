# Typer: architecture and plan

The typing pass (C2y 6.3, 6.5 - 6.7, 6.9; draft N3886 as in
`parser-plan.md`). It runs after `Resolver` and before lowering, and turns the
syntactic AST plus `Bindings` into a **typed tree** that lowering can consume
without ever looking at `org.jbm.cc.ast` or `sema.Bindings` again.

## Decision: type the AST into a new tree, not the TAC

Alternatives considered: (a) annotate the TAC with types during or after
lowering; (b) keep the AST and record types in an identity-keyed side table
like `Bindings`; (c) build a new tree whose nodes carry their type. We chose
(c) because:

1. [x] **Lowering cannot start without types.** `a + b` is an integer add, a float
   add or a pointer add scaled by the element size; `a < b` is a signed or
   unsigned compare; `s.m` needs an offset; `f(c)` with `char c` needs a
   widening. Typing on the TAC is circular.
2. [x] **C's typing rules are defined on syntactic context** (6.3.3.1: array decay
   is suppressed under `&`, `sizeof`, `_Countof`; `sizeof` operands are
   unevaluated; `_Generic` selects on the unconverted type; assignment needs a
   *modifiable lvalue*). The TAC has flattened exactly the structure those
   rules inspect.
3. [x] **Constant expressions precede code**: array sizes, enumerators, case
   labels, bit-field widths and static initializers need typed evaluation
   before any function is lowered.
4. [x] **Side tables (b) leave the implicit conversions implicit.** Lowering would
   have to re-derive decay, promotions and usual arithmetic conversions, which
   is a second, hidden typer. A new tree makes every conversion an explicit
   node once, and the tree can later be rewritten freely because it has no
   identity-keyed tables hanging off it (see `parser-plan.md`, Phase 6, for
   why `Bindings` must be identity-keyed and why rewriters must not run
   between `Resolver` and its consumer).

This is the shape of Clang (Sema types the AST; CodeGen consumes it; LLVM IR
records what the front end decided) and GCC (typed GENERIC before GIMPLE).

## Decision: sealed hierarchies instead of kind enums

Wherever a node would carry a discriminator (`ValueKind`, `CastKind`, an
operator enum, an evaluation-context enum) the typed tree uses a sealed type
instead: one record per conversion, one record per operator, value category
as a sub-interface. The reasons:

1. [x] **The compiler enforces the invariants.** `AddrOf(Lvalue)`,
   `Assign(Lvalue target, Rvalue value)`, `Deref(Rvalue pointer)`,
   `Add(Rvalue, Rvalue)` cannot be built wrong. With a kind enum the same
   rules are runtime checks that every consumer repeats.
2. [x] **The constraints of 6.5 become failed narrowings.** "not an lvalue",
   "operands of `%` must be integers" are the typer failing to obtain the
   parameter type a node's constructor demands, with the token for the
   diagnostic.
3. [x] **Adding a node kind is a compile error until every visitor handles it**,
   the same property `ast.Visitor` already gives the AST. An enum case can be
   forgotten silently.
4. [x] **No extra memory.** A record's class pointer is the discriminator; an
   enum field would be one more reference per node.

Java 17 has no pattern-matching `switch`, so consumers dispatch through a
`TVisitor<R>` with `accept` on every node (double dispatch, O(1) per node,
like `ast.Visitor`), or `instanceof` chains on the sealed families where a
pass only cares about one family.

## Pipeline

```
tokens ──Parser──> AST ──Desugar──> AST ──Resolver──> Bindings ──Typer──> typed tree ──Lower──> TAC (Module)
                                                       (side tables,        (self-contained:            ├──Codegen──> .s
                                                        AST-identity keyed)  symbols, types, casts)     └──VM (runs it)
```

`Typer` is the **only** consumer of `Bindings`. Its output references
`Symbol`, `TagSymbol` and `CType` objects directly; nothing downstream needs
the AST, which becomes garbage once typing finishes.

`Lower` is the only consumer of the typed tree, and the TAC it produces is
a public contract with two consumers: the x86-64 code generator, and the
VM of `cshell-plan.md`, a separate project that executes it. The TAC is
layout-explicit (the numbers the typer fixed for the `Target` appear as
constants) and ABI-neutral (a `call` carries typed arguments; each
consumer applies its own convention). The shell recompiles everything
typed so far on every line through the same whole-unit entry, so no pass
needs an incremental form.

## Packages and classes

```
org.jbm.cc.types              semantic types, shared by sema, tast and lowering
  CType                       sealed: Void, Int, Float, BitInt, Pointer, Array, Function, Record
  Quals                       const/volatile/restrict/_Atomic, on every CType
  Types                       the interner + the arithmetic of 6.3 (promotions, usual arithmetic conversions,
                              compatibility 6.2.7, composite type), parameterized by a Target
  Target                      interface: what the standard leaves to the implementation (widths, alignments,
                              char signedness, size_t/ptrdiff_t/wchar_t, bit-field packing policy)
  Layout                      size, alignment, member offsets, bit-field placement, per TagSymbol, from a Target
org.jbm.cc.arch               concrete Targets, one per ABI
  X86_64SysV                  the first Target; the only file that knows a real ABI's numbers
org.jbm.cc.tast               the typed tree (sealed interfaces + records), lowering's input
  TExpr                       sealed into Lvalue, Rvalue, FunctionDesignator; every node has type() and token()
  TStmt                       statements; jumps hold JumpTarget references
  TInit                       flat initializer: (offset, value) items in ascending offset order
  TFunction, TUnit            a function with its parameters, locals and body; the unit with globals in order
  JumpTarget                  identity object for a label, loop or switch; goto/break/continue/case point at it
  TVisitor                    one visitor over every typed node kind, with accept on each node
  TypedPrinter                S-expression dump with `:type` suffixes; the test oracle
org.jbm.cc.sema
  TypeBuilder                 ast.Type + Bindings -> CType (typedef/typeof/tag resolution, parameter adjustment)
  Literals                    6.4.5 decoding: integer suffixes/bases, floats, character constants, string bytes
  ConstEval                   6.6 over TExpr: integer, floating and address constants; folds to Constant nodes
  Typer                       the pass: Visitor<TExpr> for Expr, plus statement and declaration visitors
  Initializers                6.7.11: designators, brace elision, string-into-array, size completion -> TInit
```

## Semantic types (`CType`)

- **`CType` is the value, `Types` is the factory.** A `CType` is one of the
  sealed records and answers only questions about its own structure
  (`isArithmetic()`, `isPointer()`, `quals()`, a pointer's `target()`). It
  never creates another type and never knows a width. `Types` is one object
  per compilation that owns the intern tables and the `Target`; every
  construction (`pointer(t)`, `array(t, n)`, `qualified(t, q)`) and every
  rule that needs a machine fact (`width(t)`, `promote(t)`,
  `usualArithmetic(a, b)`, `compatible(a, b)`, `composite(a, b)`, `sizeT()`)
  goes through it. The rule: returns a new type or asks the target, then it
  is on `Types`; inspects one type, then it is on `CType`. This is Clang's
  `Type` / `ASTContext` split.
- **Canonical and interned.** A `CType` is immutable and hash-consed through
  `Types`: two structurally equal types are the same object, so
  compatibility checks on identical types are pointer comparisons and every
  `int` in the program is one object. Typedefs, `typeof` and tag references do
  not exist in `CType`; a typedef is resolved to what it names at the point of
  its declaration.
- **Variants are the sealed hierarchy**: `Void`, `Int` (rank `bool`,
  `char`, `short`, `int`, `long`, `long long`, plus signedness), `Float`
  (rank `float`, `double`, `long double`), `BitInt` (explicit width,
  signedness), `Pointer`, `Array`, `Function`, `Record`. Predicates such as `isArithmetic()`, `isScalar()`,
  `isInteger()` are methods on `CType` with per-variant overrides, not tests
  of a kind field.
- **Records by identity.** `CType.Record` wraps a `TagSymbol`; two struct
  types are compatible iff they are the same tag (6.2.7p1 within one unit).
  This keeps interning cheap: a record's hash is its tag's identity hash, not
  a walk over its members.
- **Enums are integers.** `enum E` becomes its underlying integer type; the
  enumeration constants are `int` (or the fixed underlying type) constants
  decided when the enum is typed. No `Enum` variant is needed for lowering;
  the tag stays on `TagSymbol` for diagnostics.
- **Qualifiers on every variant**, dropped by lvalue conversion (6.3.3.1p2)
  and ignored for arithmetic. `restrict` and `_Atomic` are carried but not
  acted on.
- **Arrays**: element type plus `OptionalLong size`; absent means incomplete.
  Variable-length arrays are out of scope for this phase (see Deferred).
- **Functions**: return type, adjusted parameter types (arrays and functions
  become pointers, 6.7.7.4p7-8), variadic flag, and whether a prototype was
  given. `()` in C2y means `(void)`, so every function type has a prototype.
- **Architecture-agnostic.** A `CType` records what the C standard defines
  (rank, signedness, structure) and nothing the standard leaves to the
  implementation. `int` is the same object on every target. Everything
  implementation-defined is a question to the `Target` (5.2.5.3, 6.2.6.2,
  6.7.3.2p13): the width and alignment of each rank, whether plain `char` is
  signed, which ranks `size_t`, `ptrdiff_t`, `wchar_t` and `intptr_t` are,
  and how bit-fields pack. `Types` and `Layout` take a `Target` at
  construction and ask it; nothing else in sema or the tree touches a
  number that could differ between machines. The usual arithmetic
  conversions need this too: whether `long + unsigned int` is `long` or
  `unsigned long` depends on whether `long` can represent every
  `unsigned int` (6.3.2.2p1), which is a width question. The first
  implementation is `X86_64SysV` (LP64, `char` signed, natural alignment,
  bit-fields in their declared type's storage unit); an ILP32 or an LLP64
  target is another record with different numbers and no other change.

### `CType` versus `ast.Type`

They answer different questions: `ast.Type` is what the program *wrote*,
`CType` is what it *means*. The parser produces the first; the typer
consumes it and produces the second, and lowering only ever sees the second.

| | `ast.Type` (syntactic) | `CType` (semantic) |
|---|---|---|
| Produced by | `Parser`, from specifiers and declarators | `TypeBuilder` / `Types`, from `ast.Type` and `Bindings` |
| Typedef names | `TypedefName(name, aliased)`, kept as spelled | resolved; the typedef is gone |
| `typeof(expr)` | unresolved, holds the `Expr` | the operand's `CType` |
| Struct / enum | the specifier with its tag token and member declarations; the same tag spelled twice is two nodes | `Record(TagSymbol)`: one object per tag; members via `Layout` |
| Enum | `Enum` specifier | its underlying integer type |
| Array size, `_BitInt` width | an `Expr` | a number (`OptionalLong` / `int`) |
| Function parameters | declarators, names, attributes, `int a[]` as written | adjusted types only (`int *`), no names |
| `int` | a new `Basic` record per spelling, with its `Token` | one interned object for the whole unit |
| Equality | structural (records), but position-bearing | identity; compatibility (6.2.7) is a `Types` method |
| Carries | tokens, attributes, storage classes on parameters, `[*]`, `static` in `[]` | rank, signedness, qualifiers, structure |
| Knows the target | no | no; asks the `Target` when it needs a width |
| Lifetime | until typing ends | until lowering ends |

Many `ast.Type`s map to one `CType` (every `int`, every `T` for
`typedef int T`, every `typeof(x)` with `int x`), which is why the syntactic
type cannot be the thing lowering keys on, and why `Symbol` keeps both: the
first for "previously declared as ..." diagnostics, the second for
everything else.

Where types live after the pass:

| Holder | Field | Set by | Read by |
|---|---|---|---|
| `Symbol` | `declaredType` (syntactic, first declaration) | Resolver | diagnostics only |
| `Symbol` | `type` (semantic, composite of all declarations) | Typer | Typer (uses), lowering (slots, globals, signatures) |
| `TagSymbol` | `type` (`CType.Record`) and `layout` | Typer, lazily | Typer (members, `sizeof`), lowering |
| `TExpr` | `type` (record component) | Typer, at construction | lowering |

`Symbol.type()` throws `IllegalStateException` when unset, like
`Bindings.require`: reading it before the typer is a bug, not a null.

## The typed tree (`tast`)

### Value categories as types

```
TExpr                     type(), token(), accept(TVisitor)
├── Lvalue                designates an object (6.3.3.1p1)
├── FunctionDesignator    designates a function
└── Rvalue                a value
    ├── Constant          IntConst, FloatConst, AddrConst
    ├── Conversion        one record per 6.3 conversion
    ├── Arithmetic        one record per operator; both operands of the node's own type
    ├── Comparison        one record per operator; result int
    └── (the rest)        Logical ops, unary ops, pointer ops, calls, assignments, ...
```

Every node kind implements exactly one category, so the category is known
statically at every use site and there is no `kind()` accessor. Where C
makes the category depend on the operand (`.` on an lvalue struct is an
lvalue, on a function result it is not) the tree removes the dependency
instead: a non-lvalue struct value is first stored in a temporary by a
`Materialize(Rvalue)` node, which is an `Lvalue`, so `Member` always has an
`Lvalue` base. Lowering has to allocate that temporary anyway. The one
thing lost is that `f().m` is not an lvalue in C (6.5.3.4p3): the typer
restores that by rejecting a store into, or the address of, a `Member`
chain rooted in a `Materialize`; reads are fine.

### Node kinds

```
Lvalue
  VarRef(symbol)                    symbol.type; string literals are VarRefs to anonymous static symbols
  Deref(Rvalue pointer)             a[i] becomes Deref(PtrAdd(a, i)); p->m becomes Member(Deref(p), m)
  Member(Lvalue base, member)       member holds name, type, offset, bit-field (offset, width) if any
  Materialize(Rvalue value)         anonymous temporary holding a non-lvalue struct/union value
  CompoundLit(symbol, TInit)        evaluates the initializer into the anonymous object at this point

FunctionDesignator
  FuncRef(symbol)
  FuncDeref(Rvalue pointer)         *fp on a pointer to function (6.5.4.2p4); FunctionDecay(FuncDeref(p)) is
                                    the identity on p and the typer collapses it, so (*f)(x) and f(x) type alike

Rvalue
  Constant
    IntConst(long, type)            also enumerators, sizeof/alignof/_Countof results, true/false
    FloatConst(double, type)
    AddrConst(symbol, offset, type) address constant (6.6p9); produced by ConstEval only
  Conversion(operand)
    LvalueToRvalue(Lvalue)          drops qualifiers
    ArrayDecay(Lvalue)              array to pointer to first element
    FunctionDecay(FunctionDesignator)
    IntToInt, IntToFloat, FloatToInt, FloatToFloat, PtrToInt, IntToPtr, PtrToPtr, ToBool, ToVoid, NullToPtr
  Arithmetic(Rvalue l, Rvalue r)    Add Sub Mul Div Rem BitAnd BitOr BitXor Shl Shr; operands converted to `type`
                                    (Shl/Shr: right operand promoted only, as 6.5.7 says)
  Comparison(Rvalue l, Rvalue r)    Eq Ne Lt Le Gt Ge; operands converted to a common type; result int
  Logical(Rvalue l, Rvalue r)       And Or; operands converted ToBool; result int
  Neg(Rvalue), BitNot(Rvalue), Not(Rvalue)
  PtrAdd(Rvalue ptr, Rvalue index)  index converted to ptrdiff_t; element type is type.target(); Sub is a negated index
  PtrDiff(Rvalue l, Rvalue r)       ptrdiff_t
  AddrOf(Lvalue | FunctionDesignator)      &f on a function is FunctionDecay(FuncRef f); AddrOf takes lvalues only
  Call                              sealed: DirectCall(Symbol callee, args) names the function, IndirectCall(Rvalue callee,
                                    args) computes a pointer-to-function; args converted as if by assignment
  Assign(Lvalue target, Rvalue value)      value converted to the target's unqualified type; yields the new value
  CompoundAssign(Lvalue target, Rvalue newValue)   yields the new value
  PostfixAssign(Lvalue target, Rvalue newValue)    yields the old value (i++, i--)
  TargetValue(Lvalue target)        inside newValue only; target is the *same node instance* as the enclosing
                                    assignment's target: the value of that one evaluation, not a repeat of it
  Cond(Rvalue c, Rvalue t, Rvalue e)
  Comma(Rvalue l, Rvalue r)
```

`CompoundAssign` and `PostfixAssign` carry no operator: `newValue` is an
ordinary typed subtree over a `TargetValue` placeholder, so the computation
type and every conversion are explicit and there is nothing for lowering to
re-derive. `TargetValue` holds a reference to the target `Lvalue` node, the
same instance the enclosing assignment holds, which is what expresses
6.5.17.3p3's "the lvalue is evaluated only once": lowering computes the
target's address when it meets the assignment, records it under that node's
identity, and a `TargetValue` is a load through that recorded address.
Nested assignments need no scoping rule, since each `TargetValue` names its
own target. `char c; c += 1.5` is

```
(compound-assign c:char (float-to-int:char (add:double (int-to-float:double (target:char c)) 1.5:double)))
```

and `p++` on an `int *` is `(postfix-assign p (ptradd:int* (target:int* p) 1:long))`.
Lowering evaluates the target's address once, loads it for `TargetValue`,
computes, stores, and yields whichever value the node says.

Gone from the AST: `Identifier`, `Literal`, `StringLiteral`, `Generic`
(replaced by its chosen arm), `Index`, `TypeOperator` and unary
`sizeof`/`_Countof` (folded to `IntConst`), `StaticAssertion` (checked and
dropped), explicit `Cast` (it produces the same `Conversion` nodes as an
implicit one; nothing downstream distinguishes them), prefix increment
(already desugared), unary `+` (only a promotion), `Postfix` in void context
(already desugared), `Assign` with a compound operator (becomes
`CompoundAssign`).

### Invariants

- Every node carries its `CType` and the source `Token` it came from. Tokens
  are shared with the AST, never copied; nodes hold no reference to any AST
  node, so the AST is not pinned in memory.
- Every implicit conversion is a `Conversion` node, inserted only when the
  type actually changes.
- `Arithmetic` operands have exactly the node's type; `Comparison` operands
  have one common type. Lowering never re-derives a conversion.
- Anything with storage has a `Symbol`; anything that is only a value has
  just a type. String literals become anonymous static `Symbol.Variable`s
  with their bytes as initializer; compound literals and materialized
  temporaries have anonymous automatic symbols listed in the function's
  locals.
- No side tables: references are fields (`Symbol`, `TagSymbol`, `Member`,
  `JumpTarget`). The tree can be rewritten by later passes without any
  remapping.
- One shared node, by design: a `TargetValue` and its enclosing
  `CompoundAssign`/`PostfixAssign` reference the same `Lvalue` instance. A
  typed-tree rewriter handles it the way `AstRewriter` handles the parser's
  shared `Type` nodes: an identity memo, filled when the target is rewritten
  (record components are rewritten in order, so the target comes first) and
  read when the `TargetValue` is reached. `TypedPrinter` prints the
  placeholder as `(target x)` and a test asserts the identity.

### Statements

`Block(items)`, `ExprStmt`, `If`, `While`, `DoWhile`, `For`, `Switch(cond,
cases, body)`, `Labeled(target, body)`, `Goto(target)`, `Break(target)`,
`Continue(target)`, `Return(value)`, `LocalDecl(symbol, init)`. Declarations
inside blocks become `LocalDecl` statements at their position, because
initialization happens at that point in control flow. `Switch.cases` holds
`Case(value, target)` and `CaseRange(low, high, target)` records (a sealed
pair, not a flag) that lowering builds a jump table from; the `Labeled`
nodes in the body are where they land.

## The Typer

One `Typer` instance holds three visitors that share state: `Bindings`, the
`Types` interner, and the current function (return type, its `Map<Stmt,
JumpTarget>`, its locals list, and an unevaluated-operand depth counter).

**Single pass in declaration order.** C requires declaration before use, so
when an identifier is typed its `Symbol.type` is already set: a function's
prototype precedes its calls, a struct is complete before an object of it is
declared. No fixpoint and no second walk. The two things decided at the end
are tentative definitions with incomplete array type, which get size 1
(6.9.2p5), and the unit-wide check that every used function is declared.

**Expressions** are typed bottom-up; each `visit` returns a new node built
from typed children, through helpers whose Java signatures are the rules of
6.3:

- `Rvalue rvalue(TExpr x)`: lvalue conversion, array decay, function decay,
  by narrowing on the category. Not applied under `&`, `sizeof`, `_Countof`,
  `alignof`, on the left of `.`, on assignment targets, or on `++`/`--`
  operands.
- `Lvalue lvalue(TExpr x, Token at)` and `Lvalue modifiable(TExpr x, Token
  at)`: the narrowing that fails with "not an lvalue" / "assignment to
  const"; `Materialize` is inserted here for struct rvalues under `.`.
- `Rvalue promote(Rvalue x)`: integer promotions (6.3.2.1), bit-field and
  `_BitInt` rules.
- `CType usualArithmetic(CType a, CType b)`: the common real type (6.3.2.2);
  pure type arithmetic, no nodes.
- `Rvalue convert(Rvalue x, CType to)`: returns `x` when the type is already
  `to`, otherwise the one `Conversion` record the pair of types calls for.
- `Rvalue assignConvert(Rvalue x, CType to, Token at)`: the constraints of
  6.5.17.1 (arithmetic, compatible pointers, `void *`, null pointer
  constants, records of the same tag), used by `=`, arguments, `return`, and
  initializers.

**No evaluation-context enum.** A `sizeof` operand is typed with the
unevaluated depth counter incremented, which only suppresses the
side effects of typing (registering compound-literal temporaries as locals).
"Must be a constant expression" is not a typing mode: the site types the
operand normally and then calls `ConstEval.require(expr, token)`, which folds
or fails.

**Statements** check conditions are scalar (and insert `ToBool`), convert
`return` values as if by assignment, promote the `switch` controlling
expression and convert every `case` to that type, reject duplicate cases,
and turn every loop/switch/label into a `JumpTarget`. Jumps look the old AST
target up through `Bindings` and then through the per-function
`Map<Stmt, JumpTarget>` (`computeIfAbsent`, so forward gotos work).

**Declarations** run `TypeBuilder` on each declarator, compose with the
symbol's existing type (6.2.7p3), check completeness where an object is
defined, complete array sizes from initializers, infer `auto`, and produce
`TInit`s. Function definitions open a new per-function state, type the body,
and emit a `TFunction` with the ordered list of locals (every block-scope
`Symbol.Variable` and `Symbol.Parameter`, plus anonymous compound-literal and
materialized temporaries) so lowering can allocate a frame in one pass.

**Diagnostics** are `SemaException(message, token)` as today, fail-fast.

## Constant evaluation (`ConstEval`)

Runs on `TExpr`, not on the AST, because folding needs the result type
(wraparound width, signedness, float to int truncation). `fold(Rvalue)`
returns `Optional<Constant>`; `require(Rvalue, Token)` returns the
`Constant` or throws. Address constants are `&object + integer`, `array +
integer`, `&s.m`, string literals and function designators (6.6p9). Integer
values are `long` plus their `CType`; only `_BitInt` wider than 64 needs
`BigInteger`, so the common path never boxes. `ConstEval` is a `TVisitor`
over the `Constant`, `Conversion`, `Arithmetic`, `Comparison`, `Logical`,
unary and `AddrOf`/`Member`/`Deref` families; every other node is not a
constant.

Used by: array sizes, `_BitInt` widths, enumerator values, bit-field widths,
`case` labels, `static_assert`, `sizeof`/`alignof`/`_Countof`, `alignas`,
static-storage initializers (every item must fold or it is an error), and
null-pointer-constant detection in `assignConvert`.

## Layout

`Layout.of(TagSymbol)` computes size, alignment and member placement on
first request and caches it on the tag. The algorithm is the standard's
(6.7.3.2: members in declaration order at increasing addresses, unions
sized to the widest member, trailing padding to the struct's alignment) and
every number in it comes from the `Target`: the size and alignment of each
scalar rank, and the bit-field policy (which storage unit a bit-field
occupies, whether it may straddle one, how a zero-width bit-field pads).
`X86_64SysV` answers natural alignment and "declared type's storage unit,
no straddling"; another ABI answers differently without touching `Layout`.
Flexible array members contribute nothing to the size. Anonymous
struct/union members are flattened into the enclosing member map with
composed offsets (6.7.3.2p15), so `s.x` finds a member of an anonymous inner
union in one lookup.

## Initializers

`Initializers.normalize(init, type)` walks the braced initializer with a
cursor over the object's layout (current subobject, next index/member),
applying designators, brace elision and the string-literal-into-char-array
rule, and emits a `TInit`: items `(offset, member?, Rvalue)` in ascending
offset order, each value already converted as if by assignment to the
subobject's type. Unmentioned subobjects are not emitted; lowering zero-fills
the object first (static: `.bss`/zero bytes; automatic: a memset when any
item is missing). This keeps the output proportional to the source, not to
the object: `int big[1 << 20] = {0}` produces one item.

## Memory and performance

The pass is designed to be linear in the size of the AST, with the few
places where naive typing goes quadratic handled explicitly.

**Traversal.** Each AST node is visited once and produces at most a constant
number of typed nodes (the node itself plus up to two conversions per
operand position), so the typed tree is at most a small constant factor
larger than the AST's expression nodes, and usually smaller because `Index`,
`Generic`, `sizeof`, static assertions and unary `+` disappear. Recursion
depth equals expression nesting depth, as in the parser.

**Types are interned, so type equality is O(1) and memory is per distinct
type, not per use.** `Types` keeps one `HashMap<Key, CType>` per variant;
derived types (`Pointer`, `Array`, `Function`) hash their components'
identity hashes, so building a type is O(1) after its components exist, and a
record's hash never walks its members. A translation unit with ten thousand
`int` expressions holds one `int` object. Each typed node adds two
references (type, token) over the equivalent AST node; the category and the
operator are the class, which costs nothing per instance.

**Typedefs and `typeof` are resolved once, at the declaration.** The
resolver's `AstWalker` already treats `Type.TypedefName` as a leaf so the
aliased struct body is not re-walked at every use; the typer keeps that
property by storing the resolved `CType` on `Symbol.Typedef` and reading it
at each use in O(1). Without this, `typedef struct {...} T;` followed by a
thousand `T x;` would rebuild the record type a thousand times. This needs a
small `Resolver` change: bind each `Type.TypedefName` to its `Symbol.Typedef`
(a new `bindings.typedefs` map), since the resolver currently does not visit
typedef names at all.

**Members are found by hash lookup.** `Layout` builds a
`LinkedHashMap<String, Member>` per tag once; `s.m` is O(1) instead of a
scan over the member list, and anonymous members are flattened into the same
map so nested lookups are still one probe. Layout is computed lazily on the
first completeness-requiring use and cached on the `TagSymbol`; an
in-progress marker turns a struct containing itself by value into an error
instead of unbounded recursion.

**Enumerator values are memoized on the symbol.** `enum { A, B = A + 1, C =
B + 1, ... }` evaluates each enumerator once and stores the value on
`Symbol.Enumerator`; a reference folds to that value in O(1). Re-evaluating
through the chain at each reference would make a long enum quadratic.

**Constant folding is on demand, bottom-up, and not memoized.** Each
constant-required site folds its own expression once, O(size of the
expression). There is no whole-tree folding pass and no per-node cache;
values that are reused (enumerators, `sizeof` results) are stored where they
are defined.

**Conversions are inserted only when the type changes**, so the common case
of `int` arithmetic between `int`s adds one `LvalueToRvalue` per variable
reference and nothing else.

**Dispatch is O(1) per node** through `TVisitor` double dispatch. The sealed
families have around forty node kinds; an `instanceof` chain over all of
them would be a constant but noticeable factor in every lowering visit, so
whole-tree consumers (lowering, `ConstEval`, `TypedPrinter`) implement
`TVisitor`, and `instanceof` is reserved for narrowing to one family.

**Initializers are output-proportional to the source**, as described above,
and the cursor over the layout advances in O(1) amortized per item;
designators reposition it by one member lookup or one array index.

**Switch case checking** is a `HashSet<Long>` for single values and a sort of
the ranges, O(k log k) in the number of cases.

**Per-function state is reset, not accumulated.** The jump-target map,
locals list and label set are per function, so peak memory during typing is
the AST plus the typed tree of the whole unit plus one function's working
state. The AST itself is released as soon as `Typer.type(unit, bindings)`
returns, because the typed tree holds only `Token`s from it, and `Bindings`
goes with it.

**Integer arithmetic stays in `long`.** `IntConst` holds a `long` and its
type, and `ConstEval` masks and sign-extends by the width the `Target`
reports for the type. `BigInteger` appears only when a target or a `_BitInt`
asks for more than 64 bits; no supported `Target` does for standard ranks.

What this design deliberately does not optimize: `Optional` wrappers for
absent children (the codebase convention; allocation is one small object per
absent child), and string-literal pooling across identical literals (lowering
may merge them; 6.4.5p7 allows it).

## Changes to existing code

- `Resolver`: bind `Type.TypedefName` nodes to their `Symbol.Typedef`
  (`bindings.typedefs`); everything else stays.
- `Symbol`: add `type` (semantic `CType`, throwing accessor when unset),
  `Symbol.Enumerator.value`, and anonymous variables (no name token) for
  string literals, compound literals and materialized temporaries.
  `declaredType` stays for diagnostics.
- `TagSymbol`: add `type` and `layout` caches.
- `Bindings.fileScope` moves conceptually into `TUnit.globals`; after typing
  nothing reads `Bindings`.
- `Main` prints the typed tree after the AST; `PipelineTest` extends to it.

## Testing

Every test runs the full pipeline (lex, expand, phase 7, parse, desugar,
resolve, type) and compares `TypedPrinter` output, so a typing rule is one
line. The printer names each node by its record, so the conversion inserted
is visible in the expectation:

```java
assertEquals("(add:int (rv:int a:int) (int-to-int:int (rv:char b:char)))", expr("int a; char b;", "a + b"));
assertEquals("(ptradd:int* (decay:int* a:int[3]) (int-to-int:long (rv:int i:int)))", expr("int a[3]; int i;", "a + i"));
assertEquals("8:unsigned long", expr("struct S { char c; int i; };", "sizeof(struct S)"));
assertEquals("(member:int (materialize:struct S (icall:struct S (fdecay:struct S(*)(void) f))) i)",
             expr("struct S { int i; } f(void);", "f().i"));
```

Plus a golden-file corpus under `src/test/resources/typed/`: one C program
per family of typed constructs (with macros in play), run end to end by
`TypedCorpusTest` and compared to the `.typed` file beside it, and walked by
`TypedTreeInvariants`, a `TVisitor` that asserts the structural rule of every
node kind (operand types, conversions that change the type, jump targets,
listed locals, constant static initializers, `TargetValue` identity). A test
also checks that every node kind prints somewhere in the corpus.

Plus: `TypesTest` (interning identity, compatibility, composite types),
`LayoutTest` (offsets and sizes against known GCC output), `ConstEvalTest`
(wraparound, unsigned, float to int, address constants), `InitializersTest`
(designators, elision, size completion), and failure tests
(`SemaException` with the right token) for each constraint.

## Status

Steps 1-25 are implemented (September 2026): `Typer.type(unit, bindings)`
produces the `TUnit` that `Main` prints, and the full suite is green. What
the implementation settled differently from the text above:

- The pass is split: `Typer` handles declarations, statements and the unit,
  `ExprTyper` the expressions, sharing `Bindings`, `Types`, `ConstEval` and
  the current function's locals. `TypeBuilder` reaches back into
  expressions (array sizes, `_BitInt` widths, `typeof(expr)`, member
  static assertions) through a small `Hooks` interface.
- `CType.Record` refers to a `Tag` interface that `TagSymbol` implements,
  so `org.jbm.cc.types` does not depend on `sema`.
- `TInit` items are in source order, not ascending offset order: a later
  item overrides an earlier one at the same bytes (6.7.11p20) and lowering
  applies them in sequence. A string literal that initializes an array
  becomes per-element items and is not emitted as a separate object.
- `TUnit.Global` carries `isDefinition`: an object declared `extern` only
  is a reference, one declared at least once without `extern` is a
  tentative definition and gets storage.
- The typed tree prints `(lit ...)` for compound literals, `(materialize
  ...)` for struct temporaries, and `member:bit/width` for bit-fields.
- The resolver gained two rules the typer needed: every declaration of a
  name with linkage denotes one symbol (6.2.2), and a typedef name that
  stands for a function type declares a function.
- `_BitInt` above 64 bits, VLAs, `_Complex`, decimal floating types and
  `constexpr` remain unsupported, as listed under Deferred.

## Steps

Each step is one commit: the test suite is green, `Main` still runs, and the
step adds one thing that the next step needs. Steps inside a group depend on
the previous one; groups D and E are independent of each other once C is
done. Step 13 is the first end-to-end milestone: the program in `Main`
typed and printed.

### A - semantic types (no typer yet)

1. [x] **Resolver binds typedef names.** `visit(Type.TypedefName)` looks the
   name up and records it in a new `bindings.typedefs` map. Test in
   `ResolverTest`: a typedef use resolves to its `Symbol.Typedef`, an inner
   variable that hides a typedef still parses as before.
2. [x] **Scalar `CType`s and the interner.** `Target` with `X86_64SysV`,
   `Quals`, `Void`, `Int` by rank, `Float` by rank, `Pointer`, and `Types`
   with `HashMap`-backed hash-consing. `TypesTest`:
   `types.pointer(types.int()) == types.pointer(types.int())`, qualifiers
   produce distinct objects, `unqualified()` returns the shared one,
   `types.width(long)` differs between `X86_64SysV` and a test-only ILP32
   target.
3. [x] **Promotions and usual arithmetic conversions** as methods on `Types`:
   `promote(CType)`, `usualArithmetic(CType, CType)`, and the predicates
   (`isArithmetic`, `isInteger`, `isScalar`) as per-variant overrides. Tests
   are tables: `char + short` is `int`, `int + unsigned long` is `unsigned
   long`, `float + int` is `float`; and `unsigned int + long` is `long` under
   `X86_64SysV` but `unsigned long` under the ILP32 test target, which is
   the check that no width leaked into the rule.
4. [x] **Array and function types.** `Array` with `OptionalLong` size, `Function`
   with adjusted parameters (6.7.7.4p7-8) and the variadic flag; compatibility
   (6.2.7) and composite type for these variants. Tests: `int[]` composes with
   `int[3]` to `int[3]`, `int (*)[]` is compatible with `int (*)[2]`.
5. [x] **`TypeBuilder` for everything but records and enums**, plus
   `Symbol.type` with its throwing accessor. Sizes stay unresolved (an
   `Array` with a non-literal size fails) until step 15. Test harness
   `declaredTypes(src)` prints every file-scope symbol as `name: type`, so
   `int (*fp)(char)` and `T x` with `typedef int *T` are one-line tests.

### B - the typed tree, expressions

6. [x] **`tast` skeleton and the printer.** `TExpr` with the three categories,
   `TVisitor`, `TypedPrinter`, and only these nodes: `VarRef`, `FuncRef`,
   `IntConst`, `LvalueToRvalue`, `ArrayDecay`, `FunctionDecay`, `IntToInt`,
   `Add`, `Sub`, `Mul`, `Div`. `Typer` handles identifiers, integer literals
   without suffixes, and `+ - * /` over integers. First test:
   `(add:int (rv:int a:int) (int-to-int:int (rv:char b:char)))`.
7. [x] **`Literals`.** Integer constants with bases and suffixes (6.4.5.2),
   character constants, floating constants; `FloatConst` and the four
   int/float conversions. String literals become anonymous static
   `Symbol.Variable`s holding their bytes, referenced by `VarRef`; the unit
   keeps them in declaration order.
8. [x] **The rest of the arithmetic families.** `Rem`, `Shl`/`Shr` (right operand
   promoted alone), `BitAnd`/`BitOr`/`BitXor`, `Neg`/`BitNot`/`Not`, the six
   `Comparison`s, `Logical` with `ToBool`, `Cond`, `Comma`, `ToVoid`. Failure
   tests: `1.5 % 2`, `p << 1`.
9. [x] **Pointers.** `Deref`, `AddrOf`, `PtrAdd`, `PtrDiff`, `Index` rewritten to
   `Deref(PtrAdd)`, `PtrToPtr`/`IntToPtr`/`PtrToInt`/`NullToPtr`, null pointer
   constants and `nullptr`, pointer comparisons, `void *` rules. Tests cover
   `&a[i]`, `p - q`, `p == 0`, `*fp` yielding a function designator.
10. [x] **Assignment.** `Assign` through `assignConvert`, the `lvalue` and
    `modifiable` narrowings with their diagnostics, `CompoundAssign` and
    `PostfixAssign` over `TargetValue`. Tests: the `c += 1.5` tree from the
    node list, `p++`, `a[k++] += 1` with the identity of `TargetValue.target`
    asserted, `const int` rejected, an array rejected.
11. [x] **Calls.** `DirectCall` for a named function and `IndirectCall` through
    a pointer value, arity and prototype checks,
    arguments through `assignConvert`, default argument promotions for
    variadic arguments, calls through function pointers. Failure tests for
    too few arguments and an incompatible pointer argument.

### C - constants, statements, the first milestone

12. [x] **`ConstEval` for integers.** A `TVisitor` over `Constant`, `Conversion`,
    `Arithmetic`, `Comparison`, `Logical` and the unary nodes, masking and
    sign-extending by width; `fold` and `require`. Then `sizeof`, `alignof`
    and `_Countof` on complete types, `static_assert` checked and dropped.
    Tests: `sizeof(int *)`, `(char) 300`, `-1u`, `1 << 31` wrapping,
    `static_assert(0)` failing at the right token.
13. [x] **Statements and functions.** `TStmt` kinds, `JumpTarget` with the
    per-function map, `LocalDecl` with scalar initializers, conditions
    through `ToBool`, `return` through `assignConvert`, `switch` with
    `Case`/`CaseRange` folded through `ConstEval` and a duplicate check,
    `TFunction` with its locals, `TUnit` with globals and their scalar
    initializers, `Typer.type(unit, bindings)`. `Main` prints the typed tree.
    **Milestone: `Main.SOURCE` types end to end** and `PipelineTest` asserts
    a few of its nodes.
14. [x] **Enums.** `TypeBuilder` for enum specifiers (tag lookup through
    `bindings.tags`, fixed underlying type), enumerators folded in order and
    memoized on `Symbol.Enumerator`, references becoming `IntConst`. Tests:
    `enum { A, B = A + 5, C }` gives `C` the value 6; an enum object has an
    integer `CType`.
15. [x] **Sizes through `ConstEval`.** Array sizes and `_BitInt` widths in
    `TypeBuilder`, the `BitInt` variant, `sizeof` of arrays with computed
    sizes. Non-constant sizes fail with "variable length arrays are not
    supported".
16. [x] **Explicit casts, `_Generic`, `typeof`.** Explicit casts reuse `convert`
    with the wider cast rules (6.5.5), `_Generic` selects its arm on the
    unconverted type and returns that arm's tree, `typeof(expr)` types the
    operand under the unevaluated counter and takes its type.

### D - records

17. [x] **Record types and layout.** `CType.Record` over `TagSymbol`,
    `TagSymbol.type`/`layout`, `Layout` for structs and unions without
    bit-fields, the in-progress marker for self-containing structs, `sizeof`
    of records. `LayoutTest` compares offsets and sizes against GCC output
    for a dozen structs under `X86_64SysV`, and runs the same structs under
    the ILP32 test target to check `Layout` only asks the `Target`.
18. [x] **Member access.** `Member` with the per-tag `LinkedHashMap`, `->` as
    `Member(Deref)`, anonymous members flattened, `Materialize` for struct
    rvalues, struct assignment, passing and returning by value through
    `assignConvert` on the same tag. Tests: `s.a.b`, `p->m = 1`, `f().i`.
19. [x] **Bit-fields.** Layout packing rules, bit-field data on `Member`,
    promotion of bit-field values, assignment to bit-fields. Tests against
    GCC layouts under `X86_64SysV`; the packing policy is a `Target` method,
    not a constant in `Layout`.

### E - initializers and declarations

20. [x] **`Initializers.normalize`.** Braced initializers for arrays and records
    with the layout cursor, designators, brace elision, string literal into a
    char array, array size completion from the initializer count. Tests
    print the `TInit` as `(offset value)` lists; `int big[1 << 20] = {0}`
    yields one item.
21. [x] **Address constants and static initializers.** `AddrConst` in
    `ConstEval` (`&x`, `arr + 1`, `&s.m`, string literals, function
    designators); every item of a static-storage initializer must fold.
    Failure test: `int *p = &local;` at file scope is fine, `int x = y;` is
    not.
22. [x] **Compound literals.** `CompoundLit` with an anonymous automatic symbol
    in the function's locals (static at file scope), its `TInit` from step
    20, suppressed under `sizeof` by the unevaluated counter.
23. [x] **Redeclarations and completeness.** Composite types across
    declarations (6.2.7p3), tentative definitions completed to size 1 at end
    of unit, incomplete-type checks for object definitions and `sizeof`,
    block-scope `extern`, `auto` inference from the initializer. Tests:
    `int a[]; int a[3];`, `extern int f(); int f(int x) {}`, `struct S s;`
    with `S` incomplete failing.

### F - closing

24. [x] **Floating and remaining constant rules in `ConstEval`**:
    `FloatToInt` truncation, `bool` results, character constants in case
    labels, `_BitInt` up to 64 bits.
25. [x] **Statement checks that were deferred**: a `return` without a value in a
    non-void function, a value in a void one, `switch` on a non-integer,
    `goto` into the scope of a compound literal. `Bindings.fileScope` is no
    longer read anywhere; `Symbol.declaredType` is documented as diagnostics
    only.

### Deferred
- variable-length arrays and `[*]` (need runtime size expressions on `CType.Array`)
- `_Atomic` semantics, `restrict`, `_Complex`, `_Decimal*` arithmetic (types are carried, operations rejected)
- attributes (kept on the AST, not consulted)
- `_BitInt` wider than 64 bits in `ConstEval`
- string-literal pooling

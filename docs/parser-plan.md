# C parser: architecture and plan

Target grammar: ISO/IEC 9899 C2y working draft **N3886**, Annex A
(https://www.open-std.org/jtc1/sc22/wg14/www/docs/n3886.pdf). Section numbers
below refer to that draft; grammar quoted here was lifted from its Annex A.

## Decision: hand-written recursive descent

Alternatives considered: ANTLR 4 (ALL(*)) versus a hand-written recursive
descent parser with precedence climbing for binary expressions. We chose the
latter because:

1. **C's grammar is not context-free.** `T * x;` is a declaration if `T` is a
   typedef-name and a multiplication otherwise; `(T) * x` is a cast or a
   product for the same reason. The standard resolves this via the
   *typedef-name* production (6.7.9), which requires the parser to consult a
   symbol table it is building as it parses. In hand-written code that is a
   one-line `isTypeName(tok)` at each decision point; in ANTLR it means
   semantic predicates plus scope-tracking actions in the grammar.
2. **Declarators are inside-out** (`int (*a[3])(void)`). Recursive descent
   builds the type naturally on the way back up the recursion; a parse tree
   still needs a second pass to do the same.
3. **The parser's input already exists** as `List<CppToken>` from translation
   phase 7 (`TokenConversion`). A cursor over that list is trivial; ANTLR wants
   its own `TokenSource`/`Token` implementations.
4. **No new build machinery**: no code generation step, no runtime dependency.
5. C is LL(2) once the typedef table exists. The only two-token lookaheads are
   `( type-name` (cast / compound literal), `identifier :` (label) and `[ [`
   (attribute). No backtracking is needed.

ANTLR would be preferable for a tool where grammar fidelity matters more than
the semantic model (formatter, linter), or for a language without the
typedef problem. Here the AST is the product and the next phases are semantic
analysis and code generation.

## Pipeline

```
source ──CppTokenizer──> pp-tokens ──Scanner.expand──> pp-tokens ──TokenConversion──> tokens ──Parser──> AST
         (phases 3-4)                  (phase 4)                    (phase 7)                 (phase 7/8)
```

The parser never sees `#define` lines, `OBJECT_MACRO`/`CALL_MACRO`, `STRINGIZE`
or `PASTE` tokens; those are consumed by the expander. It receives `KEYWORD`,
`IDENTIFIER`, `INTEGER_CONSTANT`, `FLOATING_CONSTANT`, `CHARACTER_LITERAL`,
`STRING_LITERAL`, `PUNCTUATOR` and `EOF`. Anything else is a parse error.

## Packages and classes

```
org.jbm.cc.cpp          existing lexer / expander / phase-7 conversion
org.jbm.cc.ast          the syntax tree (sealed interfaces + records)
  Expr                  expressions (6.5)
  Type                  types built from specifiers + declarators (6.7.3-6.7.9)
  Decl                  declarations, static assertions, function definitions (6.7, 6.9)
  Stmt                  statements (6.8)
  BlockItem             = Decl | Stmt (6.8.3)
  Initializer           braced / expression initializers, designators (6.7.11)
  Attribute             [[ prefix::name(args) ]] (6.7.12) - parsed, kept, otherwise ignored
  AstPrinter            S-expression dump; used by every parser test and for debugging
org.jbm.cc.parse
  TokenCursor           peek(k) / at(text) / accept / expect over List<CppToken>
  ScopeStack            block-scoped name table answering isTypeName(name)
  Parser                one method per Annex A nonterminal
  ParseException        message + line:column, like LexException / ConversionException
```

### AST conventions

- Every node is a `record` implementing one of the sealed interfaces. Java 17
  is the toolchain, so pattern-matching `switch` is unavailable; consumers use
  `instanceof` patterns (or a visitor added when sema needs one).
- Every node keeps the `Token` it starts at (for diagnostics). Literals keep
  their spelling; decoding the value and type of `0x1Full` is sema's job.
- The parser produces **syntactic** `Type`s: a `Type.TypedefName` records both
  the name and the type the typedef resolved to; a `Type.Struct` records its
  tag and, when present, its members, but tag lookup across declarations is
  left to sema. `typeof(expr)` stays unresolved. Qualifiers live on every
  `Type` variant.

### Key parsing decisions

- **Declarators as closures.** `parseDeclarator()` returns the declared name
  plus a `UnaryOperator<Type>`: pointer prefix gives `t -> inner(Pointer(t))`,
  suffix `[n]` gives `t -> inner(Array(t, n))`, suffix `(params)` gives
  `t -> inner(Function(t, params))`, parentheses recurse. Applying the closure
  to the specifier type yields the correct inside-out type without special
  cases (`int *a[3]` is an array of pointers; `int (*a)[3]` a pointer to an
  array). Abstract declarators are the same code with a null name.
- **Declaration specifiers as a bag.** Storage classes, qualifiers, function
  specifiers, alignment and type-specifier keywords are collected in any
  order (`long unsigned int long` is legal), then folded to a `Type` once the
  list ends. Once any type specifier has been seen, a following identifier is
  the declarator, not a typedef-name (`typedef int T; unsigned T x;` declares
  `T`).
- **Declaration vs. expression.** At a block-item or `for`/`if`/`switch` header
  the input is a declaration iff the token is a declaration-specifier keyword,
  `static_assert`, or an identifier for which `isTypeName()` holds and which is
  not followed by `:` (label). Attributes `[[...]]` are parsed first and the
  decision made on what follows.
- **Scopes.** `ScopeStack` records typedef names *and* ordinary identifiers
  (variables, functions, enumerators, parameters), because an inner ordinary
  declaration shadows an outer typedef. Scopes are pushed for compound
  statements, `for`/`if`/`switch` statements, function prototypes and function
  bodies (parameters are declared in the body scope).
- **Expressions.** `primary`/`postfix`/`unary`/`cast` are hand-written; the ten
  binary levels use one precedence-climbing loop; then `conditional`,
  `assignment` (right associative) and `comma`. Argument lists and
  initializers call `parseAssignment`, never `parseExpression`, so commas
  separate. The `unary-expression` restriction on assignment targets is
  checked by sema, not the grammar (like GCC/Clang).
- **Errors.** Fail fast with `ParseException` naming the offending token.
  Error recovery (sync to `;`/`}`) is a later phase, if ever.
- **Tests.** Each parser test runs the *full* pipeline (`CppTokenizer` →
  `Scanner` → `TokenConversion` → `Parser`) and compares `AstPrinter` output,
  so macros are exercised and tests read as one-liners:
  `assertEquals("(+ a (* b c))", expr("a + b * c"))`.

## Findings about the existing code that the parser depends on

- `CppTokenizer.KEYWORDS` was a C++ keyword list (`class`, `template`,
  `co_await`, ...) and lacked most C ones (`restrict`, `_Atomic`, `_BitInt`,
  `_Generic`, `typeof`, ...). Replaced with the 55 keywords of A.2.2. Note that
  C2y dropped the `_Bool`/`_Alignas`/`_Static_assert`/`_Thread_local` spellings.
- `PUNCTUATORS` had C++-only `<=>`, `->*`, `.*` and lacked the digraphs
  `<: :> <% %> %: %:%:` (6.4.7). Digraphs are lexed and normalized to their
  primary spelling so the parser only ever matches `[ ] { } # ##`.
- `TokenConversion` did not accept the `0o` octal prefix, the `wb` bit-precise
  suffix, decimal-float suffixes `df dd dl` or complex suffixes `i I j J`
  (6.4.5.2/6.4.5.3).

## Phases

Each phase ends with the test suite green and `Main` still running.

### Phase 0 - scaffold (done)
- [x] Keyword and punctuator tables per A.2.2 / A.2.7; digraph normalization.
- [x] Phase-7 constant classification per A.2.5 (`0o`, `wb`, `df/dd/dl`, `i/j`).
- [x] `org.jbm.cc.ast`: all node records for 6.5-6.9; `AstPrinter`.
- [x] `org.jbm.cc.parse` support: `TokenCursor`, `ScopeStack`, `ParseException`.

### Phase 1 - expressions (A.3.1, 6.5)
- [x] `Parser` with the expression entry point; `ParserTest` harness running the full pipeline (`expr(src)`)
- [x] primary: identifier, constants (incl. `true`/`false`/`nullptr`), adjacent string-literal concatenation, `( expression )`
- [x] postfix: `[]`, calls, `.`/`->`, `++`/`--`
- [x] unary: `++ --`, `& * + - ~ !`, `sizeof`/`alignof`/`_Countof` (expr and type forms), `static_assert` expression
- [x] cast `( type-name ) cast-expression` and compound literals `( type-name ) { ... }`
- [x] binary via precedence climbing, `?:`, assignment operators, comma
- [x] `_Generic`
- [x] `type-name` (specifier-qualifier-list + abstract declarator) - shared with phase 2

### Phase 2 - declarations (A.3.2, 6.7)
- [x] declaration-specifiers bag and fold to `Type` (incl. `_BitInt(N)`, `_Complex`, `_Decimal*`, `_Atomic(T)`)
- [x] declarators / abstract declarators as closures; pointer qualifiers; array `static`/qualifiers/`*`; parameter-type-list with `...`
- [x] init-declarator-list, initializers, braced initializers, designators
- [x] `typedef` registration in `ScopeStack`; ordinary-identifier shadowing
- [x] `struct`/`union` specifiers with members and bit-fields; `enum` with C23 underlying type
- [x] `typeof` / `typeof_unqual`, `alignas`, `static_assert` declaration
- [x] attributes: `[[ ]]` sequences parsed everywhere the grammar allows; balanced-token argument clauses

### Phase 3 - statements (A.3.3, 6.8)
- [x] compound statement with block-items (declaration | unlabeled-statement | label) and scope push
- [x] labels: `name :`, `case expr :`, `case lo ... hi :`, `default :`
- [x] expression statement, `if`/`else`, `switch`, selection-header with declarations
- [x] `while`, `do`/`while`, `for` (expression and declaration forms)
- [x] `goto`, `continue [label]`, `break [label]`, `return`
- [x] the declaration-vs-expression decision

### Phase 4 - external definitions (A.3.4, 6.9)
- [x] translation-unit; function-definition vs declaration split after the first declarator
- [x] parameters declared in the function body scope
- [x] `Main.SOURCE` parses end to end and `Main` prints the AST

### Phase 5 - hardening (not started)
- [ ] `%:` digraph as a directive introducer at line start (the lexer only recognizes `#` there)
- [ ] constant value decoding (6.4.5.2/6.4.5.3 types and suffixes) as a sema helper
- [ ] error recovery (sync to `;` / `}`) for multi-error reporting
- [ ] golden-file corpus under `src/test/resources/parse/`
- [ ] AST visitor once sema needs one
- [ ] (sema, not parser) secondary blocks (`if (c) stmt`) are blocks per 6.8.5.1/6.8.6.1
      but `ScopeStack` does not push a scope for them. This cannot change a parse - a
      non-compound secondary block cannot contain a declaration, so no typedef name can
      be introduced or hidden there - but sema's symbol table must model it (compound
      literal and VLA lifetimes, jump constraints). `ScopeStack` is intentionally only
      the typedef-name table the grammar needs; sema builds the real one over the AST.
- [ ] attributes are kept on declarations, declarators, parameters, members and
      enumerators but dropped on statements, labels, pointers and specifiers, and
      `AstPrinter` does not show them (only attribute-declarations print)
- [ ] `struct S { enum E : 3; }` - the member-declaration ambiguity between a bit-field
      and an enum-type-specifier is resolved as the enum-type-specifier, matching the
      grammar but not the standard's stated intent for that case

### Phase 6 - sema

The parser's output is syntactic; sema turns it into what lowering needs.
Design: one `org.jbm.cc.ast.Visitor<R>` covers every node kind of the five
sealed hierarchies (`Expr`, `Stmt`, `Decl`, `Type`, `Initializer`), each node
having an `accept`. `AstPrinter` is a `Visitor<String>`; `AstWalker` is a
`Visitor<Void>` with default child traversal that passes extend. Results of a pass over the
existing tree go in identity-keyed side tables (`sema.Bindings`), because
records have structural equality and macro expansion produces structurally
equal but distinct nodes; the typing pass will instead build a new tree with
symbols and explicit casts on the nodes.

- [x] `Resolver`: symbol table with the ordinary and tag namespaces, C scoping
      (compound, selection/iteration statements, prototypes, function bodies);
      storage duration and linkage; every identifier, declarator, parameter,
      enumerator and tag specifier bound to its `Symbol`/`TagSymbol`; labels per
      function with `goto` resolution; `break`/`continue` (labeled too) bound to
      their loop or switch; `case`/`default` checked to be inside a switch.
- [ ] constant expression evaluator (6.6): integer and address constants
- [ ] typing pass over a new tree: literal decoding, lvalue/rvalue, decay,
      promotions and usual arithmetic conversions made explicit as casts,
      member resolution to offsets, `sizeof`/`_Generic` folded, calls checked
      - architecture and phases in `typer-plan.md`
- [ ] layout: `sizeof`/`alignof`, struct offsets and padding (x86-64 SysV)
- [ ] initializers (6.7.11): designators, brace elision, array size completion
- [ ] statement checks: switch case sets, return types
- [ ] secondary blocks as blocks (compound literal / VLA lifetimes)

## Status

Phases 0-4 are implemented: `Parser` covers all of A.3 and the full test suite
(`./gradlew test`) is green. `Main` runs the pipeline end to end and prints the
translation unit as S-expressions.

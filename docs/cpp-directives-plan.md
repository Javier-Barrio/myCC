# Preprocessor directives: plan

Today the tokenizer executes `#define`/`#undef` in scan order and drops
every other directive line, so `#if` compiles both branches and
`#include` is ignored. Conditionals come first, then `#include`.

## Rule

Directives are executed by the tokenizer at the point where they
appear, because the macro table is built in scan order and a skipped
`#define` must not take effect. A directive line emits no tokens.

## A. Conditionals

`#if #ifdef #ifndef #elif #elifdef #elifndef #else #endif`.

- A stack of frames: parent active, branch taken, `#else` seen, active.
  Tokens are emitted only when every frame is active. A skipped group is
  still scanned so nesting is matched, but nothing is emitted, defined
  or evaluated.
- The condition: lex the rest of the line; rewrite `defined X` and
  `defined(X)` to `1`/`0`; macro-expand with `Scanner`; remaining
  identifiers become `0` (`true`/`false` become `1`/`0`); evaluate as a
  64-bit signed/unsigned constant expression with C's operators and
  constants. Floats, strings, division by zero and bad syntax are errors.
- Errors: `#else`/`#elif` after `#else`, `#else`/`#endif` without `#if`,
  `#if` open at end of file.
- Code: `PpExpr` (evaluator), `CppTokenizer` (frames, `defined`, the
  call into `Scanner`).

## B. `#include`

- `HeaderProvider`: text and name for `<n>`/`"n"`; `BundledHeaders`
  (resources under `headers/`), `FileHeaders` (next to the includer,
  then search dirs), a chain. No provider and an `#include` is an error.
- The tokenizer scans the header with a nested tokenizer sharing the
  macro table and emits its tokens in place; depth limit 200.
- `Token` gains `file`; `stripDirectiveLines` keys on file and line;
  diagnostics print `file:line:col`.
- Bundled headers: `stdio.h stdlib.h string.h math.h stddef.h stdbool.h
  stdint.h limits.h`, declarations only, guarded by `#ifndef`, with
  target-dependent types written as `typeof(...)`.

## Steps

1. [ ] `PpExpr` with tests per operator, constant form and error.
2. [ ] Conditionals in the tokenizer; end-to-end tests per directive,
   nesting, skipped `#define`, unevaluated nested `#if`, macros in
   conditions, each error.
3. [ ] `HeaderProvider`, `Token.file`, the strip by file and line.
4. [ ] `#include` with the nested tokenizer, depth limit, errors.
5. [ ] `BundledHeaders` and the headers; each parses on both targets;
   `LowerTest`'s function-pointer program from `#include <stdio.h>`.
6. [ ] `FileHeaders`, the chain, and the entry `Main` and the tests use.
7. [ ] Diagnostics with the file.

Deferred: `#include MACRO`, `#error`, `#line`, `__FILE__`, `__LINE__`,
`#pragma`.

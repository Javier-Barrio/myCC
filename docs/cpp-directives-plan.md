# Preprocessor directives: design and plan

The preprocessor executes `#define` and `#undef` as the tokenizer scans,
building the macro table in source order; every other directive line is
left in the token stream and dropped by `Scanner` before expansion. So
`#if` is ignored and both branches are compiled, and `#include <stdio.h>`
is ignored and `printf` must be declared by hand. This plan adds the
conditional directives first, then `#include` with a `HeaderProvider`
and a set of bundled headers whose guards are ordinary `#ifndef`s.

## Decision: directives are executed by the tokenizer, in source order

The macro table is built as the source is scanned, and a conditional
or an include changes what the rest of the scan sees: a `#define` in a
skipped group must not take effect, and a header's macros must be
visible after its `#include` and not before. So every directive is
executed at the point where it appears, by the tokenizer, which already
has the directive state machine for `#define`. The alternative, a pass
over the token stream afterwards, would see the whole file's macros at
once and could not skip a group correctly.

The tokenizer emits nothing for a conditional or an include line: the
directive is consumed. `#define` lines stay in the stream for `Scanner`
to strip by line number, as today.

## Part A: conditional compilation

**Directives.** `#if expr`, `#ifdef name`, `#ifndef name`, `#elif expr`,
`#elifdef name`, `#elifndef name`, `#else`, `#endif`.

**Groups.** A stack of frames, one per open `#if`: whether the enclosing
group is active, whether a branch of this one has already been taken,
whether `#else` has been seen, and whether the current branch is active.
A token is emitted only when every frame is active. In an inactive
group the tokenizer still scans, so that nested conditionals are
counted, but it emits nothing, executes no `#define` or `#undef`, and
evaluates no `#if`: a group under an inactive one is inactive whatever
its condition says, which is what lets `#if 0` hide code that would not
even preprocess.

**The condition.** The rest of the `#if` line is lexed as a token list,
then:

1. `defined name` and `defined ( name )` are replaced by `1` or `0`
   against the macro table as it stands. This happens before expansion,
   as C requires, so `defined` applied to a macro that expands is still
   about the macro's existence.
2. The list is macro-expanded by `Scanner` with the current table: the
   same expander as for the program text, on a stream of one line.
3. Every identifier that remains, and any keyword other than `true` and
   `false`, becomes `0`.
4. The result is evaluated as an integer constant expression in
   `intmax_t`/`uintmax_t` arithmetic: a value is 64 bits plus a
   signedness; a `u` suffix, or a constant that does not fit a signed
   64-bit value, makes it unsigned; an unsigned operand makes a binary
   operation unsigned, so `-1 < 0u` is false as in C. Operators: `? :`,
   `||`, `&&`, `|`, `^`, `&`, `== !=`, `< > <= >=`, `<< >>`, `+ -`,
   `* / %`, unary `+ - ~ !`, parentheses; integer constants in every C
   form with digit separators; character constants with the usual
   escapes. A floating constant, a string, a division by zero, or a
   malformed expression is an error naming the line.

`#ifdef` and `#ifndef` look the name up; `#elif` is `#if` for a frame
whose branches so far were not taken; `#else` after `#else`, `#elif`
after `#else`, `#endif` or `#else` without `#if`, and an `#if` still
open at the end of the file are errors.

**Where it lives.** `PpExpr` evaluates a token list to a value;
`CppTokenizer` gains the frame stack, the directive names, the
`defined` rewrite and the call into `Scanner`. `Scanner` is unchanged.

## Part B: `#include`

**Processed by the tokenizer, where it appears.** On an `#include`
line the tokenizer asks a `HeaderProvider` for the header's text, scans
it with a nested tokenizer that shares the macro table and the
conditional stack's discipline, and emits the header's tokens at that
point. Macros defined before the include are visible in the header and
the header's macros after it, because the table is built in order. Every
token carries the header's name and its own line and column.

**`HeaderProvider`.** The tokenizer never touches the file system. It
asks the provider for a header by name and kind (`<name>` or `"name"`)
and the including file, and gets the text and a name to attribute
tokens to, or nothing. `BundledHeaders` serves the compiler's own
headers from resources under `headers/` for `<...>` includes;
`FileHeaders` serves the file system, a `"name"` looked up next to the
including file and then in the search directories, a `<name>` in the
search directories only; `Headers.of(...)` chains them, and the
compiler's default is the bundled headers followed by the file system.
`tokenSet(source)` keeps working for source without includes; an
`#include` with no provider is an error saying so.

**Guards.** The bundled headers guard themselves with `#ifndef`,
`#define`, `#endif`, which Part A makes work; there is no `#pragma once`.

**Token origin.** `Token` gains a `file` field, `null` for the main
source. `Scanner.stripDirectiveLines` compares the file along with the
line, since a header token can share a line number with a `#define` in
the main file. Diagnostics print `file:line:col` when there is a file.

**The bundled headers.** Declarations only, target-independent by
construction: `size_t` is `typeof(sizeof 0)`, `ptrdiff_t` is the
difference of two pointers, so the typer computes them for the target
in use.

```
stdio.h      int printf(const char *, ...); int puts(const char *); int putchar(int); ...
stdlib.h     void *malloc(size_t); void free(void *); void exit(int); int abs(int); ...
string.h     size_t strlen(const char *); void *memcpy(void *, const void *, size_t); ...
math.h       double sqrt(double); double pow(double, double); ...
stddef.h     size_t, ptrdiff_t, NULL, offsetof
stdbool.h    (empty: bool, true and false are keywords)
stdint.h     int8_t ... int64_t, the unsigned ones, intptr_t and uintptr_t via typeof
limits.h     INT_MAX and friends as the constants of a two's complement target
```

The set is what the VM's builtins will implement (`cshell-plan.md`); a
function declared here and not provided by a consumer faults at the
call, by name, which is the TAC's rule already.

## Testing

Each step is test first. The conditional tests run the pipeline through
`Scanner` and compare token texts, as `PreprocessorEndToEndTest` does;
the evaluator has its own tests on token lists; the include tests use a
map-backed `HeaderProvider`; the bundled headers are tested through the
pipeline on both targets.

## Steps

Each step is one commit, its tests first and failing, then the suite
green with `Main` still running.

**A. Conditionals**
1. [ ] **`PpExpr`**: the evaluator on a token list; tests for each operator,
   the unsigned rules, every constant form, character constants, and each
   error.
2. [ ] **The directives in the tokenizer**: the frame stack, `#if`, `#ifdef`,
   `#ifndef`, `#elif`, `#elifdef`, `#elifndef`, `#else`, `#endif`, the
   `defined` rewrite and the expansion of the condition; the end-to-end
   tests: each directive, nesting, a `#define` in a skipped group not
   taking effect, a nested `#if` in a skipped group not evaluated, macros
   in conditions, and each error.

**B. Include**
3. [ ] **`HeaderProvider`, `Header`, the map-backed test provider**, the
   `file` field on `Token`, `stripDirectiveLines` keyed by file and line.
4. [ ] **`#include` in the tokenizer**: the nested tokenizer sharing the macro
   table, splicing, a depth limit against cycles, the missing-header and
   no-provider errors; tokens carrying the header's name.
5. [ ] **`BundledHeaders`** and the headers, each guarded; the both-targets
   test of each; `LowerTest`'s function-pointer program from `#include
   <stdio.h>`.
6. [ ] **`FileHeaders` and `Headers`**, the compiler's default chain; the
   `tokenSet(source, provider, name)` entry used by `Main`, the corpus
   tests and `LowerTest`.
7. [ ] **Diagnostics with the file**: `SemaException` and `ParseException`
   print `file:line:col`; the test of an error inside a header.

### Deferred
- `#include MACRO` (the macro-expanded header name)
- `#error`, `#warning`, `#line`, `__FILE__`, `__LINE__`, `__STDC__`
- `#pragma` (ignored today by being dropped with every unknown directive)

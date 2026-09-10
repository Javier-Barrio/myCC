# `#include`: design and plan

The preprocessor executes `#define` and `#undef` as the tokenizer scans,
building the macro table in source order; every other directive line is
left in the token stream and dropped by `Scanner` before expansion. So
`#include <stdio.h>` is silently ignored today, and a program that needs
`printf` has to declare it. This plan adds `#include`, a `HeaderProvider`
that says where headers come from, a set of bundled headers for the C
library the VM will provide, and `#pragma once`. It does not add
conditional compilation, which is a separate step noted at the end.

```c
#include <stdio.h>
int main(void) { printf("%d\n", 42); return 0; }
```

## Decision: an include is processed by the tokenizer, where it appears

Alternatives considered: (a) textual inclusion before tokenizing, the
way a naive preprocessor pastes files together; (b) a pass over the
token stream after tokenizing, splicing the header's tokens in; (c) the
tokenizer, on reading an `#include` line, scans the header with a nested
tokenizer that shares its macro table and emits the header's tokens at
that point. We chose (c):

1. [ ] **Order is what C requires.** Macros defined before the include are
   visible in the header, macros the header defines are visible after
   it, and a `#define` in the main file between two includes affects the
   second only. The macro table is built in scan order, so the header
   must be scanned at its point of inclusion; (b) would see the whole
   file's `#define`s before any of the header's.
2. [ ] **Every token keeps its origin.** A token scanned from a header
   carries the header's name and its own line and column, so an error in
   `stdio.h` says so. Textual inclusion (a) would renumber the main file
   and lose the file name.
3. [ ] **It is where the directive machinery already is.** `#define` and
   `#undef` are recognized by the tokenizer's directive state; `#include`
   and `#pragma` are two more states of the same machine.

The tokenizer emits nothing for an `#include` or `#pragma` line: the
directive is consumed. That differs from `#define` lines, which stay in
the stream for `Scanner` to strip by line number, and it is why a token
also carries its file: the strip must not drop a header's tokens that
happen to share a line number with a `#define` in the main file.

## Decision: headers come from a `HeaderProvider`

The tokenizer never touches the file system. It asks a `HeaderProvider`
for a header's text by name and kind (`<name>` or `"name"`) and the file
that asked, and gets the text and a name to attribute tokens to, or
nothing. Three implementations:

- **`BundledHeaders`**: the compiler's own headers, resources under
  `headers/` on the class path, for `<...>` includes. This is what the
  shell and the tests use, and what makes `#include <stdio.h>` mean the
  same in the shell as in the compiler.
- **`FileHeaders`**: the file system, for a compiler run on files: a
  `"name"` include is looked up next to the including file, then in the
  search directories; a `<name>` include in the search directories only.
- **`Headers.of(first, second, ...)`**: a chain; the compiler's default
  is the bundled headers followed by the file system.

`CppTokenizer.tokenSet(source)` keeps working for source without
includes; `tokenSet(source, provider, name)` is the new entry, and an
`#include` with no provider is an error saying so.

## Decision: `#pragma once` now, conditional compilation later

Include guards are `#ifndef`/`#define`/`#endif`, and the preprocessor
has no `#if` family. Rather than start on conditionals here, this plan
adds `#pragma once`, which marks the current file as included and makes
a later `#include` of the same header a no-op. The bundled headers use
it. Conditional compilation (`#if`, `#ifdef`, `#ifndef`, `#elif`,
`#else`, `#endif`, `defined`, and constant-expression evaluation on
preprocessing tokens) is its own plan; when it exists, guards in
third-party headers work too.

Unknown `#pragma`s are ignored, as C says. Other directives (`#if`,
`#error`, `#line`) stay as they are today, dropped by `Scanner`, until
their plans exist; `#error` is cheap and is included here.

## The bundled headers

Declarations only, and target-independent by construction: a type the
target decides is written with `typeof` so the typer computes it.

```
stdio.h      int printf(const char *, ...); int puts(const char *); int putchar(int); ...
stdlib.h     void *malloc(size_t); void free(void *); void exit(int); int abs(int); ...
string.h     size_t strlen(const char *); void *memcpy(void *, const void *, size_t); ...
math.h       double sqrt(double); double pow(double, double); ...
stddef.h     typedef typeof(sizeof 0) size_t; typedef typeof((char *)0 - (char *)0) ptrdiff_t;
             #define NULL ((void *)0); #define offsetof(T, m) ((size_t)&((T *)0)->m)
stdbool.h    (empty: bool, true and false are keywords)
stdint.h     typedef signed char int8_t; ... typedef long long int64_t; the unsigned ones;
             intptr_t and uintptr_t via typeof
limits.h     INT_MAX and friends as the constants of a two's complement target
```

Each header begins with `#pragma once` and includes what it needs
(`stdlib.h` and `string.h` include `stddef.h` for `size_t`). The set is
what the VM's builtins will implement (`cshell-plan.md`); a function
declared here and not provided by a consumer faults at the call, by
name, which is the TAC's rule already.

## Token origin

`Token` gains a `file` field, the name the provider attributed to the
header (`<stdio.h>` shows as `stdio.h`), `null` for the main source. The
line and column are the header's own. Diagnostics that print `line:col`
print `file:line:col` when there is a file. `Scanner.stripDirectiveLines`
compares the file along with the line.

## Pipeline

```
CppTokenizer(source, provider, name)
  #define / #undef      the macro table, in order (as today)
  #include <n> | "n"    provider.read(n, kind, name) -> text and name; a nested tokenizer over the text,
                        sharing the macro table and the once-set; its tokens are emitted here
  #pragma once          the current name joins the once-set; a later include of it emits nothing
  #pragma anything      ignored
  #error text           a preprocessing error with the file and line
Scanner.expand          as today, over the spliced stream
```

An `#include` inside an included header nests the same way; a depth
limit (200, as GCC's) turns a cycle into an error. The header name after
`#include` is the literal `<...>` or `"..."` form; the macro-expanded
form (`#include MACRO`) is deferred.

## Packages and classes

```
org.jbm.cc.cpp
  HeaderProvider          interface: Optional<Header> read(String name, boolean system, @Nullable String from)
  Header                  record: the text and the name to attribute tokens to
  BundledHeaders          the resources under headers/
  FileHeaders             the file system with search directories
  Headers                 a chain of providers
  CppTokenizer            the #include, #pragma and #error directive states; the nested tokenizer; the once-set
  Scanner                 stripDirectiveLines by (file, line)
  CppTokenizer.Token      the file field
src/main/resources/headers/*.h
```

## Testing

Each step is test first; the tests use a map-backed `HeaderProvider` so
they need no files, and the bundled headers are tested through the
pipeline.

- **Tokenizer**: an include splices the header's tokens at its point; a
  macro defined before the include expands inside the header; a macro
  the header defines expands after it and not before; a nested include;
  the same header included twice with `#pragma once` appears once, and
  twice without; a missing header is an error naming the header and the
  including file and line; a cycle hits the depth limit; `"n"` is asked
  of the provider with the including file and `<n>` as a system header;
  tokens from a header carry its name and their own line numbers;
  `#error` raises with its text.
- **Scanner**: a header token on the same line number as a `#define` in
  the main file survives the strip.
- **Bundled headers**: each one includes, parses and types on both
  targets; `sizeof(size_t)` is 8 on x86-64 and 4 on ILP32; `NULL`,
  `offsetof`, `INT_MAX` have their values; the function-pointer program
  of `LowerTest` compiles from `#include <stdio.h>` and lowers to a
  `declare @printf(ptr, ...) -> i32`.
- **Diagnostics**: a type error inside an included header reports
  `stdio.h:line:col`.

## Steps

Each step is one commit, its tests first and failing, then the suite
green with `Main` still running.

1. [ ] **`HeaderProvider`, `Header`, the map-backed test provider**, and the
   `file` field on `Token` with `stripDirectiveLines` keyed by file and
   line; a tokenizer test that a header token on a `#define`'s line
   survives.
2. [ ] **`#include` in the tokenizer**: the directive state, the nested
   tokenizer sharing the macro table, splicing, the depth limit, the
   missing-header error; the tokenizer tests above.
3. [ ] **`#pragma once`, other pragmas, `#error`**.
4. [ ] **`BundledHeaders`** and the headers `stddef.h`, `stdbool.h`,
   `stdint.h`, `limits.h`, `stdio.h`, `stdlib.h`, `string.h`, `math.h`;
   the both-targets test of each; `LowerTest`'s function-pointer program
   from `#include <stdio.h>`.
5. [ ] **`FileHeaders` and `Headers`**, the compiler's default chain; the
   `tokenSet(source, provider, name)` entry used by `Main`, the corpus
   tests and `LowerTest`.
6. [ ] **Diagnostics with the file**: `SemaException` and `ParseException`
   print `file:line:col`; the test of an error inside a header.

### Deferred
- conditional compilation: `#if` and its family, `defined`, expression evaluation
- `#include MACRO` (the macro-expanded header name)
- `#line`, `__FILE__`, `__LINE__`
- `#include_next` and other extensions

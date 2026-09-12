# myCc

A C compiler in Java: preprocessor, parser, semantic analysis, and a
lowering to a three-address code (TAC), plus a VM that executes the TAC
and `cshell`, an interactive C shell in the spirit of `jshell`.

The design lives in `docs/`: `tac-plan.md` (the TAC), `lower-plan.md`
(the lowering), `cshell-plan.md` (compiler and VM decisions),
`repl-plan.md` (the shell), `cpp-directives-plan.md` (the preprocessor).

## The language and the targets

The compiler implements C as of the C2y working draft N3886, which is
C23 plus a few small additions such as `_Countof`, compiled as C23 by
default: `bool`, `nullptr`, `typeof`, `auto`, `static_assert`,
`[[attributes]]`, `constexpr` objects, `_Countof`, and an empty
parameter list `()` meaning `(void)`. `-std=c17` compiles older code instead:
`()` declares a function without a prototype, so any arguments may be
passed, and old-style definitions with an identifier list, `int f(a,
b) int a; char b; { ... }`, are accepted. The older spellings `_Bool`,
`_Alignas`, `_Alignof`, `_Static_assert` and `_Thread_local` work in
both. GNU extensions that real code depends on are in as well:
statement expressions `({ ... })`, `__attribute__((...))` (accepted and
ignored), range designators `[a ... b]`, empty structs, zero-length
arrays, `__builtin_expect`, `#pragma push_macro`. Not supported yet:
variable length arrays, `_Complex`, `_Decimal` types, `long double`
beyond `double` precision, `setjmp`, threads.

The target is x86-64 Linux with the System V ABI: the VM and the
shell lay out data as it does, the assembler writes ELF64 objects, and
the printed assembly is for the GNU assembler; the C library is glibc's
when a program is linked, and a small built-in subset of it in the VM.
An ILP32 data model (`Ilp32`, 32-bit `long` and pointers) exists for
the type system and the VM only, to keep the layout code honest; there
is no code generator for it.

## The commands

`./gradlew installDist` builds the launchers; then `bin/mycc` is the
compiler as a gcc-compatible command and `bin/cshell` the shell:

```
bin/mycc -c file.c -o file.o        # an ELF object, ready for gcc or ld
bin/mycc -S -a file.c               # assembly with the TAC in comments
bin/mycc -E -DX=1 -I inc file.c     # preprocessed
bin/mycc -o prog a.c b.o -lm        # compiles the C and links through gcc
bin/mycc -std=c17 -c old.c
```

`-O`, `-g`, `-W...`, `-f...`, `-m...` and the `-M` dependency flags
are accepted and ignored, so a build system can be pointed at it with
`CC=bin/mycc`. Until there is a linker of our own, the link step is
gcc's; the objects are all ours.

## Lua and curl, as checks

Lua 5.4.7 builds with the compiler unchanged and passes its own test
suite, `all.lua`, in full. With the Lua sources unpacked in `lua/`:

```
CP=build/classes/java/main:build/resources/main
for f in lua/src/l*.c; do java -cp $CP org.jbm.mycc.Main -std=c17 -c $f -o obj/$(basename $f .c).o; done
gcc -o lua obj/*.o -lm
cd lua-tests && ../lua -e "_U=true" all.lua
```

`luac.c` is left out since it defines its own `main`.

curl 8.10.1 builds through its own `configure` with the compiler as
`CC`, and its test suite runs against the result:

```
./configure CC=$PWD/../myCc/bin/mycc --without-ssl --disable-shared --without-libpsl --without-zlib
make -j4 && cd tests && perl runtests.pl -n -a
```

The objects we produce follow the System V ABI, small structs in
registers included, so they link with gcc-built objects and with the
C library either way; `AbiTest` checks that in both directions.

## Build and test

Java 17 and the Gradle wrapper are all that is needed:

```
./gradlew build          # compiles and runs every test
./gradlew test           # the tests alone
```

The typed and TAC corpus goldens under `src/test/resources` are
regenerated with `./gradlew test -Dtyped.update=true -Dtac.update=true`.

`src/test/resources/c-testsuite` holds the single-file programs of
[c-testsuite](https://github.com/c-testsuite/c-testsuite), compiled as
C17 since they predate C23; the ones in `passing.txt` must pass on the
VM and natively. `./gradlew test
-Dctestsuite.report=true --tests '*CTestsuiteTest*' -i | grep CTESTSUITE`
tries every program and prints why the others fail.

## The shell

```
./gradlew installDist
build/install/cshell/bin/cshell
```

The launcher runs on your terminal, which is what gives line editing,
history (Up and Down arrows walk it, Ctrl-R searches it, kept in
`~/.cshell_history`) and tab completion. `./gradlew -q cshell
--console=plain` also runs the shell but through Gradle's pipes, so it
has none of those; use it for scripted input.

Type declarations, statements or expressions; a declaration is kept
for the lines that follow, a statement runs once, an expression is
evaluated and stored in `$1`, `$2`, ... for later use. `/exit` or
Ctrl-D ends the session.

```
cshell> int sq(int x) { return x * x; }
|  defined sq
cshell> int v[3] = { 1, 4, 9 };
v ==> {1, 4, 9}
cshell> v[1] + sq(v[2])
$1 ==> 85
cshell> for (int i = 0; i < 3; i++) { v[i] *= 2; }
cshell> v
$2 ==> {2, 8, 18}
```

`/help` lists the commands: `/list`, `/vars`, `/funcs`, `/types`,
`/macros`, `/tac name`, `/drop name`, `/load file`, `/save file`,
`/reset`, `/exit`. On a terminal the shell has line editing, history
in `~/.cshell_history`, and tab completion of names, members after
`.` or `->`, commands and header names.

Headers are not included unless you ask: `#include <stdio.h>` declares
`printf`, and `-I dir` on the command line adds a directory for your
own headers, `build/install/cshell/bin/cshell -I include`. The shell
and the VM see the bundled headers, a subset of the C library that the
VM implements as builtins, bound by name while a header declares them;
calling a function the VM does not have faults with "no definition".

A session can also be piped in: `echo '6 * 7' | ./gradlew -q cshell --console=plain`.

## Compiling a file

`org.jbm.mycc.Main` prints the syntax tree, the typed tree and the TAC
of a file, or with `-S` its x86-64 assembly, `-S -a` with each TAC
instruction as a comment before the instructions it became:

```
CP=build/classes/java/main:build/resources/main:$(find ~/.gradle -name 'annotations-26*.jar' | head -1)
java -cp $CP org.jbm.mycc.Main file.c [-I dir]
java -cp $CP org.jbm.mycc.Main -S -a file.c > file.s
gcc -o prog file.s          # AT&T syntax for the GNU assembler; the C library is glibc's
java -cp $CP org.jbm.mycc.Main -c file.c      # or our own assembler: writes file.o
gcc -o prog file.o
```

Without a file it compiles its built-in sample program. In the shell,
`/asm name` shows a function's assembly the same way. `-std=c17`, on
`Main` and on `cshell`, selects the older standard described above.

A native build (`-S` or `-c`) declares the C library from the system's
own headers in `/usr/include`, the ones that match the glibc the
program is linked with, so `setjmp.h`, `time.h`, `pthread.h` and the
rest are all there; the compiler brings only the headers that describe
itself, `stddef.h`, `stdarg.h`, `stdbool.h`, `float.h`, `stdalign.h`,
`stdnoreturn.h` and `iso646.h`. glibc's headers are read as they are,
without `__GNUC__`, which turns their GNU-only parts off. The bundled
library headers are for the VM, whose builtins they declare.

# myCc

A C compiler in Java: preprocessor, parser, semantic analysis, and a
lowering to a three-address code (TAC), plus a VM that executes the TAC
and `cshell`, an interactive C shell in the spirit of `jshell`.  There is 
also an x86-64 assembler built in.  The linker is planned but not yet implemented;
see [Gaps](#gaps) for that and the other things missing or approximated.

The compiler is non-optimizing currently.

The compilation targets are x86-64 Linux and the cshell.

Most of the design lives in `docs/`.  The compiler is WIP and is not production-ready at this point
but supports enough to play with it and build the Lua and curl test suites.

## AI Note
The project is developed with the assistance of claude Fable 5.1.  I am driving the architecture and supervising and verifying the implementation. 

## The language and the targets

The compiler implements C as of the C2y working draft N3886, which is
C23 plus a few small additions.  It also supports partially C17,
including the `restrict` keyword.

## Lua and curl, as checks

Lua 5.4.7 builds with the compiler unchanged and passes its own test
suite, `all.lua`, in full. With the Lua sources unpacked in `lua/`:

```
for f in lua/src/l*.c; do bin/mycc -std=c17 -c $f -o obj/$(basename $f .c).o; done
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

## Gaps

What is missing or approximated, in the order it is likely to matter:

- **A linker.** `bin/mycc -o prog` compiles every file itself and
  hands the objects to gcc for the link. The plan is a dynamic linker
  of our own producing a PIE against `libc.so.6` with our own `_start`,
  which removes gcc from the pipeline; static linking against `libc.a`
  is not planned.
- **Variable length arrays** compile as zero-length arrays, so a
  program that declares one builds and runs as long as it does not use
  it. Real VLAs need a dynamic stack allocation through the TAC, the VM
  and the emitter.
- **`long double`** is computed and stored as `double` in a 16-byte
  slot. Its bits are wrong when handed to glibc, `printf("%Lf")`
  above all, and `va_arg(ap, long double)` reads a double. The x87
  conversions at the library boundary are the fix.
- **`_Complex`** and the `_Decimal` types are rejected.
- **No optimization** and no register allocation: every variable has a
  frame slot and every instruction goes through memory; temporaries
  share slots, which is what keeps frames small. Correct and slow.
- **Aggregates past a variadic callee's named parameters** travel by
  our own pointer convention, not SysV's, so a struct passed to a
  library's `...` is wrong; to our own functions it is consistent.
- **`alignas` above 16** on a local is not honored natively, since the
  frame is addressed from a 16-aligned `%rbp`; globals of any alignment
  are fine.
- **The VM's C library** is a subset: the printf family, files,
  strings, `malloc`, math, and no `setjmp`, threads or sockets. A native
  build has all of glibc.
- **The preprocessor** lacks `#include_next` and `__has_include`,
  neither of which glibc's headers need without `__GNUC__`; `-M`
  dependency output is accepted and not produced.
- **No debug info**, so `gdb` shows symbols and disassembly only.
- **ILP32** exists for the VM and for testing: a second target with
  every width different from x86-64's, so no size can hide in a rule
  that should ask the target. It has no code generator.

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
./gradlew installDist       # builds the launchers under bin/
bin/cshell
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
own headers, `bin/cshell -I include`. The shell
and the VM see the bundled headers, a subset of the C library that the
VM implements as builtins, bound by name while a header declares them;
calling a function the VM does not have faults with "no definition".

A session can also be piped in: `echo '6 * 7' | ./gradlew -q cshell --console=plain`.

## Compiling a file

`bin/mycc` is the compiler as a command that takes gcc's flags, so a
build system can be pointed at it with `CC=bin/mycc`:

```
bin/mycc -o prog file.c              # compiles and links; the link step is gcc's for now
bin/mycc -c file.c -o file.o         # an ELF object from our own assembler, for gcc or ld
bin/mycc -S file.c                   # x86-64 assembly for the GNU assembler, AT&T syntax
bin/mycc -S -a file.c                # the same with each TAC instruction as a comment before its code
bin/mycc -E -DX=1 -I inc file.c      # preprocessed, to standard output
bin/mycc -std=c17 -c old.c           # the older standard described above
gcc -o prog file.s                   # or file.o: the C library is glibc's
```

`-O`, `-g`, `-W...`, `-f...`, `-m...` and the `-M` dependency flags
are accepted and ignored; `-l`, `-L` and objects on a link line go to
gcc with our objects. In the shell, `/asm name` shows a function's
assembly the way `-S -a` does, and `bin/cshell -std=c17` selects the
older standard there.

The trees behind the code are printed by `Main`: the syntax tree, the
typed tree and the TAC of a file, and without a file those of its
built-in sample program:

```
java -cp "build/install/cshell/lib/*" org.jbm.mycc.Main file.c [-I dir] [-std=c17]
```

A native build (`-S` or `-c`) declares the C library from the system's
own headers in `/usr/include`, the ones that match the glibc the
program is linked with, so `setjmp.h`, `time.h`, `pthread.h` and the
rest are all there; the compiler brings only the headers that describe
itself, `stddef.h`, `stdarg.h`, `stdbool.h`, `float.h`, `stdalign.h`,
`stdnoreturn.h` and `iso646.h`. glibc's headers are read as they are,
without `__GNUC__`, which turns their GNU-only parts off. The bundled
library headers are for the VM, whose builtins they declare.

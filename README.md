# myCc

A C compiler in Java: preprocessor, parser, semantic analysis, and a
lowering to a three-address code (TAC), plus a VM that executes the TAC
and `cshell`, an interactive C shell in the spirit of `jshell`.

The design lives in `docs/`: `tac-plan.md` (the TAC), `lower-plan.md`
(the lowering), `cshell-plan.md` (compiler and VM decisions),
`repl-plan.md` (the shell), `cpp-directives-plan.md` (the preprocessor).

## Build and test

Java 17 and the Gradle wrapper are all that is needed:

```
./gradlew build          # compiles and runs every test
./gradlew test           # the tests alone
```

The typed and TAC corpus goldens under `src/test/resources` are
regenerated with `./gradlew test -Dtyped.update=true -Dtac.update=true`.

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
own headers, `build/install/cshell/bin/cshell -I include`.
The library functions themselves are not provided yet; calling one
faults with "no definition".

A session can also be piped in: `echo '6 * 7' | ./gradlew -q cshell --console=plain`.

## Compiling a file

`./gradlew -q run` is not defined; `org.jbm.mycc.Main` prints the syntax
tree, the typed tree and the TAC of a file:

```
java -cp build/classes/java/main:<annotations jar> org.jbm.mycc.Main file.c [-I dir]
```

Without a file it compiles its built-in sample program.

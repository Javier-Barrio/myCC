# The REPL: `org.jbm.repl`

The interactive shell in the spirit of `jshell`, built on the compiler
and the VM. It lives in `org.jbm.mycc.repl`, with the VM in `org.jbm.mycc.repl.vm`; the VM
moves into it later, so nothing here reaches into the VM beyond its
public entry points. This supersedes the shell sections of
`cshell-plan.md`; the compiler and VM decisions there stand.

```
cshell> #include <stdio.h>
cshell> int sq(int x) { return x * x; }
|  defined sq
cshell> int v[3] = { 1, 4, 9 };
v ==> {1, 4, 9}
cshell> v[1] + sq(v[2])
$1 ==> 85
cshell> /vars
|  int v[3] = {1, 4, 9}
|  int $1 = 85
```

## The loop

State: the kept lines (directives, declarations, `$N` declarations),
each with its source range in the concatenation; the `$N` counter; one
`VM`. No session object.

Per line:

```
text     = kept lines + the line
compiled = Compiler.compileScript(text, headers)     errors: shown, nothing kept
vm.step(compiled.tac())                               binds new and changed names; runs .file if any
```

- The parser's script mode accepts statements at file scope and puts
  them in `.file` (`cshell-plan.md`, "the file function"). If the
  compile fails at end of input the line is incomplete: continuation
  prompt. A missing `;` at the end of a statement is allowed.
- No `.file`: the line was a declaration. It is kept and echoed: a
  function as `|  defined f`, an object with its value read back from
  the VM.
- `.file` with one non-void expression statement: compiled once more
  as `typeof_unqual((e)) $N;` kept plus `$N = (e);` run, then `$N ==>
  value`. Anything else in `.file` runs and keeps nothing.
- Redefinition replaces the kept line that declared the name; if the
  new text fails to compile the old line is restored.

**Startup.** The shell loads one module before the first prompt: an
empty unit, no headers. It binds the VM's builtins under their names,
today no-ops (`VM.bind(name, args -> null)`), so a later `#include
<stdio.h>` typed by the user declares `printf` and a call reaches the
builtin. Nothing is included on the user's behalf.

## Values

`ValuePrinter` reads an object by name: `vm.addressOf(name)` and
`vm.memory().read(address, size)`, then formats by the `CType` from the
typed tree of the last compile: integers decimal, `char` as `'a' (97)`,
`bool` as `true`/`false`, floating by `%g`, pointers hex with the string
for `char *` and the name for a function, arrays `{1, 2}`, structs
`{.x = 1, .y = 2}`, depth-limited with `...`.

## The terminal

`Console` is the shell's own interface, so transcripts never need a
terminal:

```
interface Console {
    Optional<String> readLine(String prompt);   // empty at end of input
    void print(String text);
    void complete(Completer completer);         // installs tab completion
}
```

Two implementations: `JLineConsole` over JLine 3 (history across
sessions in `~/.cshell_history`, line editing, a continuation prompt
`   ...> `, Ctrl-D ends, Ctrl-C clears the line), and `ScriptConsole`
over a list of lines for tests. JLine is the one new dependency.

**Completion** answers from what the VM and the last compile know:

| Context | Candidates |
|---|---|
| start of line, `/` typed | the commands |
| after `/tac`, `/drop`, `/list` | names in the VM's symbol table |
| an identifier prefix anywhere | functions and globals from `vm.symbols()`, macro names from the last `TokenSet`, typedef and tag names from the typed tree, C keywords |
| after `#include <` | the bundled header names |
| after `.` or `->` | the members of the struct the expression before it has, when the typed tree knows it; else nothing |

A candidate shows its kind and type on the right, as `jshell` does:
`sq   int (int)`, `v   int[3]`.

## Commands

```
/help                  /list [name]     kept lines, numbered; one name's line
/vars  /funcs  /types  /macros          what is defined, with types or expansions
/tac <name>            the TAC of a function or global as TacWriter prints it
/drop <name>           forget the line that declares it; refused if others need it
/reset                 forget everything; back to the empty startup module
/load <file>           run a file line by line       /save <file>   write the kept text
/exit
```

## Errors

Compile errors carry a token with a line in the concatenation; the
line map turns it into the snippet and the column, printed with a
caret under the token. A VM fault prints its message and the C
location from the instruction's token; the kept text is unchanged.

## Classes

```
org.jbm.mycc.repl   Repl (the loop, kept lines, line map, classification, commands)
               Console, JLineConsole, ScriptConsole, Completer
               ValuePrinter
               CShell (main: arguments, JLine, a file to load first)
org.jbm.mycc.cc     Compiler.compileScript(text, headers) -> Compiled(TUnit typed, Module tac, TokenSet tokens)
org.jbm.mycc.repl.vm     VM.bind(String, Builtin); VM.symbols(); VM.addressOf; Memory.read
```

## Tests

Transcripts under `src/test/resources/repl/*.cshell`, a script of
input lines and the exact expected output, run through `Repl` with a
`ScriptConsole`: each line kind, continuation, every command,
redefinition (compatible and not), a failing line leaving the kept
text intact, a VM fault mid-line, a global's value surviving a
recompile, completion candidates for each context.

## Steps

1. [x] `Parser.parseScript` and `.file`; `$` in identifiers; `Compiler.compileScript`.
2. [x] `VM.bind`, `VM.symbols`, `Memory.read`; the empty startup module and the no-op builtins.
3. [x] `Repl` core: kept lines, line map, declarations and statements, `ScriptConsole`, first transcripts.
4. [x] `$N`, `ValuePrinter`, continuation.
5. [x] Commands; redefinition and `/drop`.
6. [x] `Completer` and its contexts; `JLineConsole`; `CShell` main and a `cshell` Gradle task.
7. [x] `/load`, `/save`, errors with the caret, faults with the C location.

Deferred: real builtins (`printf`, `malloc`, ...) replacing the no-ops;
non-constant initializers at file scope; `$N` per expression when a
line has several; a C backtrace on faults.

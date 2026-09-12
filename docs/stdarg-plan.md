# Variable arguments: `stdarg.h`

`va_list`, `va_start`, `va_arg`, `va_end` and `va_copy` for scalar and
pointer arguments, on the VM and natively. A `va_list` is the SysV
x86-64 one, a one-element array of `{ gp_offset, fp_offset,
overflow_arg_area, reg_save_area }`, so a list handed to `vsnprintf`
means the same thing to glibc; the VM only uses `overflow_arg_area`.

```
va_start(ap, last)   __builtin_va_start(ap, last)   an expression form: vastart %ap
va_arg(ap, T)        __builtin_va_arg(ap, T)        an expression form: %d = vaarg %ap, d of T
va_end(ap)           ((void) 0)
va_copy(d, s)        ((d)[0] = (s)[0])
```

- **Parser.** Both builtins are primary expressions by name, since
  `va_arg` takes a type; `Expr.VaStart`, `Expr.VaArg`.
- **Typer.** `va_start` only in a function with `...`; `ap` decays to a
  pointer; `va_arg` of a scalar type, `long double` and aggregates not
  yet.
- **VM.** The same list on every target, its next-argument pointer at
  offset 8. A variadic call spills the unnamed arguments in order, 8 bytes
  each, into the callee's frame; `vastart` writes that address into the
  list, `vaarg` reads at it and advances. The printf family gets
  `vprintf`, `vfprintf`, `vsprintf`, `vsnprintf`, reading the list per
  the format.
- **Native.** A variadic function's prologue spills the six integer and
  eight floating argument registers into a 176-byte save area;
  `vastart` fills the four fields from what the named parameters used;
  `vaarg` is the SysV sequence: from the save area while the offset is
  below its limit, else from the overflow area.

Steps:

1. [x] The header, the parser, the typer, the TAC.
2. [x] The VM, the `v*printf` builtins.
3. [x] The emitter; the corpus program on all three paths.

Deferred: `va_arg` of a struct or `long double`; `va_start(ap)` with one argument (C23).

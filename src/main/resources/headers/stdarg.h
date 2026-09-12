/* stdarg.h: variable arguments (C2y 7.16). The list is the SysV x86-64 one, so a va_list handed to the C library means the same thing there. */
#ifndef _STDARG_H
#define _STDARG_H

typedef struct __va_list_tag {
    unsigned int gp_offset;
    unsigned int fp_offset;
    void *overflow_arg_area;
    void *reg_save_area;
} va_list[1];

#define va_start(ap, last) __builtin_va_start(ap, last)
#define va_arg(ap, type) __builtin_va_arg(ap, type)
#define va_end(ap) ((void) 0)
#define va_copy(dst, src) ((dst)[0] = (src)[0])

#endif

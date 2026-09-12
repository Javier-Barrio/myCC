/* stdarg.h: variable arguments (C2y 7.16). The list is the SysV x86-64 one, so a va_list handed to the C library means the same thing there. The C library's headers ask for __gnuc_va_list alone with __need___va_list, as they do of gcc. */
#ifndef __GNUC_VA_LIST
#define __GNUC_VA_LIST
typedef struct __va_list_tag {
    unsigned int gp_offset;
    unsigned int fp_offset;
    void *overflow_arg_area;
    void *reg_save_area;
} __gnuc_va_list[1];
#endif

#ifdef __need___va_list
#undef __need___va_list
#else

#ifndef _STDARG_H
#define _STDARG_H

typedef __gnuc_va_list va_list;
#define _VA_LIST_DEFINED

#define va_start(ap, last) __builtin_va_start(ap, last)
#define va_arg(ap, type) __builtin_va_arg(ap, type)
#define va_end(ap) ((void) 0)
#define va_copy(dst, src) ((dst)[0] = (src)[0])

#endif
#endif

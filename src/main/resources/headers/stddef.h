/* stddef.h: common definitions (C2y 7.21). Target-dependent types are the types of expressions. */
#ifndef _STDDEF_H
#define _STDDEF_H

typedef typeof(sizeof(0)) size_t;
typedef typeof((char *)0 - (char *)0) ptrdiff_t;
typedef typeof(L'\0') wchar_t;
typedef typeof(nullptr) nullptr_t;
typedef long double max_align_t;

#define NULL ((void *)0)
#define offsetof(type, member) ((size_t)&((type *)0)->member)

#endif

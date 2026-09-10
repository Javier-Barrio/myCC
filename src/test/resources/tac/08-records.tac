target x86_64-sysv
type %anon.1 = { i32 @0, f32 @0 } size 4 align 4
type %anon.2 = { i16 @0, i16 @2 } size 4 align 2
type %P = { i8 @0, %anon.1 @4, %anon.2 @8 } size 12 align 4
type %B = { u32 @0, u32 @0, i32 @0, u8 @3 } size 4 align 4
declare @make() -> %P
define @f() -> void {
  %P %p
  %P %q
  ptr %pp
  %B %b
  %P %tmp9
  i64 %o
  u64 %s
  ptr %t0
  ptr %t1
  ptr %t2
  i32 %t3
  u32 %t4
  u32 %t5
  u32 %t6
  ptr %t7
  i32 %t8
  u8 %t9
  u8 %t10
  u8 %t11
  ptr %t12
  ptr %t13
  i32 %t14
  i8 %t15
  ptr %t16
  ptr %t17
  i32 %t18
  ptr %t19
  f64 %t20
  f32 %t21
  ptr %t22
  ptr %t23
  ptr %t24
  ptr %t25
  ptr %t26
  ptr %t27
  ptr %t28
  i16 %t29
  ptr %t30
  ptr %t31
  ptr %t32
  ptr %t33
  i32 %t34
  i32 %t35
  i32 %t36
  ptr %t37
  ptr %t38
  i32 %t39
  u32 %t40
  u32 %t41
  u32 %t42
  ptr %t43
  ptr %t44
  ptr %t45
  ptr %t46
  u32 %t47
  u32 %t48
  i32 %t49
  i32 %t50
  i32 %t51
  u32 %t52
  u32 %t53
  u32 %t54
  ptr %t55
  ptr %t56
  ptr %t57
  ptr %t58
  u32 %t59
  i32 %t60
  i32 %t61
  u32 %t62
  u32 %t63
  ptr %t64
  ptr %t65
  ptr %t66
  ptr %t67
  u32 %t68
  i32 %t69
  u8 %t70
  u8 %t71
  u8 %t72
  ptr %t73
  ptr %t74
  u64 %t75
  u64 %t76
  u64 %t77
  u64 %t78
  u64 %t79
.entry:
  %t0 = addrof %p
  mov.u64 %pp, %t0
  %t1 = addrof %b
  store.%B %t1, 0
  %t2 = wadd.u64 %t1, 0
  mov.s32 %t3, 3
  mov.u32 %t4, %t3
  %t5 = load.u32 %t2
  %t5 = and.u64 %t5, -241
  %t6 = and.u64 %t4, 15
  %t6 = shl.u64 %t6, 4
  %t5 = or.u64 %t5, %t6
  store.32 %t2, %t5
  %t7 = wadd.u64 %t1, 3
  mov.s32 %t8, 1
  %t9 = ne %t8, 0
  %t10 = load.u8 %t7
  %t10 = and.u64 %t10, -17
  %t11 = and.u64 %t9, 1
  %t11 = shl.u64 %t11, 4
  %t10 = or.u64 %t10, %t11
  store.8 %t7, %t10
  %t12 = addrof %p
  %t13 = wadd.u64 %t12, 0
  mov.s32 %t14, 1
  mov.s8 %t15, %t14
  store.8 %t13, %t15
  %t16 = addrof %p
  %t17 = wadd.u64 %t16, 4
  mov.s32 %t18, 2
  store.32 %t17, %t18
  %t19 = wadd.u64 %pp, 4
  mov.64 %t20, 1.5
  %t21 = fcvt.32 %t20
  store.f32 %t19, %t21
  %t22 = addrof %q
  %t23 = addrof %p
  store.%P %t22, %t23
  %t24 = addrof %p
  %t25 = wadd.u64 %t24, 8
  %t26 = wadd.u64 %t25, 2
  %t27 = wadd.u64 %pp, 8
  %t28 = wadd.u64 %t27, 0
  %t29 = load.s16 %t28
  store.16 %t26, %t29
  %t30 = addrof %p
  %t31 = wadd.u64 %t30, 4
  %t32 = addrof %tmp9
  call () -> %P @make() into %t32
  %t33 = wadd.u64 %t32, 4
  %t34 = load.s32 %t33
  mov.s32 %t35, 3
  %t36 = add.s32 %t34, %t35
  store.32 %t31, %t36
  %t37 = addrof %b
  %t38 = wadd.u64 %t37, 0
  mov.s32 %t39, 15
  mov.u32 %t40, %t39
  %t41 = load.u32 %t38
  %t41 = and.u64 %t41, -16
  %t42 = and.u64 %t40, 15
  %t42 = shl.u64 %t42, 0
  %t41 = or.u64 %t41, %t42
  store.32 %t38, %t41
  %t43 = addrof %b
  %t44 = wadd.u64 %t43, 0
  %t45 = addrof %b
  %t46 = wadd.u64 %t45, 0
  %t47 = load.u32 %t46
  %t48 = lshr.u64 %t47, 0
  %t48 = and.u64 %t48, 15
  mov.s32 %t49, %t48
  mov.s32 %t50, 1
  %t51 = add.s32 %t49, %t50
  mov.u32 %t52, %t51
  %t53 = load.u32 %t44
  %t53 = and.u64 %t53, -241
  %t54 = and.u64 %t52, 15
  %t54 = shl.u64 %t54, 4
  %t53 = or.u64 %t53, %t54
  store.32 %t44, %t53
  %t55 = addrof %b
  %t56 = wadd.u64 %t55, 0
  %t57 = addrof %b
  %t58 = wadd.u64 %t57, 0
  %t59 = load.u32 %t58
  %t60 = shl.s64 %t59, 36
  %t60 = ashr.s64 %t60, 44
  %t61 = sub.s32 0, %t60
  %t62 = load.u32 %t56
  %t62 = and.u64 %t62, -268435201
  %t63 = and.u64 %t61, 1048575
  %t63 = shl.u64 %t63, 8
  %t62 = or.u64 %t62, %t63
  store.32 %t56, %t62
  %t64 = addrof %b
  %t65 = wadd.u64 %t64, 3
  %t66 = addrof %b
  %t67 = wadd.u64 %t66, 0
  %t68 = load.u32 %t67
  %t69 = shl.s64 %t68, 36
  %t69 = ashr.s64 %t69, 44
  %t70 = ne %t69, 0
  %t71 = load.u8 %t65
  %t71 = and.u64 %t71, -17
  %t72 = and.u64 %t70, 1
  %t72 = shl.u64 %t72, 4
  %t71 = or.u64 %t71, %t72
  store.8 %t65, %t71
  mov.u64 %t73, 0
  %t74 = wadd.u64 %t73, 8
  mov.s64 %o, %t74
  mov.u64 %t75, 12
  mov.u64 %t76, 4
  %t77 = wadd.u64 %t75, %t76
  mov.u64 %t78, 4
  %t79 = wadd.u64 %t77, %t78
  mov.u64 %s, %t79
  ret
}

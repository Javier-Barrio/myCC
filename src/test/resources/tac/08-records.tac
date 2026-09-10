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
  i32 %t2
  u32 %t3
  u32 %t4
  u32 %t5
  ptr %t6
  i32 %t7
  u8 %t8
  u8 %t9
  u8 %t10
  ptr %t11
  i32 %t12
  i8 %t13
  ptr %t14
  ptr %t15
  i32 %t16
  ptr %t17
  f64 %t18
  f32 %t19
  ptr %t20
  ptr %t21
  ptr %t22
  ptr %t23
  ptr %t24
  ptr %t25
  i16 %t26
  ptr %t27
  ptr %t28
  ptr %t29
  ptr %t30
  i32 %t31
  i32 %t32
  i32 %t33
  ptr %t34
  i32 %t35
  u32 %t36
  u32 %t37
  u32 %t38
  ptr %t39
  ptr %t40
  u32 %t41
  u32 %t42
  i32 %t43
  i32 %t44
  i32 %t45
  u32 %t46
  u32 %t47
  u32 %t48
  ptr %t49
  ptr %t50
  u32 %t51
  i32 %t52
  i32 %t53
  u32 %t54
  u32 %t55
  ptr %t56
  ptr %t57
  ptr %t58
  u32 %t59
  i32 %t60
  u8 %t61
  u8 %t62
  u8 %t63
  ptr %t64
  ptr %t65
  u64 %t66
  u64 %t67
  u64 %t68
  u64 %t69
  u64 %t70
.entry:
  %t0 = addrof %p
  mov.u64 %pp, %t0
  %t1 = addrof %b
  zero %B %t1
  mov.s32 %t2, 3
  mov.u32 %t3, %t2
  %t4 = load.u32 %t1
  %t4 = and %t4, -241
  %t5 = and %t3, 15
  %t5 = shl %t5, 4
  %t4 = or %t4, %t5
  store.32 %t1, %t4
  %t6 = wadd %t1, 3
  mov.s32 %t7, 1
  %t8 = ne %t7, 0
  %t9 = load.u8 %t6
  %t9 = and %t9, -17
  %t10 = and %t8, 1
  %t10 = shl %t10, 4
  %t9 = or %t9, %t10
  store.8 %t6, %t9
  %t11 = addrof %p
  mov.s32 %t12, 1
  mov.s8 %t13, %t12
  store.8 %t11, %t13
  %t14 = addrof %p
  %t15 = wadd %t14, 4
  mov.s32 %t16, 2
  store.32 %t15, %t16
  %t17 = wadd %pp, 4
  mov.64 %t18, 1.5
  %t19 = fcvt.32 %t18
  store.f32 %t17, %t19
  %t20 = addrof %q
  %t21 = addrof %p
  copy %P %t20, %t21
  %t22 = addrof %p
  %t23 = wadd %t22, 8
  %t24 = wadd %t23, 2
  %t25 = wadd %pp, 8
  %t26 = load.s16 %t25
  store.16 %t24, %t26
  %t27 = addrof %p
  %t28 = wadd %t27, 4
  %t29 = addrof %tmp9
  call () -> %P @make() into %t29
  %t30 = wadd %t29, 4
  %t31 = load.s32 %t30
  mov.s32 %t32, 3
  %t33 = add.s32 %t31, %t32
  store.32 %t28, %t33
  %t34 = addrof %b
  mov.s32 %t35, 15
  mov.u32 %t36, %t35
  %t37 = load.u32 %t34
  %t37 = and %t37, -16
  %t38 = and %t36, 15
  %t37 = or %t37, %t38
  store.32 %t34, %t37
  %t39 = addrof %b
  %t40 = addrof %b
  %t41 = load.u32 %t40
  %t42 = and %t41, 15
  mov.s32 %t43, %t42
  mov.s32 %t44, 1
  %t45 = add.s32 %t43, %t44
  mov.u32 %t46, %t45
  %t47 = load.u32 %t39
  %t47 = and %t47, -241
  %t48 = and %t46, 15
  %t48 = shl %t48, 4
  %t47 = or %t47, %t48
  store.32 %t39, %t47
  %t49 = addrof %b
  %t50 = addrof %b
  %t51 = load.u32 %t50
  %t52 = shl %t51, 36
  %t52 = ashr %t52, 44
  %t53 = sub.s32 0, %t52
  %t54 = load.u32 %t49
  %t54 = and %t54, -268435201
  %t55 = and %t53, 1048575
  %t55 = shl %t55, 8
  %t54 = or %t54, %t55
  store.32 %t49, %t54
  %t56 = addrof %b
  %t57 = wadd %t56, 3
  %t58 = addrof %b
  %t59 = load.u32 %t58
  %t60 = shl %t59, 36
  %t60 = ashr %t60, 44
  %t61 = ne %t60, 0
  %t62 = load.u8 %t57
  %t62 = and %t62, -17
  %t63 = and %t61, 1
  %t63 = shl %t63, 4
  %t62 = or %t62, %t63
  store.8 %t57, %t62
  mov.u64 %t64, 0
  %t65 = wadd %t64, 8
  mov.s64 %o, %t65
  mov.u64 %t66, 12
  mov.u64 %t67, 4
  %t68 = wadd %t66, %t67
  mov.u64 %t69, 4
  %t70 = wadd %t68, %t69
  mov.u64 %s, %t70
  ret
}

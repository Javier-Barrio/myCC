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
  ptr %t5
  i32 %t6
  u8 %t7
  u8 %t8
  u8 %t9
  ptr %t10
  i32 %t11
  i8 %t12
  ptr %t13
  ptr %t14
  i32 %t15
  ptr %t16
  f64 %t17
  f32 %t18
  ptr %t19
  ptr %t20
  ptr %t21
  ptr %t22
  ptr %t23
  ptr %t24
  i16 %t25
  ptr %t26
  ptr %t27
  ptr %t28
  ptr %t29
  i32 %t30
  i32 %t31
  i32 %t32
  ptr %t33
  i32 %t34
  u32 %t35
  u32 %t36
  ptr %t37
  ptr %t38
  u32 %t39
  u32 %t40
  i32 %t41
  i32 %t42
  u32 %t43
  u32 %t44
  ptr %t45
  ptr %t46
  u32 %t47
  i32 %t48
  i32 %t49
  u32 %t50
  u32 %t51
  ptr %t52
  ptr %t53
  ptr %t54
  u32 %t55
  i32 %t56
  u8 %t57
  u8 %t58
  u8 %t59
  ptr %t60
  ptr %t61
  u64 %t62
  u64 %t63
  u64 %t64
  u64 %t65
  u64 %t66
.entry:
  %t0 = addrof %p
  mov %pp, %t0
  %t1 = addrof %b
  zero %B %t1
  mov %t2, 3
  %t3 = load.u32 %t1
  %t3 = and %t3, -241
  %t4 = and %t2, 15
  %t4 = shl %t4, 4
  %t3 = or %t3, %t4
  store.32 %t1, %t3
  %t5 = wadd %t1, 3
  mov %t6, 1
  %t7 = ne %t6, 0
  %t8 = load.u8 %t5
  %t8 = and %t8, -17
  %t9 = and %t7, 1
  %t9 = shl %t9, 4
  %t8 = or %t8, %t9
  store.8 %t5, %t8
  %t10 = addrof %p
  mov %t11, 1
  mov %t12, %t11
  %t12 = shl %t12, 24
  %t12 = ashr %t12, 24
  store.8 %t10, %t12
  %t13 = addrof %p
  %t14 = wadd %t13, 4
  mov %t15, 2
  store.32 %t14, %t15
  %t16 = wadd %pp, 4
  mov %t17, 1.5
  %t18 = fcvt %t17
  store.f32 %t16, %t18
  %t19 = addrof %q
  %t20 = addrof %p
  copy %P %t19, %t20
  %t21 = addrof %p
  %t22 = wadd %t21, 8
  %t23 = wadd %t22, 2
  %t24 = wadd %pp, 8
  %t25 = load.s16 %t24
  store.16 %t23, %t25
  %t26 = addrof %p
  %t27 = wadd %t26, 4
  %t28 = addrof %tmp9
  call () -> %P @make() into %t28
  %t29 = wadd %t28, 4
  %t30 = load.s32 %t29
  mov %t31, 3
  %t32 = add %t30, %t31
  store.32 %t27, %t32
  %t33 = addrof %b
  mov %t34, 15
  %t35 = load.u32 %t33
  %t35 = and %t35, -16
  %t36 = and %t34, 15
  %t35 = or %t35, %t36
  store.32 %t33, %t35
  %t37 = addrof %b
  %t38 = addrof %b
  %t39 = load.u32 %t38
  %t40 = and %t39, 15
  mov %t41, 1
  %t42 = add %t40, %t41
  %t43 = load.u32 %t37
  %t43 = and %t43, -241
  %t44 = and %t42, 15
  %t44 = shl %t44, 4
  %t43 = or %t43, %t44
  store.32 %t37, %t43
  %t45 = addrof %b
  %t46 = addrof %b
  %t47 = load.u32 %t46
  %t48 = shl %t47, 4
  %t48 = ashr %t48, 12
  %t49 = sub 0, %t48
  %t50 = load.u32 %t45
  %t50 = and %t50, -268435201
  %t51 = and %t49, 1048575
  %t51 = shl %t51, 8
  %t50 = or %t50, %t51
  store.32 %t45, %t50
  %t52 = addrof %b
  %t53 = wadd %t52, 3
  %t54 = addrof %b
  %t55 = load.u32 %t54
  %t56 = shl %t55, 4
  %t56 = ashr %t56, 12
  %t57 = ne %t56, 0
  %t58 = load.u8 %t53
  %t58 = and %t58, -17
  %t59 = and %t57, 1
  %t59 = shl %t59, 4
  %t58 = or %t58, %t59
  store.8 %t53, %t58
  mov %t60, 0
  %t61 = wadd %t60, 8
  mov %o, %t61
  mov %t62, 12
  mov %t63, 4
  %t64 = wadd %t62, %t63
  mov %t65, 4
  %t66 = wadd %t64, %t65
  mov %s, %t66
  ret
}

target x86_64-sysv
define @f() -> void {
  i32 %i
  i32 %j
  i8 %c
  ptr %p
  f64 %d
  u32 %u
  [3 x i32] %a
  i32 %t0
  i32 %t1
  i8 %t2
  i32 %t3
  i32 %t4
  f64 %t5
  f64 %t6
  f64 %t7
  i8 %t8
  i64 %t9
  i64 %t10
  ptr %t11
  i32 %t12
  i64 %t13
  i64 %t14
  i64 %t15
  ptr %t16
  u32 %t17
  i32 %t18
  f64 %t19
  f64 %t20
  i32 %t21
  i32 %t22
  i32 %t23
  i32 %t24
  i32 %t25
  i32 %t26
  i32 %t27
  i64 %t28
  i64 %t29
  i64 %t30
  ptr %t31
  i32 %t32
  i32 %t33
  i32 %t34
  i32 %t35
  i32 %t36
  i32 %t37
  ptr %t38
  i32 %t39
  i64 %t40
  i64 %t41
  ptr %t42
  ptr %t43
  i32 %t44
  i32 %t45
  i32 %t46
  i64 %t47
  i64 %t48
  ptr %t49
  ptr %t50
  i64 %t51
  i64 %t52
  ptr %t53
  i32 %t54
  ptr %t55
  i64 %t56
  i64 %t57
  ptr %t58
  i32 %t59
  i32 %t60
.entry:
  mov %t0, 2
  mov %j, %t0
  mov %i, %t0
  mov %t1, 300
  mov %t2, %t1
  %t2 = shl %t2, 24
  %t2 = ashr %t2, 24
  mov %c, %t2
  mov %t3, %c
  %t4 = add %i, %t3
  mov %i, %t4
  %t5 = i2f %c
  mov %t6, 1.5
  %t7 = fsub %t5, %t6
  %t8 = f2i %t7
  %t8 = shl %t8, 24
  %t8 = ashr %t8, 24
  mov %c, %t8
  mov %t9, %i
  %t9 = shl %t9, 32
  %t9 = ashr %t9, 32
  %t10 = wmul %t9, 4
  %t11 = wadd %p, %t10
  mov %p, %t11
  mov %t12, 1
  mov %t13, %t12
  %t13 = shl %t13, 32
  %t13 = ashr %t13, 32
  %t14 = sub 0, %t13
  %t15 = wmul %t14, 4
  %t16 = wadd %p, %t15
  mov %p, %t16
  %t17 = shl %u, %i
  mov %u, %t17
  mov %t18, 2
  %t19 = i2f %t18
  %t20 = fmul %d, %t19
  mov %d, %t20
  mov %t21, 3
  %t22 = srem %i, %t21
  mov %i, %t22
  mov %t23, 1
  %t24 = add %i, %t23
  mov %i, %t24
  mov %t25, 1
  %t26 = add %i, %t25
  mov %i, %t26
  mov %t27, 1
  mov %t28, %t27
  %t28 = shl %t28, 32
  %t28 = ashr %t28, 32
  %t29 = sub 0, %t28
  %t30 = wmul %t29, 4
  %t31 = wadd %p, %t30
  mov %p, %t31
  mov %t32, %i
  mov %t33, 1
  %t34 = add %t32, %t33
  mov %i, %t34
  mov %j, %t32
  mov %t35, %i
  mov %t36, 1
  %t37 = sub %t35, %t36
  mov %i, %t37
  mov %j, %t35
  mov %t38, %p
  mov %t39, 1
  mov %t40, %t39
  %t40 = shl %t40, 32
  %t40 = ashr %t40, 32
  %t41 = wmul %t40, 4
  %t42 = wadd %t38, %t41
  mov %p, %t42
  mov %p, %t38
  %t43 = addrof %a
  mov %t44, %i
  mov %t45, 1
  %t46 = add %t44, %t45
  mov %i, %t46
  mov %t47, %t44
  %t47 = shl %t47, 32
  %t47 = ashr %t47, 32
  %t48 = wmul %t47, 4
  %t49 = wadd %t43, %t48
  store.32 %t49, %i
  %t50 = addrof %a
  mov %t51, %j
  %t51 = shl %t51, 32
  %t51 = ashr %t51, 32
  %t52 = wmul %t51, 4
  %t53 = wadd %t50, %t52
  %t54 = load.s32 %t53
  %t55 = addrof %a
  mov %t56, %j
  %t56 = shl %t56, 32
  %t56 = ashr %t56, 32
  %t57 = wmul %t56, 4
  %t58 = wadd %t55, %t57
  %t59 = load.s32 %t58
  %t60 = add %t54, %t59
  store.32 %t53, %t60
  ret
}

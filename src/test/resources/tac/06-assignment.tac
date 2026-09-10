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
  f64 %t4
  f64 %t5
  f64 %t6
  i8 %t7
  i64 %t8
  i64 %t9
  ptr %t10
  i32 %t11
  i64 %t12
  i64 %t13
  i64 %t14
  ptr %t15
  u32 %t16
  i32 %t17
  f64 %t18
  f64 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  i32 %t23
  i32 %t24
  i32 %t25
  i32 %t26
  i64 %t27
  i64 %t28
  i64 %t29
  ptr %t30
  i32 %t31
  i32 %t32
  i32 %t33
  i32 %t34
  i32 %t35
  i32 %t36
  ptr %t37
  i32 %t38
  i64 %t39
  i64 %t40
  ptr %t41
  ptr %t42
  i32 %t43
  i32 %t44
  i32 %t45
  i64 %t46
  i64 %t47
  ptr %t48
  ptr %t49
  i64 %t50
  i64 %t51
  ptr %t52
  i32 %t53
  ptr %t54
  i64 %t55
  i64 %t56
  ptr %t57
  i32 %t58
  i32 %t59
.entry:
  mov %t0, 2
  mov %j, %t0
  mov %i, %t0
  mov %t1, 300
  mov %t2, %t1
  %t2 = shl %t2, 24
  %t2 = ashr %t2, 24
  mov %c, %t2
  %t3 = add %i, %c
  mov %i, %t3
  %t4 = i2f %c
  mov %t5, 1.5
  %t6 = fsub %t4, %t5
  %t7 = f2i %t6
  %t7 = shl %t7, 24
  %t7 = ashr %t7, 24
  mov %c, %t7
  mov %t8, %i
  %t8 = shl %t8, 32
  %t8 = ashr %t8, 32
  %t9 = wmul %t8, 4
  %t10 = wadd %p, %t9
  mov %p, %t10
  mov %t11, 1
  mov %t12, %t11
  %t12 = shl %t12, 32
  %t12 = ashr %t12, 32
  %t13 = sub 0, %t12
  %t14 = wmul %t13, 4
  %t15 = wadd %p, %t14
  mov %p, %t15
  %t16 = shl %u, %i
  mov %u, %t16
  mov %t17, 2
  %t18 = i2f %t17
  %t19 = fmul %d, %t18
  mov %d, %t19
  mov %t20, 3
  %t21 = srem %i, %t20
  mov %i, %t21
  mov %t22, 1
  %t23 = add %i, %t22
  mov %i, %t23
  mov %t24, 1
  %t25 = add %i, %t24
  mov %i, %t25
  mov %t26, 1
  mov %t27, %t26
  %t27 = shl %t27, 32
  %t27 = ashr %t27, 32
  %t28 = sub 0, %t27
  %t29 = wmul %t28, 4
  %t30 = wadd %p, %t29
  mov %p, %t30
  mov %t31, %i
  mov %t32, 1
  %t33 = add %t31, %t32
  mov %i, %t33
  mov %j, %t31
  mov %t34, %i
  mov %t35, 1
  %t36 = sub %t34, %t35
  mov %i, %t36
  mov %j, %t34
  mov %t37, %p
  mov %t38, 1
  mov %t39, %t38
  %t39 = shl %t39, 32
  %t39 = ashr %t39, 32
  %t40 = wmul %t39, 4
  %t41 = wadd %t37, %t40
  mov %p, %t41
  mov %p, %t37
  %t42 = addrof %a
  mov %t43, %i
  mov %t44, 1
  %t45 = add %t43, %t44
  mov %i, %t45
  mov %t46, %t43
  %t46 = shl %t46, 32
  %t46 = ashr %t46, 32
  %t47 = wmul %t46, 4
  %t48 = wadd %t42, %t47
  store.32 %t48, %i
  %t49 = addrof %a
  mov %t50, %j
  %t50 = shl %t50, 32
  %t50 = ashr %t50, 32
  %t51 = wmul %t50, 4
  %t52 = wadd %t49, %t51
  %t53 = load.s32 %t52
  %t54 = addrof %a
  mov %t55, %j
  %t55 = shl %t55, 32
  %t55 = ashr %t55, 32
  %t56 = wmul %t55, 4
  %t57 = wadd %t54, %t56
  %t58 = load.s32 %t57
  %t59 = add %t53, %t58
  store.32 %t52, %t59
  ret
}

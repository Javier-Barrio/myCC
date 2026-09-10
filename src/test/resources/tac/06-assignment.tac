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
  ptr %t9
  i32 %t10
  i64 %t11
  i64 %t12
  ptr %t13
  u32 %t14
  i32 %t15
  f64 %t16
  f64 %t17
  i32 %t18
  i32 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  i32 %t23
  i32 %t24
  i64 %t25
  i64 %t26
  ptr %t27
  i32 %t28
  i32 %t29
  i32 %t30
  i32 %t31
  i32 %t32
  i32 %t33
  ptr %t34
  i32 %t35
  i64 %t36
  ptr %t37
  ptr %t38
  i32 %t39
  i32 %t40
  i32 %t41
  i64 %t42
  ptr %t43
  ptr %t44
  i64 %t45
  ptr %t46
  i32 %t47
  ptr %t48
  i64 %t49
  ptr %t50
  i32 %t51
  i32 %t52
.entry:
  mov.s32 %t0, 2
  mov.s32 %j, %t0
  mov.s32 %i, %t0
  mov.s32 %t1, 300
  mov.s8 %t2, %t1
  mov.s8 %c, %t2
  %t3 = add.s32 %i, %c
  mov.s32 %i, %t3
  %t4 = i2f.64 %c
  mov.64 %t5, 1.5
  %t6 = fsub.64 %t4, %t5
  %t7 = f2i.64 %t6
  mov.s8 %c, %t7
  %t8 = wmul.s64 %i, 4
  %t9 = wadd.u64 %p, %t8
  mov.u64 %p, %t9
  mov.s32 %t10, 1
  %t11 = sub.s64 0, %t10
  %t12 = wmul.s64 %t11, 4
  %t13 = wadd.u64 %p, %t12
  mov.u64 %p, %t13
  %t14 = shl.u32 %u, %i
  mov.u32 %u, %t14
  mov.s32 %t15, 2
  %t16 = i2f.64 %t15
  %t17 = fmul.64 %d, %t16
  mov.64 %d, %t17
  mov.s32 %t18, 3
  %t19 = srem.s32 %i, %t18
  mov.s32 %i, %t19
  mov.s32 %t20, 1
  %t21 = add.s32 %i, %t20
  mov.s32 %i, %t21
  mov.s32 %t22, 1
  %t23 = add.s32 %i, %t22
  mov.s32 %i, %t23
  mov.s32 %t24, 1
  %t25 = sub.s64 0, %t24
  %t26 = wmul.s64 %t25, 4
  %t27 = wadd.u64 %p, %t26
  mov.u64 %p, %t27
  mov.s32 %t28, %i
  mov.s32 %t29, 1
  %t30 = add.s32 %t28, %t29
  mov.s32 %i, %t30
  mov.s32 %j, %t28
  mov.s32 %t31, %i
  mov.s32 %t32, 1
  %t33 = sub.s32 %t31, %t32
  mov.s32 %i, %t33
  mov.s32 %j, %t31
  mov.u64 %t34, %p
  mov.s32 %t35, 1
  %t36 = wmul.s64 %t35, 4
  %t37 = wadd.u64 %t34, %t36
  mov.u64 %p, %t37
  mov.u64 %p, %t34
  %t38 = addrof %a
  mov.s32 %t39, %i
  mov.s32 %t40, 1
  %t41 = add.s32 %t39, %t40
  mov.s32 %i, %t41
  %t42 = wmul.s64 %t39, 4
  %t43 = wadd.u64 %t38, %t42
  store.32 %t43, %i
  %t44 = addrof %a
  %t45 = wmul.s64 %j, 4
  %t46 = wadd.u64 %t44, %t45
  %t47 = load.s32 %t46
  %t48 = addrof %a
  %t49 = wmul.s64 %j, 4
  %t50 = wadd.u64 %t48, %t49
  %t51 = load.s32 %t50
  %t52 = add.s32 %t47, %t51
  store.32 %t46, %t52
  ret
}

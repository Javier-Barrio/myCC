target x86_64-sysv
global @arr : [4 x i32] align 4
global @m : [2 x [3 x i32]] align 4
declare @f(i32) -> i32
define @g() -> void {
  ptr %p
  ptr %pp
  i64 %n
  ptr %fp
  ptr %t0
  ptr %t1
  ptr %t2
  i32 %t3
  ptr %t4
  i32 %t5
  i64 %t6
  i64 %t7
  ptr %t8
  ptr %t9
  i64 %t10
  ptr %t11
  i32 %t12
  i64 %t13
  i64 %t14
  i64 %t15
  ptr %t16
  ptr %t17
  i64 %t18
  ptr %t19
  i32 %t20
  i64 %t21
  i64 %t22
  ptr %t23
  ptr %t24
  i32 %t25
  i64 %t26
  i64 %t27
  ptr %t28
  i32 %t29
  ptr %t30
  i32 %t31
  i64 %t32
  i64 %t33
  ptr %t34
  i32 %t35
  i64 %t36
  i64 %t37
  ptr %t38
  ptr %t39
  i32 %t40
  ptr %t41
  i32 %t42
  i64 %t43
  i64 %t44
  ptr %t45
  i32 %t46
  i32 %t47
  i32 %t48
  i32 %t49
  i32 %t50
  i32 %t51
  u64 %t52
  u64 %t53
  u64 %t54
.entry:
  %t0 = addrof @arr
  mov %p, %t0
  %t1 = addrof %p
  mov %pp, %t1
  %t2 = addrof @f
  mov %fp, %t2
  mov %t3, 1
  store.32 %p, %t3
  %t4 = addrof @arr
  mov %t5, 2
  mov %t6, %t5
  %t6 = shl %t6, 32
  %t6 = ashr %t6, 32
  %t7 = wmul %t6, 4
  %t8 = wadd %t4, %t7
  mov %p, %t8
  %t9 = addrof @arr
  %t10 = wmul %n, 4
  %t11 = wadd %t9, %t10
  mov %p, %t11
  mov %t12, 2
  mov %t13, %t12
  %t13 = shl %t13, 32
  %t13 = ashr %t13, 32
  %t14 = sub 0, %t13
  %t15 = wmul %t14, 4
  %t16 = wadd %p, %t15
  mov %p, %t16
  %t17 = addrof @arr
  %t18 = wsub %p, %t17
  %t18 = sdiv %t18, 4
  mov %n, %t18
  %t19 = addrof @arr
  mov %t20, 1
  mov %t21, %t20
  %t21 = shl %t21, 32
  %t21 = ashr %t21, 32
  %t22 = wmul %t21, 4
  %t23 = wadd %t19, %t22
  %t24 = addrof @arr
  mov %t25, 3
  mov %t26, %t25
  %t26 = shl %t26, 32
  %t26 = ashr %t26, 32
  %t27 = wmul %t26, 4
  %t28 = wadd %t24, %t27
  %t29 = load.s32 %t28
  store.32 %t23, %t29
  %t30 = addrof @m
  mov %t31, 1
  mov %t32, %t31
  %t32 = shl %t32, 32
  %t32 = ashr %t32, 32
  %t33 = wmul %t32, 12
  %t34 = wadd %t30, %t33
  mov %t35, 2
  mov %t36, %t35
  %t36 = shl %t36, 32
  %t36 = ashr %t36, 32
  %t37 = wmul %t36, 4
  %t38 = wadd %t34, %t37
  %t39 = load.u64 %pp
  %t40 = load.s32 %t39
  store.32 %t38, %t40
  %t41 = addrof @m
  mov %t42, 1
  mov %t43, %t42
  %t43 = shl %t43, 32
  %t43 = ashr %t43, 32
  %t44 = wmul %t43, 12
  %t45 = wadd %t41, %t44
  mov %p, %t45
  mov %t46, 3
  %t47 = icall (i32) -> i32 %fp(%t46)
  mov %t48, 4
  %t49 = icall (i32) -> i32 %fp(%t48)
  mov %t50, 5
  %t51 = icall (i32) -> i32 %fp(%t50)
  mov %p, %p
  mov %t52, 16
  mov %t53, 4
  %t54 = udiv %t52, %t53
  mov %n, %t54
  ret
}

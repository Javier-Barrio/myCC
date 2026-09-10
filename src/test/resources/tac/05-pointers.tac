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
  ptr %t7
  ptr %t8
  i64 %t9
  ptr %t10
  i32 %t11
  i64 %t12
  i64 %t13
  ptr %t14
  ptr %t15
  i64 %t16
  ptr %t17
  i32 %t18
  i64 %t19
  ptr %t20
  ptr %t21
  i32 %t22
  i64 %t23
  ptr %t24
  i32 %t25
  ptr %t26
  i32 %t27
  i64 %t28
  ptr %t29
  i32 %t30
  i64 %t31
  ptr %t32
  ptr %t33
  i32 %t34
  ptr %t35
  i32 %t36
  i64 %t37
  ptr %t38
  i32 %t39
  i32 %t40
  i32 %t41
  i32 %t42
  i32 %t43
  i32 %t44
  u64 %t45
  u64 %t46
  u64 %t47
.entry:
  %t0 = addrof @arr
  mov.u64 %p, %t0
  %t1 = addrof %p
  mov.u64 %pp, %t1
  %t2 = addrof @f
  mov.u64 %fp, %t2
  mov.s32 %t3, 1
  store.32 %p, %t3
  %t4 = addrof @arr
  mov.s32 %t5, 2
  %t6 = wmul %t5, 4
  %t7 = wadd %t4, %t6
  mov.u64 %p, %t7
  %t8 = addrof @arr
  %t9 = wmul %n, 4
  %t10 = wadd %t8, %t9
  mov.u64 %p, %t10
  mov.s32 %t11, 2
  %t12 = sub 0, %t11
  %t13 = wmul %t12, 4
  %t14 = wadd %p, %t13
  mov.u64 %p, %t14
  %t15 = addrof @arr
  %t16 = wsub %p, %t15
  %t16 = sdiv %t16, 4
  mov.s64 %n, %t16
  %t17 = addrof @arr
  mov.s32 %t18, 1
  %t19 = wmul %t18, 4
  %t20 = wadd %t17, %t19
  %t21 = addrof @arr
  mov.s32 %t22, 3
  %t23 = wmul %t22, 4
  %t24 = wadd %t21, %t23
  %t25 = load.s32 %t24
  store.32 %t20, %t25
  %t26 = addrof @m
  mov.s32 %t27, 1
  %t28 = wmul %t27, 12
  %t29 = wadd %t26, %t28
  mov.s32 %t30, 2
  %t31 = wmul %t30, 4
  %t32 = wadd %t29, %t31
  %t33 = load.u64 %pp
  %t34 = load.s32 %t33
  store.32 %t32, %t34
  %t35 = addrof @m
  mov.s32 %t36, 1
  %t37 = wmul %t36, 12
  %t38 = wadd %t35, %t37
  mov.u64 %p, %t38
  mov.s32 %t39, 3
  %t40 = icall (i32) -> i32 %fp(%t39)
  mov.s32 %t41, 4
  %t42 = icall (i32) -> i32 %fp(%t41)
  mov.s32 %t43, 5
  %t44 = icall (i32) -> i32 %fp(%t43)
  mov.u64 %p, %p
  mov.u64 %t45, 16
  mov.u64 %t46, 4
  %t47 = udiv %t45, %t46
  mov.s64 %n, %t47
  ret
}

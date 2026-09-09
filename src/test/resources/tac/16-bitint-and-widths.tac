target x86_64-sysv
global @b7 : i8 align 1
global @u3 : u8 align 1 = { 0 : u8 5 }
global @b40 : i64 align 8
define @f(i32 %i, u32 %u, i64 %l) -> void {
  ptr %t0
  ptr %t1
  i8 %t2
  ptr %t3
  i8 %t4
  i8 %t5
  ptr %t6
  i8 %t7
  i32 %t8
  i32 %t9
  ptr %t10
  ptr %t11
  i64 %t12
  i64 %t13
  i64 %t14
  ptr %t15
  i64 %t16
  i64 %t17
  ptr %t18
  ptr %t19
  u8 %t20
  i32 %t21
  u8 %t22
  ptr %t23
  ptr %t24
  i8 %t25
  i8 %t26
.entry:
  %t0 = addrof @b7
  %t1 = addrof @b7
  %t2 = load.s8 %t1
  %t3 = addrof @b7
  %t4 = load.s8 %t3
  %t5 = add %t2, %t4
  %t5 = shl %t5, 25
  %t5 = ashr %t5, 25
  store.8 %t0, %t5
  %t6 = addrof @b7
  %t7 = load.s8 %t6
  mov %t8, %t7
  %t9 = add %t8, %i
  mov %i, %t9
  %t10 = addrof @b40
  %t11 = addrof @b40
  %t12 = load.s64 %t11
  mov %t13, %u
  %t14 = add %t12, %t13
  %t14 = shl %t14, 24
  %t14 = ashr %t14, 24
  store.64 %t10, %t14
  %t15 = addrof @b40
  %t16 = load.s64 %t15
  %t17 = mul %t16, %l
  mov %l, %t17
  %t18 = addrof @u3
  %t19 = addrof @u3
  %t20 = load.u8 %t19
  mov %t21, 1
  %t22 = shl %t20, %t21
  %t22 = and %t22, 7
  store.8 %t18, %t22
  %t23 = addrof @b7
  %t24 = addrof @b7
  %t25 = load.s8 %t24
  %t26 = sub 0, %t25
  %t26 = shl %t26, 25
  %t26 = ashr %t26, 25
  store.8 %t23, %t26
  ret
}

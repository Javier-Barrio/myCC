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
  ptr %t9
  ptr %t10
  i64 %t11
  i64 %t12
  i64 %t13
  ptr %t14
  i64 %t15
  i64 %t16
  ptr %t17
  ptr %t18
  u8 %t19
  i32 %t20
  u8 %t21
  ptr %t22
  ptr %t23
  i8 %t24
  i8 %t25
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
  %t8 = add %t7, %i
  mov %i, %t8
  %t9 = addrof @b40
  %t10 = addrof @b40
  %t11 = load.s64 %t10
  mov %t12, %u
  %t13 = add %t11, %t12
  %t13 = shl %t13, 24
  %t13 = ashr %t13, 24
  store.64 %t9, %t13
  %t14 = addrof @b40
  %t15 = load.s64 %t14
  %t16 = mul %t15, %l
  mov %l, %t16
  %t17 = addrof @u3
  %t18 = addrof @u3
  %t19 = load.u8 %t18
  mov %t20, 1
  %t21 = shl %t19, %t20
  %t21 = and %t21, 7
  store.8 %t17, %t21
  %t22 = addrof @b7
  %t23 = addrof @b7
  %t24 = load.s8 %t23
  %t25 = sub 0, %t24
  %t25 = shl %t25, 25
  %t25 = ashr %t25, 25
  store.8 %t22, %t25
  ret
}

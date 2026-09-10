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
  ptr %t13
  i64 %t14
  i64 %t15
  ptr %t16
  ptr %t17
  u8 %t18
  i32 %t19
  u8 %t20
  ptr %t21
  ptr %t22
  i8 %t23
  i8 %t24
.entry:
  %t0 = addrof @b7
  %t1 = addrof @b7
  %t2 = load.s8 %t1
  %t3 = addrof @b7
  %t4 = load.s8 %t3
  %t5 = add.s8 %t2, %t4
  %t5 = shl %t5, 57
  %t5 = ashr %t5, 57
  store.8 %t0, %t5
  %t6 = addrof @b7
  %t7 = load.s8 %t6
  %t8 = add.s32 %t7, %i
  mov %i, %t8
  %t9 = addrof @b40
  %t10 = addrof @b40
  %t11 = load.s64 %t10
  %t12 = add %t11, %u
  %t12 = shl %t12, 24
  %t12 = ashr %t12, 24
  store.64 %t9, %t12
  %t13 = addrof @b40
  %t14 = load.s64 %t13
  %t15 = mul %t14, %l
  mov %l, %t15
  %t16 = addrof @u3
  %t17 = addrof @u3
  %t18 = load.u8 %t17
  mov %t19, 1
  %t20 = shl.u8 %t18, %t19
  %t20 = and %t20, 7
  store.8 %t16, %t20
  %t21 = addrof @b7
  %t22 = addrof @b7
  %t23 = load.s8 %t22
  %t24 = sub.s8 0, %t23
  %t24 = shl %t24, 57
  %t24 = ashr %t24, 57
  store.8 %t21, %t24
  ret
}

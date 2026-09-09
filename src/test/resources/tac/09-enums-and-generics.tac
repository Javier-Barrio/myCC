target x86_64-sysv
global @c : i32 align 4 = { 0 : i32 6 }
global @s : u8 align 1 = { 0 : u8 200 }
global @which : i32 align 4 = { 0 : i32 2 }
global @plain : i32 align 4
define @f() -> void {
  i32 %i
  f64 %d
  ptr %p
  u64 %z
  i32 %t0
  i32 %t1
  i32 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  i32 %t6
  i32 %t7
  i8 %t8
  i32 %t9
  i32 %t10
  i64 %t11
  i32 %t12
  i32 %t13
  i64 %t14
  f64 %t15
  u64 %t16
  u64 %t17
  u64 %t18
.entry:
  mov %t0, 1
  mov %t1, 3
  %t2 = add %t0, %t1
  mov %t3, 2
  %t4 = add %t2, %t3
  mov %t5, 0
  %t6 = add %t4, %t5
  mov %i, %t6
  %t7 = f2i %d
  mov %t8, %i
  %t8 = shl %t8, 24
  %t8 = ashr %t8, 24
  mov %t9, %t8
  %t10 = add %t7, %t9
  mov %t11, %p
  mov %t12, %t11
  %t13 = add %t10, %t12
  mov %i, %t13
  mov %t14, 1099511627776
  %t15 = i2f %t14
  mov %d, %t15
  mov %t16, 8
  mov %t17, 8
  %t18 = wadd %t16, %t17
  mov %z, %t18
  ret
}

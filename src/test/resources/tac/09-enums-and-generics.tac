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
  i32 %t11
  u64 %t12
  f64 %t13
  u64 %t14
  u64 %t15
  u64 %t16
.entry:
  mov.s32 %t0, 1
  mov.s32 %t1, 3
  %t2 = add.s32 %t0, %t1
  mov.s32 %t3, 2
  %t4 = add.s32 %t2, %t3
  mov.s32 %t5, 0
  %t6 = add.s32 %t4, %t5
  mov.s32 %i, %t6
  %t7 = f2i.64 %d
  mov.s8 %t8, %i
  %t9 = add.s32 %t7, %t8
  mov.s32 %t10, %p
  %t11 = add.s32 %t9, %t10
  mov.s32 %i, %t11
  mov.u64 %t12, 1099511627776
  %t13 = u2f.64 %t12
  mov.64 %d, %t13
  mov.u64 %t14, 8
  mov.u64 %t15, 8
  %t16 = wadd.u64 %t14, %t15
  mov.u64 %z, %t16
  ret
}

target x86_64-sysv
define @f() -> void {
  i8 %c
  i16 %s
  i32 %i
  i64 %l
  f32 %fl
  f64 %d
  [2 x i32] %a
  ptr %p
  ptr %v
  u8 %b
  ptr %fp
  i32 %t0
  i8 %t1
  f64 %t2
  i32 %t3
  f64 %t4
  f32 %t5
  u8 %t6
  ptr %t7
  i64 %t8
  ptr %t9
  ptr %t10
  ptr %t11
  i32 %t12
  i32 %t13
  i32 %t14
  i16 %t15
.entry:
  mov %t0, %c
  mov %i, %t0
  mov %t1, %i
  %t1 = shl %t1, 24
  %t1 = ashr %t1, 24
  mov %c, %t1
  %t2 = i2f %i
  mov %d, %t2
  %t3 = f2i %d
  mov %i, %t3
  %t4 = fcvt %fl
  mov %d, %t4
  %t5 = fcvt %d
  mov %fl, %t5
  %t6 = ne %p, 0
  mov %b, %t6
  mov %v, %p
  mov %t7, %l
  mov %p, %t7
  mov %t8, %p
  mov %l, %t8
  mov %t9, 0
  mov %p, %t9
  %t10 = addrof %a
  mov %p, %t10
  %t11 = addrof @f
  mov %fp, %t11
  mov %t12, %c
  mov %t13, %s
  %t14 = add %t12, %t13
  mov %t15, %t14
  %t15 = shl %t15, 16
  %t15 = ashr %t15, 16
  mov %s, %t15
  ret
}

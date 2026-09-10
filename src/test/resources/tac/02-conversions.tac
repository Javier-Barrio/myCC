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
  i8 %t0
  f64 %t1
  i32 %t2
  f64 %t3
  f32 %t4
  u8 %t5
  ptr %t6
  ptr %t7
  ptr %t8
  i32 %t9
  i16 %t10
.entry:
  mov %i, %c
  mov %t0, %i
  %t0 = shl %t0, 24
  %t0 = ashr %t0, 24
  mov %c, %t0
  %t1 = i2f %i
  mov %d, %t1
  %t2 = f2i %d
  mov %i, %t2
  %t3 = fcvt %fl
  mov %d, %t3
  %t4 = fcvt %d
  mov %fl, %t4
  %t5 = ne %p, 0
  mov %b, %t5
  mov %v, %p
  mov %p, %l
  mov %l, %p
  mov %t6, 0
  mov %p, %t6
  %t7 = addrof %a
  mov %p, %t7
  %t8 = addrof @f
  mov %fp, %t8
  %t9 = add %c, %s
  mov %t10, %t9
  %t10 = shl %t10, 16
  %t10 = ashr %t10, 16
  mov %s, %t10
  ret
}

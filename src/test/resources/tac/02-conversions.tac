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
  f32 %t3
  u8 %t4
  ptr %t5
  ptr %t6
  ptr %t7
  i32 %t8
  i16 %t9
.entry:
  mov %i, %c
  mov.s8 %t0, %i
  mov %c, %t0
  %t1 = i2f.64 %i
  mov %d, %t1
  %t2 = f2i.64 %d
  mov %i, %t2
  mov %d, %fl
  %t3 = fcvt.32 %d
  mov %fl, %t3
  %t4 = ne %p, 0
  mov %b, %t4
  mov %v, %p
  mov %p, %l
  mov %l, %p
  mov %t5, 0
  mov %p, %t5
  %t6 = addrof %a
  mov %p, %t6
  %t7 = addrof @f
  mov %fp, %t7
  %t8 = add.s32 %c, %s
  mov.s16 %t9, %t8
  mov %s, %t9
  ret
}

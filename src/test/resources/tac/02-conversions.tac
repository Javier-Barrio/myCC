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
  mov.s32 %i, %c
  mov.s8 %t0, %i
  mov.s8 %c, %t0
  %t1 = i2f.64 %i
  mov.64 %d, %t1
  %t2 = f2i.64 %d
  mov.s32 %i, %t2
  mov.64 %d, %fl
  mov.32 %t3, %d
  mov.32 %fl, %t3
  %t4 = ne %p, 0
  mov.u8 %b, %t4
  mov.u64 %v, %p
  mov.u64 %p, %l
  mov.s64 %l, %p
  mov.u64 %t5, 0
  mov.u64 %p, %t5
  %t6 = addrof %a
  mov.u64 %p, %t6
  %t7 = addrof @f
  mov.u64 %fp, %t7
  %t8 = add.s32 %c, %s
  mov.s16 %t9, %t8
  mov.s16 %s, %t9
  ret
}

target x86_64-sysv
define @f() -> void {
  i8 %c
  u8 %uc
  i16 %s
  i32 %i
  u32 %u
  i64 %l
  u64 %ul
  f32 %fl
  f64 %d
  i32 %t0
  u32 %t1
  u32 %t2
  i64 %t3
  u64 %t4
  i32 %t5
  u32 %t6
  u32 %t7
  i32 %t8
  i64 %t9
  i32 %t10
  i32 %t11
  i32 %t12
  u64 %t13
  f32 %t14
  f32 %t15
  f64 %t16
  i32 %t17
  u32 %t18
  i32 %t19
  i32 %t20
.entry:
  %t0 = add.s32 %c, %s
  mov %i, %t0
  mov.u32 %t1, %i
  %t2 = wsub.u32 %t1, %u
  mov %u, %t2
  %t3 = mul %l, %i
  mov %l, %t3
  %t4 = udiv %l, %ul
  mov %ul, %t4
  %t5 = srem.s32 %i, %c
  mov %i, %t5
  mov.u32 %t6, %i
  %t7 = and.u32 %u, %t6
  mov %u, %t7
  mov %t8, 1
  %t9 = or %l, %t8
  mov %l, %t9
  %t10 = xor.s32 %uc, %c
  mov %i, %t10
  %t11 = shl.s32 %c, %l
  mov %i, %t11
  mov %t12, 3
  %t13 = lshr %ul, %t12
  mov %ul, %t13
  %t14 = i2f.32 %i
  %t15 = fadd.32 %fl, %t14
  mov %d, %t15
  %t16 = fsub.64 %d, %fl
  mov %d, %t16
  %t17 = sub.s32 0, %c
  mov %i, %t17
  %t18 = wsub.u32 0, %u
  mov %u, %t18
  %t19 = xor.s32 %uc, -1
  mov %i, %t19
  mov %i, %c
  %t20 = mul.s32 %c, %c
  mov %i, %t20
  ret
}

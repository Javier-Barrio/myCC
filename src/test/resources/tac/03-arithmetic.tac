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
  mov.s32 %i, %t0
  mov.u32 %t1, %i
  %t2 = wsub.u32 %t1, %u
  mov.u32 %u, %t2
  %t3 = mul.s64 %l, %i
  mov.s64 %l, %t3
  %t4 = udiv.u64 %l, %ul
  mov.u64 %ul, %t4
  %t5 = srem.s32 %i, %c
  mov.s32 %i, %t5
  mov.u32 %t6, %i
  %t7 = and.u32 %u, %t6
  mov.u32 %u, %t7
  mov.s32 %t8, 1
  %t9 = or.s64 %l, %t8
  mov.s64 %l, %t9
  %t10 = xor.s32 %uc, %c
  mov.s32 %i, %t10
  %t11 = shl.s32 %c, %l
  mov.s32 %i, %t11
  mov.s32 %t12, 3
  %t13 = lshr.u64 %ul, %t12
  mov.u64 %ul, %t13
  %t14 = i2f.32 %i
  %t15 = fadd.32 %fl, %t14
  mov.64 %d, %t15
  %t16 = fsub.64 %d, %fl
  mov.64 %d, %t16
  %t17 = sub.s32 0, %c
  mov.s32 %i, %t17
  %t18 = wsub.u32 0, %u
  mov.u32 %u, %t18
  %t19 = xor.s32 %uc, -1
  mov.s32 %i, %t19
  mov.s32 %i, %c
  %t20 = mul.s32 %c, %c
  mov.s32 %i, %t20
  ret
}

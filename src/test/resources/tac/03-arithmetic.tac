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
  i64 %t2
  i64 %t3
  u64 %t4
  i32 %t5
  u32 %t6
  i32 %t7
  i64 %t8
  i64 %t9
  i32 %t10
  i32 %t11
  i32 %t12
  i32 %t13
  u64 %t14
  u64 %t15
  f32 %t16
  f32 %t17
  f64 %t18
  f64 %t19
  f64 %t20
  i32 %t21
  u32 %t22
  i32 %t23
  i32 %t24
.entry:
  %t0 = add %c, %s
  mov %i, %t0
  %t1 = wsub %i, %u
  mov %u, %t1
  mov %t2, %i
  %t2 = shl %t2, 32
  %t2 = ashr %t2, 32
  %t3 = mul %l, %t2
  mov %l, %t3
  %t4 = udiv %l, %ul
  mov %ul, %t4
  %t5 = srem %i, %c
  mov %i, %t5
  %t6 = and %u, %i
  mov %u, %t6
  mov %t7, 1
  mov %t8, %t7
  %t8 = shl %t8, 32
  %t8 = ashr %t8, 32
  %t9 = or %l, %t8
  mov %l, %t9
  %t10 = xor %uc, %c
  mov %i, %t10
  mov %t11, %l
  %t12 = shl %c, %t11
  mov %i, %t12
  mov %t13, 3
  mov %t14, %t13
  %t15 = lshr %ul, %t14
  mov %ul, %t15
  %t16 = i2f %i
  %t17 = fadd %fl, %t16
  %t18 = fcvt %t17
  mov %d, %t18
  %t19 = fcvt %fl
  %t20 = fsub %d, %t19
  mov %d, %t20
  %t21 = sub 0, %c
  mov %i, %t21
  %t22 = wsub 0, %u
  mov %u, %t22
  %t23 = xor %uc, -1
  mov %i, %t23
  mov %i, %c
  %t24 = mul %c, %c
  mov %i, %t24
  ret
}

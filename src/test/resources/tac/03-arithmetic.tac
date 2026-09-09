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
  i32 %t1
  i32 %t2
  u32 %t3
  u32 %t4
  i64 %t5
  i64 %t6
  u64 %t7
  u64 %t8
  i32 %t9
  i32 %t10
  u32 %t11
  u32 %t12
  i32 %t13
  i64 %t14
  i64 %t15
  i32 %t16
  i32 %t17
  i32 %t18
  i32 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  u64 %t23
  u64 %t24
  f32 %t25
  f32 %t26
  f64 %t27
  f64 %t28
  f64 %t29
  i32 %t30
  i32 %t31
  u32 %t32
  i32 %t33
  i32 %t34
  i32 %t35
  i32 %t36
  i32 %t37
  i32 %t38
.entry:
  mov %t0, %c
  mov %t1, %s
  %t2 = add %t0, %t1
  mov %i, %t2
  mov %t3, %i
  %t4 = wsub %t3, %u
  mov %u, %t4
  mov %t5, %i
  %t5 = shl %t5, 32
  %t5 = ashr %t5, 32
  %t6 = mul %l, %t5
  mov %l, %t6
  mov %t7, %l
  %t8 = udiv %t7, %ul
  mov %ul, %t8
  mov %t9, %c
  %t10 = srem %i, %t9
  mov %i, %t10
  mov %t11, %i
  %t12 = and %u, %t11
  mov %u, %t12
  mov %t13, 1
  mov %t14, %t13
  %t14 = shl %t14, 32
  %t14 = ashr %t14, 32
  %t15 = or %l, %t14
  mov %l, %t15
  mov %t16, %uc
  mov %t17, %c
  %t18 = xor %t16, %t17
  mov %i, %t18
  mov %t19, %c
  mov %t20, %l
  %t21 = shl %t19, %t20
  mov %i, %t21
  mov %t22, 3
  mov %t23, %t22
  %t24 = lshr %ul, %t23
  mov %ul, %t24
  %t25 = i2f %i
  %t26 = fadd %fl, %t25
  %t27 = fcvt %t26
  mov %d, %t27
  %t28 = fcvt %fl
  %t29 = fsub %d, %t28
  mov %d, %t29
  mov %t30, %c
  %t31 = sub 0, %t30
  mov %i, %t31
  %t32 = wsub 0, %u
  mov %u, %t32
  mov %t33, %uc
  %t34 = xor %t33, -1
  mov %i, %t34
  mov %t35, %c
  mov %i, %t35
  mov %t36, %c
  mov %t37, %c
  %t38 = mul %t36, %t37
  mov %i, %t38
  ret
}

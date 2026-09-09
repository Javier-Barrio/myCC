target x86_64-sysv
define @falls_off(i32 %c) -> i32 {
  u8 %t0
  i32 %t1
.entry:
  %t0 = ne %c, 0
  condbr %t0, .then, .if.done
.then:
  mov %t1, 1
  ret %t1
.if.done:
  trap "end of non-void function"
}
define @u(u32 %a, u32 %b, f64 %d, f32 %f) -> u32 {
  u32 %q
  u8 %r
  f64 %e
  u8 %back
  u32 %t0
  u32 %t1
  u32 %t2
  i32 %t3
  u32 %t4
  u32 %t5
  u32 %t6
  u32 %t7
  i32 %t8
  i32 %t9
  i32 %t10
  i32 %t11
  i32 %t12
  i32 %t13
  i32 %t14
  f64 %t15
  i32 %t16
  f64 %t17
  i32 %t18
  f64 %t19
  i32 %t20
  f64 %t21
  i32 %t22
  f64 %t23
  f64 %t24
  f64 %t25
  f64 %t26
  f64 %t27
  f64 %t28
  u8 %t29
  u32 %t30
  u32 %t31
  u32 %t32
  u32 %t33
  f32 %t34
  f32 %t35
  u32 %t36
  u32 %t37
.entry:
  %t0 = udiv %a, %b
  %t1 = urem %a, %b
  %t2 = wadd %t0, %t1
  mov %t3, 3
  %t4 = lshr %a, %t3
  %t5 = wadd %t2, %t4
  %t6 = wmul %a, %b
  %t7 = wsub %t5, %t6
  mov %q, %t7
  mov %t8, 1
  mov %t9, 1
  mov %t10, 1
  mov %t11, 1
  mov %t12, 1
  %t13 = ult %a, %b
  condbr %t13, .or.done, .or
.or:
  %t14 = ule %a, %b
  mov %t12, %t14
  br .or.done
.or.done:
  condbr %t12, .or.done.2, .or.2
.or.2:
  %t15 = fcvt %f
  %t16 = feq %d, %t15
  mov %t11, %t16
  br .or.done.2
.or.done.2:
  condbr %t11, .or.done.3, .or.3
.or.3:
  %t17 = fcvt %f
  %t18 = fne %d, %t17
  mov %t10, %t18
  br .or.done.3
.or.done.3:
  condbr %t10, .or.done.4, .or.4
.or.4:
  %t19 = fcvt %f
  %t20 = flt %d, %t19
  mov %t9, %t20
  br .or.done.4
.or.done.4:
  condbr %t9, .or.done.5, .or.5
.or.5:
  %t21 = fcvt %f
  %t22 = fle %d, %t21
  mov %t8, %t22
  br .or.done.5
.or.done.5:
  mov %r, %t8
  %t23 = u2f %a
  %t24 = fcvt %f
  %t25 = fadd %t23, %t24
  mov %t26, 2.0
  %t27 = fdiv %d, %t26
  %t28 = fsub %t25, %t27
  mov %e, %t28
  %t29 = f2u %e
  %t29 = and %t29, 255
  mov %back, %t29
  mov %t30, %r
  %t31 = xor %q, %t30
  mov %t32, %back
  %t33 = xor %t31, %t32
  mov %t34, 1.0
  %t35 = fsub %f, %t34
  %t36 = f2u %t35
  %t37 = xor %t33, %t36
  ret %t37
}

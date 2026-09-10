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
  i32 %t15
  i32 %t16
  i32 %t17
  i32 %t18
  f64 %t19
  f64 %t20
  f64 %t21
  f64 %t22
  f64 %t23
  u8 %t24
  u32 %t25
  u32 %t26
  f32 %t27
  f32 %t28
  u32 %t29
  u32 %t30
.entry:
  %t0 = udiv.u32 %a, %b
  %t1 = urem.u32 %a, %b
  %t2 = wadd.u32 %t0, %t1
  mov %t3, 3
  %t4 = lshr.u32 %a, %t3
  %t5 = wadd.u32 %t2, %t4
  %t6 = wmul.u32 %a, %b
  %t7 = wsub.u32 %t5, %t6
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
  %t15 = feq %d, %f
  mov %t11, %t15
  br .or.done.2
.or.done.2:
  condbr %t11, .or.done.3, .or.3
.or.3:
  %t16 = fne %d, %f
  mov %t10, %t16
  br .or.done.3
.or.done.3:
  condbr %t10, .or.done.4, .or.4
.or.4:
  %t17 = flt %d, %f
  mov %t9, %t17
  br .or.done.4
.or.done.4:
  condbr %t9, .or.done.5, .or.5
.or.5:
  %t18 = fle %d, %f
  mov %t8, %t18
  br .or.done.5
.or.done.5:
  mov %r, %t8
  %t19 = u2f.64 %a
  %t20 = fadd.64 %t19, %f
  mov %t21, 2.0
  %t22 = fdiv.64 %d, %t21
  %t23 = fsub.64 %t20, %t22
  mov %e, %t23
  %t24 = f2u.64 %e
  mov %back, %t24
  %t25 = xor.u32 %q, %r
  %t26 = xor.u32 %t25, %back
  mov %t27, 1.0
  %t28 = fsub.32 %f, %t27
  %t29 = f2u.32 %t28
  %t30 = xor.u32 %t26, %t29
  ret %t30
}

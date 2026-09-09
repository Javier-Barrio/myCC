target x86_64-sysv
define @f() -> void {
  i32 %i
  u32 %u
  f64 %d
  ptr %p
  ptr %q
  u8 %b
  i8 %c
  u32 %t0
  i32 %t1
  f64 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  i32 %t6
  i32 %t7
  ptr %t8
  i32 %t9
  u32 %t10
  u32 %t11
  i32 %t12
  i32 %t13
  u8 %t14
  u8 %t15
  i32 %t16
  u8 %t17
  u8 %t18
  i32 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  i32 %t23
  f64 %t24
  f64 %t25
  ptr %t26
  ptr %t27
  i32 %t28
  i32 %t29
.entry:
  mov %t0, %i
  %t1 = eq %t0, %u
  mov %b, %t1
  %t2 = i2f %i
  %t3 = fne %t2, %d
  mov %b, %t3
  mov %t4, %c
  mov %t5, 122
  %t6 = slt %t4, %t5
  mov %b, %t6
  %t7 = ule %p, %q
  mov %b, %t7
  mov %t8, 0
  %t9 = eq %p, %t8
  mov %b, %t9
  mov %t10, %i
  mov %t11, 0
  %t12 = ule %t11, %t10
  mov %b, %t12
  mov %t13, 0
  %t14 = ne %p, 0
  condbr %t14, .and, .and.done
.and:
  %t15 = fne %d, 0.0
  mov %t13, %t15
  br .and.done
.and.done:
  mov %b, %t13
  mov %t16, 1
  %t17 = ne %i, 0
  condbr %t17, .or.done, .or
.or:
  mov %t16, %b
  br .or.done
.or.done:
  mov %b, %t16
  %t18 = ne %p, 0
  %t19 = eq %t18, 0
  mov %b, %t19
  mov %t21, %c
  %t22 = slt %t21, %i
  condbr %t22, .then, .else
.then:
  mov %t20, %i
  br .cond.done
.else:
  mov %t23, %c
  mov %t20, %t23
  br .cond.done
.cond.done:
  mov %i, %t20
  condbr %b, .then.2, .else.2
.then.2:
  %t25 = i2f %i
  mov %t24, %t25
  br .cond.done.2
.else.2:
  mov %t24, %d
  br .cond.done.2
.cond.done.2:
  mov %d, %t24
  condbr %b, .then.3, .else.3
.then.3:
  mov %t26, %p
  br .cond.done.3
.else.3:
  mov %t27, 0
  mov %t26, %t27
  br .cond.done.3
.cond.done.3:
  mov %p, %t26
  mov %t28, 1
  mov %i, %t28
  mov %t29, %c
  mov %i, %t29
  ret
}

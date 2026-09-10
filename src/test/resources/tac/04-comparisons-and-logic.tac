target x86_64-sysv
define @f() -> void {
  i32 %i
  u32 %u
  f64 %d
  ptr %p
  ptr %q
  u8 %b
  i8 %c
  i32 %t0
  f64 %t1
  i32 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  ptr %t6
  i32 %t7
  u32 %t8
  i32 %t9
  i32 %t10
  u8 %t11
  u8 %t12
  i32 %t13
  u8 %t14
  u8 %t15
  i32 %t16
  i32 %t17
  i32 %t18
  f64 %t19
  f64 %t20
  ptr %t21
  ptr %t22
  i32 %t23
.entry:
  %t0 = eq %i, %u
  mov %b, %t0
  %t1 = i2f %i
  %t2 = fne %t1, %d
  mov %b, %t2
  mov %t3, 122
  %t4 = slt %c, %t3
  mov %b, %t4
  %t5 = ule %p, %q
  mov %b, %t5
  mov %t6, 0
  %t7 = eq %p, %t6
  mov %b, %t7
  mov %t8, 0
  %t9 = ule %t8, %i
  mov %b, %t9
  mov %t10, 0
  %t11 = ne %p, 0
  condbr %t11, .and, .and.done
.and:
  %t12 = fne %d, 0.0
  mov %t10, %t12
  br .and.done
.and.done:
  mov %b, %t10
  mov %t13, 1
  %t14 = ne %i, 0
  condbr %t14, .or.done, .or
.or:
  mov %t13, %b
  br .or.done
.or.done:
  mov %b, %t13
  %t15 = ne %p, 0
  %t16 = eq %t15, 0
  mov %b, %t16
  %t18 = slt %c, %i
  condbr %t18, .then, .else
.then:
  mov %t17, %i
  br .cond.done
.else:
  mov %t17, %c
  br .cond.done
.cond.done:
  mov %i, %t17
  condbr %b, .then.2, .else.2
.then.2:
  %t20 = i2f %i
  mov %t19, %t20
  br .cond.done.2
.else.2:
  mov %t19, %d
  br .cond.done.2
.cond.done.2:
  mov %d, %t19
  condbr %b, .then.3, .else.3
.then.3:
  mov %t21, %p
  br .cond.done.3
.else.3:
  mov %t22, 0
  mov %t21, %t22
  br .cond.done.3
.cond.done.3:
  mov %p, %t21
  mov %t23, 1
  mov %i, %t23
  mov %i, %c
  ret
}

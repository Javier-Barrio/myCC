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
  ptr %t7
  i32 %t8
  u32 %t9
  u32 %t10
  i32 %t11
  i32 %t12
  u8 %t13
  u8 %t14
  i32 %t15
  u8 %t16
  u8 %t17
  i32 %t18
  i32 %t19
  i32 %t20
  f64 %t21
  f64 %t22
  ptr %t23
  ptr %t24
  i32 %t25
.entry:
  mov.u32 %t0, %i
  %t1 = eq %t0, %u
  mov.u8 %b, %t1
  %t2 = i2f.64 %i
  %t3 = fne %t2, %d
  mov.u8 %b, %t3
  mov.s32 %t4, 122
  %t5 = slt %c, %t4
  mov.u8 %b, %t5
  %t6 = ule %p, %q
  mov.u8 %b, %t6
  mov.u64 %t7, 0
  %t8 = eq %p, %t7
  mov.u8 %b, %t8
  mov.u32 %t9, %i
  mov.u32 %t10, 0
  %t11 = ule %t10, %t9
  mov.u8 %b, %t11
  mov.s32 %t12, 0
  %t13 = ne %p, 0
  condbr %t13, .and, .and.done
.and:
  %t14 = fne %d, 0.0
  mov.s32 %t12, %t14
  br .and.done
.and.done:
  mov.u8 %b, %t12
  mov.s32 %t15, 1
  %t16 = ne %i, 0
  condbr %t16, .or.done, .or
.or:
  mov.s32 %t15, %b
  br .or.done
.or.done:
  mov.u8 %b, %t15
  %t17 = ne %p, 0
  %t18 = eq %t17, 0
  mov.u8 %b, %t18
  %t20 = slt %c, %i
  condbr %t20, .then, .else
.then:
  mov.s32 %t19, %i
  br .cond.done
.else:
  mov.s32 %t19, %c
  br .cond.done
.cond.done:
  mov.s32 %i, %t19
  condbr %b, .then.2, .else.2
.then.2:
  %t22 = i2f.64 %i
  mov.64 %t21, %t22
  br .cond.done.2
.else.2:
  mov.64 %t21, %d
  br .cond.done.2
.cond.done.2:
  mov.64 %d, %t21
  condbr %b, .then.3, .else.3
.then.3:
  mov.u64 %t23, %p
  br .cond.done.3
.else.3:
  mov.u64 %t24, 0
  mov.u64 %t23, %t24
  br .cond.done.3
.cond.done.3:
  mov.u64 %p, %t23
  mov.s32 %t25, 1
  mov.s32 %i, %t25
  mov.s32 %i, %c
  ret
}

target x86_64-sysv
define @f(i32 %n) -> i32 {
  i32 %total
  i32 %m
  i32 %m.2
  i32 %i
  i32 %j
  i32 %t0
  u8 %t1
  i32 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  u8 %t6
  i32 %t7
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
  i32 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  i32 %t23
  i32 %t24
  i32 %t25
  i32 %t26
  i32 %t27
.entry:
  mov %t0, 0
  mov %total, %t0
  %t1 = ne %n, 0
  condbr %t1, .then, .else
.then:
  mov %t2, 1
  mov %total, %t2
  br .if.done
.else:
  mov %t3, 2
  mov %total, %t3
  br .if.done
.if.done:
  mov %t4, 2
  %t5 = mul.s32 %n, %t4
  mov %m, %t5
  %t6 = ne %m, 0
  condbr %t6, .then.2, .if.done.2
.then.2:
  %t7 = add.s32 %total, %m
  mov %total, %t7
  br .if.done.2
.if.done.2:
  mov %m.2, %n
  mov %t8, 3
  %t9 = slt %t8, %m.2
  condbr %t9, .then.3, .if.done.3
.then.3:
  %t10 = add.s32 %total, %m.2
  mov %total, %t10
  br .if.done.3
.if.done.3:
  br .while.cond
.while.cond:
  mov %t11, 0
  %t12 = slt %t11, %n
  condbr %t12, .while.body, .while.done
.while.body:
  mov %t13, 1
  %t14 = sub.s32 %n, %t13
  mov %n, %t14
  br .while.cond
.while.done:
  br .do.body
.do.body:
  mov %t15, 1
  %t16 = add.s32 %total, %t15
  mov %total, %t16
  br .do.cond
.do.cond:
  mov %t17, 10
  %t18 = slt %total, %t17
  condbr %t18, .do.body, .do.done
.do.done:
  mov %t19, 0
  mov %i, %t19
  mov %t20, 1
  mov %j, %t20
  br .for.cond
.for.cond:
  %t21 = slt %i, %n
  condbr %t21, .for.body, .for.done
.for.body:
  %t22 = add.s32 %total, %j
  mov %total, %t22
  br .for.step
.for.step:
  mov %t23, 1
  %t24 = add.s32 %i, %t23
  mov %i, %t24
  mov %t25, 2
  %t26 = mul.s32 %j, %t25
  mov %j, %t26
  br .for.cond
.for.done:
  mov %t27, 0
  mov %total, %t27
  br .for.body.2
.for.body.2:
  br .for.done.2
.for.step.2:
  br .for.body.2
.for.done.2:
  br .for.body.3
.for.body.3:
  br .for.done.3
.for.step.3:
  br .for.body.3
.for.done.3:
  ret %total
}
define @v() -> void {
.entry:
  ret
}

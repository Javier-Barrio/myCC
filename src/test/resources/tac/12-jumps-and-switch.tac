target x86_64-sysv
define @f(i32 %c, i8 %ch) -> i32 {
  i8 %k
  i32 %i
  i8 %t0
  u8 %t1
  i32 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  i32 %t6
  i32 %t7
  i32 %t8
  u8 %t9
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
.entry:
  %t0 = wsub.u8 %ch, 98
  %t1 = ule %t0, 4
  condbr %t1, .case.98.102, .case.next
.case.next:
  switch %ch, .default, [ 97 -> .case.97 ]
.case.97:
  br .case.98.102
.case.98.102:
  mov %t2, 1
  mov %c, %t2
  br .switch.done
.default:
  mov %t3, 0
  mov %c, %t3
  br .switch.done
.switch.done:
  mov %k, %ch
  switch %k, .switch.done.2, [ 1 -> .case.1 ]
.case.1:
  ret %k
.switch.done.2:
  switch %c, .switch.done.3, [ 99 -> .case.99 ]
.case.99:
  mov %t4, 0
  mov %c, %t4
  br .switch.done.3
.switch.done.3:
  br .retry
.retry:
  mov %t5, 3
  %t6 = slt %c, %t5
  condbr %t6, .then, .if.done
.then:
  mov %t7, 1
  %t8 = add.s32 %c, %t7
  mov %c, %t8
  br .retry
.if.done:
  br .outer
.outer:
  br .while.cond
.while.cond:
  %t9 = ne %c, 0
  condbr %t9, .while.body, .while.done
.while.body:
  mov %t10, 0
  mov %i, %t10
  br .for.cond
.for.cond:
  %t11 = slt %i, %c
  condbr %t11, .for.body, .for.done
.for.body:
  mov %t12, 1
  %t13 = eq %i, %t12
  condbr %t13, .then.2, .if.done.2
.then.2:
  br .for.step
.if.done.2:
  mov %t14, 2
  %t15 = eq %i, %t14
  condbr %t15, .then.3, .if.done.3
.then.3:
  br .while.cond
.if.done.3:
  mov %t16, 3
  %t17 = eq %i, %t16
  condbr %t17, .then.4, .if.done.4
.then.4:
  br .while.done
.if.done.4:
  br .for.done
.for.step:
  mov %t18, 1
  %t19 = add.s32 %i, %t18
  mov %i, %t19
  br .for.cond
.for.done:
  mov %t20, 1
  %t21 = sub.s32 %c, %t20
  mov %c, %t21
  br .while.cond
.while.done:
  br .end
.end:
  ret %c
}

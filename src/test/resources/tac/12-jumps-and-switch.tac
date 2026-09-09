target x86_64-sysv
define @f(i32 %c, i8 %ch) -> i32 {
  i8 %k
  i32 %i
  i32 %t0
  i32 %t1
  u8 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  i32 %t6
  i32 %t7
  i32 %t8
  i32 %t9
  i32 %t10
  i32 %t11
  u8 %t12
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
.entry:
  mov %t0, %ch
  %t1 = wsub %t0, 98
  %t2 = ule %t1, 4
  condbr %t2, .case.98.102, .case.next
.case.next:
  switch %t0, .default, [ 97 -> .case.97 ]
.case.97:
  br .case.98.102
.case.98.102:
  mov %t3, 1
  mov %c, %t3
  br .switch.done
.default:
  mov %t4, 0
  mov %c, %t4
  br .switch.done
.switch.done:
  mov %k, %ch
  mov %t5, %k
  switch %t5, .switch.done.2, [ 1 -> .case.1 ]
.case.1:
  mov %t6, %k
  ret %t6
.switch.done.2:
  switch %c, .switch.done.3, [ 99 -> .case.99 ]
.case.99:
  mov %t7, 0
  mov %c, %t7
  br .switch.done.3
.switch.done.3:
  br .retry
.retry:
  mov %t8, 3
  %t9 = slt %c, %t8
  condbr %t9, .then, .if.done
.then:
  mov %t10, 1
  %t11 = add %c, %t10
  mov %c, %t11
  br .retry
.if.done:
  br .outer
.outer:
  br .while.cond
.while.cond:
  %t12 = ne %c, 0
  condbr %t12, .while.body, .while.done
.while.body:
  mov %t13, 0
  mov %i, %t13
  br .for.cond
.for.cond:
  %t14 = slt %i, %c
  condbr %t14, .for.body, .for.done
.for.body:
  mov %t15, 1
  %t16 = eq %i, %t15
  condbr %t16, .then.2, .if.done.2
.then.2:
  br .for.step
.if.done.2:
  mov %t17, 2
  %t18 = eq %i, %t17
  condbr %t18, .then.3, .if.done.3
.then.3:
  br .while.cond
.if.done.3:
  mov %t19, 3
  %t20 = eq %i, %t19
  condbr %t20, .then.4, .if.done.4
.then.4:
  br .while.done
.if.done.4:
  br .for.done
.for.step:
  mov %t21, 1
  %t22 = add %i, %t21
  mov %i, %t22
  br .for.cond
.for.done:
  mov %t23, 1
  %t24 = sub %c, %t23
  mov %c, %t24
  br .while.cond
.while.done:
  br .end
.end:
  ret %c
}

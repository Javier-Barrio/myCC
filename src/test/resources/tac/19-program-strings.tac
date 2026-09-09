target x86_64-sysv
global internal @.str.250cc051be3c036f : [5 x i8] align 1 readonly = { 0 : bytes "zero\00" }
global internal @.str.ed680992bea55a8f : [4 x i8] align 1 readonly = { 0 : bytes "one\00" }
global internal @.str.be9d66a41acbccb3 : [4 x i8] align 1 readonly = { 0 : bytes "two\00" }
global internal @.str.a51e86b8b5cb34db : [2 x i8] align 1 readonly = { 0 : bytes "?\00" }
global internal @names : [3 x ptr] align 8 readonly = { 0 : addr @.str.250cc051be3c036f, 8 : addr @.str.ed680992bea55a8f, 16 : addr @.str.be9d66a41acbccb3 }
define @strlen_(ptr %s) -> u64 {
  ptr %p
  i8 %t0
  u8 %t1
  i32 %t2
  i64 %t3
  ptr %t4
  i64 %t5
  u64 %t6
.entry:
  mov %p, %s
  br .while.cond
.while.cond:
  %t0 = load.s8 %p
  %t1 = ne %t0, 0
  condbr %t1, .while.body, .while.done
.while.body:
  mov %t2, 1
  mov %t3, %t2
  %t3 = shl %t3, 32
  %t3 = ashr %t3, 32
  %t4 = wadd %p, %t3
  mov %p, %t4
  br .while.cond
.while.done:
  %t5 = wsub %p, %s
  mov %t6, %t5
  ret %t6
}
define @atoi_(ptr %s) -> i32 {
  i32 %sign
  i32 %value
  i32 %t0
  i32 %t1
  i8 %t2
  i32 %t3
  i32 %t4
  i32 %t5
  i32 %t6
  i32 %t7
  i32 %t8
  i64 %t9
  ptr %t10
  i32 %t11
  i8 %t12
  i32 %t13
  i32 %t14
  i32 %t15
  i8 %t16
  i32 %t17
  i32 %t18
  i32 %t19
  i32 %t20
  i32 %t21
  i8 %t22
  i32 %t23
  i32 %t24
  i32 %t25
  i32 %t26
  i32 %t27
  i64 %t28
  ptr %t29
  i32 %t30
.entry:
  mov %t0, 1
  mov %sign, %t0
  mov %t1, 0
  mov %value, %t1
  %t2 = load.s8 %s
  mov %t3, %t2
  mov %t4, 45
  %t5 = eq %t3, %t4
  condbr %t5, .then, .if.done
.then:
  mov %t6, 1
  %t7 = sub 0, %t6
  mov %sign, %t7
  mov %t8, 1
  mov %t9, %t8
  %t9 = shl %t9, 32
  %t9 = ashr %t9, 32
  %t10 = wadd %s, %t9
  mov %s, %t10
  br .if.done
.if.done:
  br .for.cond
.for.cond:
  mov %t11, 0
  %t12 = load.s8 %s
  mov %t13, %t12
  mov %t14, 48
  %t15 = sle %t14, %t13
  condbr %t15, .and, .and.done
.and:
  %t16 = load.s8 %s
  mov %t17, %t16
  mov %t18, 57
  %t19 = sle %t17, %t18
  mov %t11, %t19
  br .and.done
.and.done:
  condbr %t11, .for.body, .for.done
.for.body:
  mov %t20, 10
  %t21 = mul %value, %t20
  %t22 = load.s8 %s
  mov %t23, %t22
  mov %t24, 48
  %t25 = sub %t23, %t24
  %t26 = add %t21, %t25
  mov %value, %t26
  br .for.step
.for.step:
  mov %t27, 1
  mov %t28, %t27
  %t28 = shl %t28, 32
  %t28 = ashr %t28, 32
  %t29 = wadd %s, %t28
  mov %s, %t29
  br .for.cond
.for.done:
  %t30 = mul %sign, %value
  ret %t30
}
define @name(i32 %n) -> ptr {
  ptr %t0
  i32 %t1
  i32 %t2
  i32 %t3
  u64 %t4
  i32 %t5
  i32 %t6
  ptr %t7
  i64 %t8
  i64 %t9
  ptr %t10
  ptr %t11
  ptr %t12
.entry:
  mov %t1, 0
  mov %t2, 0
  %t3 = sle %t2, %n
  condbr %t3, .and, .and.done
.and:
  mov %t4, 3
  mov %t5, %t4
  %t6 = slt %n, %t5
  mov %t1, %t6
  br .and.done
.and.done:
  condbr %t1, .then, .else
.then:
  %t7 = addrof @names
  mov %t8, %n
  %t8 = shl %t8, 32
  %t8 = ashr %t8, 32
  %t9 = wmul %t8, 8
  %t10 = wadd %t7, %t9
  %t11 = load.u64 %t10
  mov %t0, %t11
  br .cond.done
.else:
  %t12 = addrof @.str.a51e86b8b5cb34db
  mov %t0, %t12
  br .cond.done
.cond.done:
  ret %t0
}

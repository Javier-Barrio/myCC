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
  ret %t5
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
  i64 %t8
  ptr %t9
  i32 %t10
  i8 %t11
  i32 %t12
  i32 %t13
  i8 %t14
  i32 %t15
  i32 %t16
  i32 %t17
  i32 %t18
  i8 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  i32 %t23
  i64 %t24
  ptr %t25
  i32 %t26
.entry:
  mov %t0, 1
  mov %sign, %t0
  mov %t1, 0
  mov %value, %t1
  %t2 = load.s8 %s
  mov %t3, 45
  %t4 = eq %t2, %t3
  condbr %t4, .then, .if.done
.then:
  mov %t5, 1
  %t6 = sub 0, %t5
  mov %sign, %t6
  mov %t7, 1
  mov %t8, %t7
  %t8 = shl %t8, 32
  %t8 = ashr %t8, 32
  %t9 = wadd %s, %t8
  mov %s, %t9
  br .if.done
.if.done:
  br .for.cond
.for.cond:
  mov %t10, 0
  %t11 = load.s8 %s
  mov %t12, 48
  %t13 = sle %t12, %t11
  condbr %t13, .and, .and.done
.and:
  %t14 = load.s8 %s
  mov %t15, 57
  %t16 = sle %t14, %t15
  mov %t10, %t16
  br .and.done
.and.done:
  condbr %t10, .for.body, .for.done
.for.body:
  mov %t17, 10
  %t18 = mul %value, %t17
  %t19 = load.s8 %s
  mov %t20, 48
  %t21 = sub %t19, %t20
  %t22 = add %t18, %t21
  mov %value, %t22
  br .for.step
.for.step:
  mov %t23, 1
  mov %t24, %t23
  %t24 = shl %t24, 32
  %t24 = ashr %t24, 32
  %t25 = wadd %s, %t24
  mov %s, %t25
  br .for.cond
.for.done:
  %t26 = mul %sign, %value
  ret %t26
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

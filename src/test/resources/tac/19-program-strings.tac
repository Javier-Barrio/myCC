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
  ptr %t3
  i64 %t4
.entry:
  mov %p, %s
  br .while.cond
.while.cond:
  %t0 = load.s8 %p
  %t1 = ne %t0, 0
  condbr %t1, .while.body, .while.done
.while.body:
  mov %t2, 1
  %t3 = wadd %p, %t2
  mov %p, %t3
  br .while.cond
.while.done:
  %t4 = wsub %p, %s
  ret %t4
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
  ptr %t8
  i32 %t9
  i8 %t10
  i32 %t11
  i32 %t12
  i8 %t13
  i32 %t14
  i32 %t15
  i32 %t16
  i32 %t17
  i8 %t18
  i32 %t19
  i32 %t20
  i32 %t21
  i32 %t22
  ptr %t23
  i32 %t24
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
  %t6 = sub.s32 0, %t5
  mov %sign, %t6
  mov %t7, 1
  %t8 = wadd %s, %t7
  mov %s, %t8
  br .if.done
.if.done:
  br .for.cond
.for.cond:
  mov %t9, 0
  %t10 = load.s8 %s
  mov %t11, 48
  %t12 = sle %t11, %t10
  condbr %t12, .and, .and.done
.and:
  %t13 = load.s8 %s
  mov %t14, 57
  %t15 = sle %t13, %t14
  mov %t9, %t15
  br .and.done
.and.done:
  condbr %t9, .for.body, .for.done
.for.body:
  mov %t16, 10
  %t17 = mul.s32 %value, %t16
  %t18 = load.s8 %s
  mov %t19, 48
  %t20 = sub.s32 %t18, %t19
  %t21 = add.s32 %t17, %t20
  mov %value, %t21
  br .for.step
.for.step:
  mov %t22, 1
  %t23 = wadd %s, %t22
  mov %s, %t23
  br .for.cond
.for.done:
  %t24 = mul.s32 %sign, %value
  ret %t24
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
  ptr %t9
  ptr %t10
  ptr %t11
.entry:
  mov %t1, 0
  mov %t2, 0
  %t3 = sle %t2, %n
  condbr %t3, .and, .and.done
.and:
  mov %t4, 3
  mov.s32 %t5, %t4
  %t6 = slt %n, %t5
  mov %t1, %t6
  br .and.done
.and.done:
  condbr %t1, .then, .else
.then:
  %t7 = addrof @names
  %t8 = wmul %n, 8
  %t9 = wadd %t7, %t8
  %t10 = load.u64 %t9
  mov %t0, %t10
  br .cond.done
.else:
  %t11 = addrof @.str.a51e86b8b5cb34db
  mov %t0, %t11
  br .cond.done
.cond.done:
  ret %t0
}

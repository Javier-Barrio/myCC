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
  mov.u64 %p, %s
  br .while.cond
.while.cond:
  %t0 = load.s8 %p
  %t1 = ne %t0, 0
  condbr %t1, .while.body, .while.done
.while.body:
  mov.s32 %t2, 1
  %t3 = wmul.s64 %t2, 1
  %t4 = wadd.u64 %p, %t3
  mov.u64 %p, %t4
  br .while.cond
.while.done:
  %t5 = wsub.s64 %p, %s
  %t5 = sdiv.s64 %t5, 1
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
  mov.s32 %t0, 1
  mov.s32 %sign, %t0
  mov.s32 %t1, 0
  mov.s32 %value, %t1
  %t2 = load.s8 %s
  mov.s32 %t3, 45
  %t4 = eq %t2, %t3
  condbr %t4, .then, .if.done
.then:
  mov.s32 %t5, 1
  %t6 = sub.s32 0, %t5
  mov.s32 %sign, %t6
  mov.s32 %t7, 1
  %t8 = wmul.s64 %t7, 1
  %t9 = wadd.u64 %s, %t8
  mov.u64 %s, %t9
  br .if.done
.if.done:
  br .for.cond
.for.cond:
  mov.s32 %t10, 0
  %t11 = load.s8 %s
  mov.s32 %t12, 48
  %t13 = sle %t12, %t11
  condbr %t13, .and, .and.done
.and:
  %t14 = load.s8 %s
  mov.s32 %t15, 57
  %t16 = sle %t14, %t15
  mov.s32 %t10, %t16
  br .and.done
.and.done:
  condbr %t10, .for.body, .for.done
.for.body:
  mov.s32 %t17, 10
  %t18 = mul.s32 %value, %t17
  %t19 = load.s8 %s
  mov.s32 %t20, 48
  %t21 = sub.s32 %t19, %t20
  %t22 = add.s32 %t18, %t21
  mov.s32 %value, %t22
  br .for.step
.for.step:
  mov.s32 %t23, 1
  %t24 = wmul.s64 %t23, 1
  %t25 = wadd.u64 %s, %t24
  mov.u64 %s, %t25
  br .for.cond
.for.done:
  %t26 = mul.s32 %sign, %value
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
  ptr %t9
  ptr %t10
  ptr %t11
.entry:
  mov.s32 %t1, 0
  mov.s32 %t2, 0
  %t3 = sle %t2, %n
  condbr %t3, .and, .and.done
.and:
  mov.u64 %t4, 3
  mov.s32 %t5, %t4
  %t6 = slt %n, %t5
  mov.s32 %t1, %t6
  br .and.done
.and.done:
  condbr %t1, .then, .else
.then:
  %t7 = addrof @names
  %t8 = wmul.s64 %n, 8
  %t9 = wadd.u64 %t7, %t8
  %t10 = load.u64 %t9
  mov.u64 %t0, %t10
  br .cond.done
.else:
  %t11 = addrof @.str.a51e86b8b5cb34db
  mov.u64 %t0, %t11
  br .cond.done
.cond.done:
  ret %t0
}

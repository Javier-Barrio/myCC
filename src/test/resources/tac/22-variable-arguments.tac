target x86_64-sysv
type %__va_list_tag = { u32 @0, u32 @4, ptr @8, ptr @16 } size 24 align 8
define @total(i32 %n, ...) -> i64 {
  [1 x %__va_list_tag] %ap
  i64 %s
  i32 %i
  ptr %t0
  i32 %t1
  i32 %t2
  i32 %t3
  ptr %t4
  i32 %t5
  i64 %t6
  i32 %t7
  i32 %t8
  i32 %t9
.entry:
  %t0 = addrof %ap
  vastart %t0
  mov.s32 %t1, 0
  mov.s64 %s, %t1
  mov.s32 %t2, 0
  mov.s32 %i, %t2
  br .for.cond
.for.cond:
  %t3 = slt %i, %n
  condbr %t3, .for.body, .for.done
.for.body:
  %t4 = addrof %ap
  %t5 = vaarg %t4
  %t6 = add.s64 %s, %t5
  mov.s64 %s, %t6
  br .for.step
.for.step:
  mov.s32 %t7, 1
  %t8 = add.s32 %i, %t7
  mov.s32 %i, %t8
  br .for.cond
.for.done:
  mov.s32 %t9, 0
  ret %s
}
define @first_double(ptr %p, ...) -> f64 {
  [1 x %__va_list_tag] %ap
  [1 x %__va_list_tag] %copy
  ptr %q
  f64 %d
  ptr %t0
  ptr %t1
  i32 %t2
  i64 %t3
  ptr %t4
  ptr %t5
  i32 %t6
  i64 %t7
  ptr %t8
  ptr %t9
  ptr %t10
  ptr %t11
  f64 %t12
  i32 %t13
  i32 %t14
  f64 %t15
  i32 %t16
  f64 %t17
.entry:
  %t0 = addrof %ap
  vastart %t0
  %t1 = addrof %copy
  mov.s32 %t2, 0
  %t3 = wmul.s64 %t2, 24
  %t4 = wadd.u64 %t1, %t3
  %t5 = addrof %ap
  mov.s32 %t6, 0
  %t7 = wmul.s64 %t6, 24
  %t8 = wadd.u64 %t5, %t7
  store.%__va_list_tag %t4, %t8
  %t9 = addrof %copy
  %t10 = vaarg %t9
  mov.u64 %q, %t10
  %t11 = addrof %ap
  %t12 = vaarg %t11
  mov.64 %d, %t12
  mov.s32 %t13, 0
  mov.s32 %t14, 0
  %t16 = eq %q, %p
  condbr %t16, .then, .else
.then:
  mov.64 %t15, %d
  br .cond.done
.else:
  %t17 = fsub.64 -0.0, %d
  mov.64 %t15, %t17
  br .cond.done
.cond.done:
  ret %t15
}

target x86_64-sysv
type %Node = { i32 @0, ptr @8 } size 16 align 8
define @push(ptr %head, ptr %node) -> ptr {
  ptr %t0
.entry:
  %t0 = wadd.u64 %node, 8
  store.64 %t0, %head
  ret %node
}
define @sum(ptr %head) -> i32 {
  i32 %total
  ptr %it
  i32 %t0
  ptr %t1
  i32 %t2
  ptr %t3
  i32 %t4
  i32 %t5
  ptr %t6
  ptr %t7
.entry:
  mov.s32 %t0, 0
  mov.s32 %total, %t0
  mov.u64 %it, %head
  br .for.cond
.for.cond:
  mov.u64 %t1, 0
  %t2 = ne %it, %t1
  condbr %t2, .for.body, .for.done
.for.body:
  %t3 = wadd.u64 %it, 0
  %t4 = load.s32 %t3
  %t5 = add.s32 %total, %t4
  mov.s32 %total, %t5
  br .for.step
.for.step:
  %t6 = wadd.u64 %it, 8
  %t7 = load.u64 %t6
  mov.u64 %it, %t7
  br .for.cond
.for.done:
  ret %total
}
define @length(ptr %head) -> i32 {
  i32 %n
  i32 %t0
  u8 %t1
  i32 %t2
  i32 %t3
  ptr %t4
  ptr %t5
.entry:
  mov.s32 %t0, 0
  mov.s32 %n, %t0
  br .while.cond
.while.cond:
  %t1 = ne %head, 0
  condbr %t1, .while.body, .while.done
.while.body:
  mov.s32 %t2, 1
  %t3 = add.s32 %n, %t2
  mov.s32 %n, %t3
  %t4 = wadd.u64 %head, 8
  %t5 = load.u64 %t4
  mov.u64 %head, %t5
  br .while.cond
.while.done:
  ret %n
}
define @main() -> i32 {
  %Node %a
  %Node %b
  ptr %list
  ptr %t0
  ptr %t1
  i32 %t2
  ptr %t3
  ptr %t4
  ptr %t5
  ptr %t6
  i32 %t7
  ptr %t8
  ptr %t9
  ptr %t10
  ptr %t11
  ptr %t12
  ptr %t13
  ptr %t14
  i32 %t15
  i32 %t16
  i32 %t17
  i32 %t18
  i32 %t19
.entry:
  %t0 = addrof %a
  store.%Node %t0, 0
  %t1 = wadd.u64 %t0, 0
  mov.s32 %t2, 1
  store.32 %t1, %t2
  %t3 = wadd.u64 %t0, 8
  mov.u64 %t4, 0
  store.64 %t3, %t4
  %t5 = addrof %b
  store.%Node %t5, 0
  %t6 = wadd.u64 %t5, 0
  mov.s32 %t7, 2
  store.32 %t6, %t7
  %t8 = wadd.u64 %t5, 8
  mov.u64 %t9, 0
  store.64 %t8, %t9
  mov.u64 %t10, 0
  %t11 = addrof %a
  %t12 = call (ptr, ptr) -> ptr @push(%t10, %t11)
  %t13 = addrof %b
  %t14 = call (ptr, ptr) -> ptr @push(%t12, %t13)
  mov.u64 %list, %t14
  %t15 = call (ptr) -> i32 @sum(%list)
  %t16 = call (ptr) -> i32 @length(%list)
  mov.s32 %t17, 2
  %t18 = mul.s32 %t16, %t17
  %t19 = sub.s32 %t15, %t18
  ret %t19
}

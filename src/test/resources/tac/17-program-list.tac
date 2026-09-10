target x86_64-sysv
type %Node = { i32 @0, ptr @8 } size 16 align 8
define @push(ptr %head, ptr %node) -> ptr {
  ptr %t0
.entry:
  %t0 = wadd %node, 8
  store.64 %t0, %head
  ret %node
}
define @sum(ptr %head) -> i32 {
  i32 %total
  ptr %it
  i32 %t0
  ptr %t1
  i32 %t2
  i32 %t3
  i32 %t4
  ptr %t5
  ptr %t6
.entry:
  mov %t0, 0
  mov %total, %t0
  mov %it, %head
  br .for.cond
.for.cond:
  mov %t1, 0
  %t2 = ne %it, %t1
  condbr %t2, .for.body, .for.done
.for.body:
  %t3 = load.s32 %it
  %t4 = add.s32 %total, %t3
  mov %total, %t4
  br .for.step
.for.step:
  %t5 = wadd %it, 8
  %t6 = load.u64 %t5
  mov %it, %t6
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
  mov %t0, 0
  mov %n, %t0
  br .while.cond
.while.cond:
  %t1 = ne %head, 0
  condbr %t1, .while.body, .while.done
.while.body:
  mov %t2, 1
  %t3 = add.s32 %n, %t2
  mov %n, %t3
  %t4 = wadd %head, 8
  %t5 = load.u64 %t4
  mov %head, %t5
  br .while.cond
.while.done:
  ret %n
}
define @main() -> i32 {
  %Node %a
  %Node %b
  ptr %list
  ptr %t0
  i32 %t1
  ptr %t2
  ptr %t3
  ptr %t4
  i32 %t5
  ptr %t6
  ptr %t7
  ptr %t8
  ptr %t9
  ptr %t10
  ptr %t11
  ptr %t12
  i32 %t13
  i32 %t14
  i32 %t15
  i32 %t16
  i32 %t17
.entry:
  %t0 = addrof %a
  zero %Node %t0
  mov %t1, 1
  store.32 %t0, %t1
  %t2 = wadd %t0, 8
  mov %t3, 0
  store.64 %t2, %t3
  %t4 = addrof %b
  zero %Node %t4
  mov %t5, 2
  store.32 %t4, %t5
  %t6 = wadd %t4, 8
  mov %t7, 0
  store.64 %t6, %t7
  mov %t8, 0
  %t9 = addrof %a
  %t10 = call (ptr, ptr) -> ptr @push(%t8, %t9)
  %t11 = addrof %b
  %t12 = call (ptr, ptr) -> ptr @push(%t10, %t11)
  mov %list, %t12
  %t13 = call (ptr) -> i32 @sum(%list)
  %t14 = call (ptr) -> i32 @length(%list)
  mov %t15, 2
  %t16 = mul.s32 %t14, %t15
  %t17 = sub.s32 %t13, %t16
  ret %t17
}

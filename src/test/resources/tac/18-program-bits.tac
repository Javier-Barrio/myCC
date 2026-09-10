target x86_64-sysv
define @apply(u32 %word, i32 %op, u32 %bit) -> u32 {
  u32 %mask
  u32 %t0
  i32 %t1
  u32 %t2
  u32 %t3
  u32 %t4
  u32 %t5
  u32 %t6
  u32 %t7
  u32 %t8
  u32 %t9
  i32 %t10
  u32 %t11
  i32 %t12
  u32 %t13
.entry:
  mov.u32 %t0, 1
  mov.s32 %t1, 31
  mov.u32 %t2, %t1
  %t3 = and.u32 %bit, %t2
  %t4 = shl.u32 %t0, %t3
  mov.u32 %mask, %t4
  switch %op, .switch.done, [ 0 -> .case.0, 1 -> .case.1, 2 -> .case.2, 3 -> .case.3 ]
.case.0:
  %t5 = or.u32 %word, %mask
  ret %t5
.case.1:
  %t6 = xor.u32 %mask, -1
  %t7 = and.u32 %word, %t6
  ret %t7
.case.2:
  %t8 = xor.u32 %word, %mask
  ret %t8
.case.3:
  %t9 = and.u32 %word, %mask
  mov.s32 %t10, 0
  mov.u32 %t11, %t10
  %t12 = ne %t9, %t11
  mov.u32 %t13, %t12
  ret %t13
.switch.done:
  ret %word
}
define @popcount(u64 %x) -> i32 {
  i32 %n
  i32 %t0
  u8 %t1
  i32 %t2
  i32 %t3
  i32 %t4
  u64 %t5
  u64 %t6
.entry:
  mov.s32 %t0, 0
  mov.s32 %n, %t0
  br .for.cond
.for.cond:
  %t1 = ne %x, 0
  condbr %t1, .for.body, .for.done
.for.body:
  mov.s32 %t2, 1
  %t3 = add.s32 %n, %t2
  mov.s32 %n, %t3
  br .for.step
.for.step:
  mov.s32 %t4, 1
  %t5 = wsub.u64 %x, %t4
  %t6 = and.u64 %x, %t5
  mov.u64 %x, %t6
  br .for.cond
.for.done:
  ret %n
}

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
  i32 %t9
  i32 %t10
.entry:
  mov %t0, 1
  mov %t1, 31
  %t2 = and %bit, %t1
  %t3 = shl %t0, %t2
  mov %mask, %t3
  switch %op, .switch.done, [ 0 -> .case.0, 1 -> .case.1, 2 -> .case.2, 3 -> .case.3 ]
.case.0:
  %t4 = or %word, %mask
  ret %t4
.case.1:
  %t5 = xor %mask, -1
  %t6 = and %word, %t5
  ret %t6
.case.2:
  %t7 = xor %word, %mask
  ret %t7
.case.3:
  %t8 = and %word, %mask
  mov %t9, 0
  %t10 = ne %t8, %t9
  ret %t10
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
  u64 %t7
.entry:
  mov %t0, 0
  mov %n, %t0
  br .for.cond
.for.cond:
  %t1 = ne %x, 0
  condbr %t1, .for.body, .for.done
.for.body:
  mov %t2, 1
  %t3 = add %n, %t2
  mov %n, %t3
  br .for.step
.for.step:
  mov %t4, 1
  mov %t5, %t4
  %t5 = shl %t5, 32
  %t5 = ashr %t5, 32
  %t6 = wsub %x, %t5
  %t7 = and %x, %t6
  mov %x, %t7
  br .for.cond
.for.done:
  ret %n
}

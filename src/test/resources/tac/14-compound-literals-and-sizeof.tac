target x86_64-sysv
type %Pt = { i32 @0, i32 @4 } size 8 align 4
global internal @.lit.1 : %Pt align 4 = { 0 : i32 0, 4 : i32 0 }
global @origin : ptr align 8 = { 0 : addr @.lit.1 }
global internal @.lit.2 : [3 x i32] align 4 = { 0 : i32 1, 4 : i32 2, 8 : i32 3 }
global @tbl : ptr align 8 = { 0 : addr @.lit.2 }
global internal @.lit.3 : i32 align 4 readonly = { 0 : i32 7 }
define @f(i32 %k) -> i32 {
  %Pt %lit10
  %Pt %p
  [2 x i32] %lit11
  ptr %q
  %Pt %lit13
  ptr %t0
  ptr %t1
  ptr %t2
  i32 %t3
  i32 %t4
  ptr %t5
  ptr %t6
  i32 %t7
  ptr %t8
  i32 %t9
  ptr %t10
  i32 %t11
  ptr %t12
  i32 %t13
  ptr %t14
  i32 %t15
  u64 %t16
  u64 %t17
  u64 %t18
  u64 %t19
  u64 %t20
  i32 %t21
.entry:
  %t0 = addrof %p
  zero %Pt %t0
  %t1 = addrof %lit10
  zero %Pt %t1
  store.32 %t1, %k
  %t2 = wadd %t1, 4
  mov %t3, 1
  %t4 = add %k, %t3
  store.32 %t2, %t4
  copy %Pt %t0, %t1
  %t5 = addrof %lit11
  zero [2 x i32] %t5
  store.32 %t5, %k
  %t6 = wadd %t5, 4
  mov %t7, 2
  store.32 %t6, %t7
  mov %q, %t5
  %t8 = addrof @.lit.3
  %t9 = load.s32 %t8
  %t10 = addrof %lit13
  zero %Pt %t10
  mov %t11, 1
  store.32 %t10, %t11
  %t12 = wadd %t10, 4
  mov %t13, 2
  store.32 %t12, %t13
  %t14 = wadd %t10, 4
  %t15 = load.s32 %t14
  mov %t16, %t15
  %t16 = shl %t16, 32
  %t16 = ashr %t16, 32
  mov %t17, 8
  %t18 = wadd %t16, %t17
  mov %t19, 8
  %t20 = wadd %t18, %t19
  mov %t21, %t20
  ret %t21
}

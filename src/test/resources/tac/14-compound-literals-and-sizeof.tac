target x86_64-sysv
type %Pt = { i32 @0, i32 @4 } size 8 align 4
global internal @.lit.1 : %Pt align 4 = { 0 : i32 0, 4 : i32 0 }
global @origin : ptr align 8 = { 0 : addr @.lit.1 }
global internal @.lit.2 : [3 x i32] align 4 = { 0 : i32 1, 4 : i32 2, 8 : i32 3 }
global @tbl : ptr align 8 = { 0 : addr @.lit.2 }
global internal @.lit.3 : i32 align 4 readonly = { 0 : i32 7 }
define @f(i32 %k) -> i32 {
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
  ptr %t7
  i32 %t8
  ptr %t9
  i32 %t10
  ptr %t11
  ptr %t12
  i32 %t13
  ptr %t14
  i32 %t15
  ptr %t16
  i32 %t17
  u64 %t18
  u64 %t19
  u64 %t20
  u64 %t21
  i32 %t22
.entry:
  %t0 = addrof %p
  store.%Pt %t0, 0
  %t1 = wadd.u64 %t0, 0
  store.32 %t1, %k
  %t2 = wadd.u64 %t0, 4
  mov.s32 %t3, 1
  %t4 = add.s32 %k, %t3
  store.32 %t2, %t4
  %t5 = addrof %lit11
  store.[2 x i32] %t5, 0
  %t6 = wadd.u64 %t5, 0
  store.32 %t6, %k
  %t7 = wadd.u64 %t5, 4
  mov.s32 %t8, 2
  store.32 %t7, %t8
  mov.u64 %q, %t5
  %t9 = addrof @.lit.3
  %t10 = load.s32 %t9
  %t11 = addrof %lit13
  store.%Pt %t11, 0
  %t12 = wadd.u64 %t11, 0
  mov.s32 %t13, 1
  store.32 %t12, %t13
  %t14 = wadd.u64 %t11, 4
  mov.s32 %t15, 2
  store.32 %t14, %t15
  %t16 = wadd.u64 %t11, 4
  %t17 = load.s32 %t16
  mov.u64 %t18, 8
  %t19 = wadd.u64 %t17, %t18
  mov.u64 %t20, 8
  %t21 = wadd.u64 %t19, %t20
  mov.s32 %t22, %t21
  ret %t22
}

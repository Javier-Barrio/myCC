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
  ptr %t3
  ptr %t4
  i32 %t5
  i32 %t6
  ptr %t7
  ptr %t8
  ptr %t9
  i32 %t10
  ptr %t11
  i32 %t12
  ptr %t13
  ptr %t14
  i32 %t15
  ptr %t16
  i32 %t17
  ptr %t18
  i32 %t19
  u64 %t20
  u64 %t21
  u64 %t22
  u64 %t23
  i32 %t24
.entry:
  %t0 = addrof %p
  store.%Pt %t0, 0
  %t1 = wadd.u64 %t0, 0
  %t2 = addrof %lit10
  store.%Pt %t2, 0
  %t3 = wadd.u64 %t2, 0
  store.32 %t3, %k
  %t4 = wadd.u64 %t2, 4
  mov.s32 %t5, 1
  %t6 = add.s32 %k, %t5
  store.32 %t4, %t6
  store.%Pt %t1, %t2
  %t7 = addrof %lit11
  store.[2 x i32] %t7, 0
  %t8 = wadd.u64 %t7, 0
  store.32 %t8, %k
  %t9 = wadd.u64 %t7, 4
  mov.s32 %t10, 2
  store.32 %t9, %t10
  mov.u64 %q, %t7
  %t11 = addrof @.lit.3
  %t12 = load.s32 %t11
  %t13 = addrof %lit13
  store.%Pt %t13, 0
  %t14 = wadd.u64 %t13, 0
  mov.s32 %t15, 1
  store.32 %t14, %t15
  %t16 = wadd.u64 %t13, 4
  mov.s32 %t17, 2
  store.32 %t16, %t17
  %t18 = wadd.u64 %t13, 4
  %t19 = load.s32 %t18
  mov.u64 %t20, 8
  %t21 = wadd.u64 %t19, %t20
  mov.u64 %t22, 8
  %t23 = wadd.u64 %t21, %t22
  mov.s32 %t24, %t23
  ret %t24
}

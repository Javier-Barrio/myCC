target x86_64-sysv
type %In = { i32 @0, i32 @4 } size 8 align 4
type %Out = { %In @0, [8 x i8] @8, [3 x i32] @16 } size 28 align 4
type %U = { i8 @0, i32 @0 } size 4 align 4
global internal @.str.a1e0cced32745eec : [3 x i8] align 1 readonly = { 0 : bytes "ab\00" }
global @a : [3 x i32] align 4 = { 0 : i32 1, 4 : i32 2, 8 : i32 3 }
global @b : [3 x i32] align 4 = { 8 : i32 9 }
global @s : [4 x i8] align 1 = { 0 : i8 97, 1 : i8 98, 2 : i8 99 }
global @t : [4 x i8] align 1 = { 0 : i8 97, 1 : i8 98 }
global @o : %Out align 4 = { 0 : i32 1, 4 : i32 2, 8 : i8 104, 9 : i8 101, 10 : i8 121, 16 : i32 7 }
global @p : %Out align 4 = { 20 : i32 5, 4 : i32 3, 8 : i8 113 }
global @q : [2 x %Out] align 4 = { 28 : i32 8, 32 : i32 9, 36 : i8 10 }
global @u : %U align 4 = { 0 : i32 1 }
global @m : [2 x [2 x i32]] align 4 = { 0 : i32 1, 4 : i32 2, 8 : i32 3 }
define @f(i32 %k) -> void {
  [2 x i32] %loc
  %In %in
  %Out %out
  [4 x i8] %name
  [3 x i32] %zero
  ptr %t0
  ptr %t1
  ptr %t2
  i32 %t3
  i32 %t4
  ptr %t5
  ptr %t6
  ptr %t7
  ptr %t8
  ptr %t9
  ptr %t10
  i8 %t11
  ptr %t12
  ptr %t13
  i8 %t14
  ptr %t15
  i8 %t16
  ptr %t17
  i8 %t18
  ptr %t19
.entry:
  %t0 = addrof %loc
  zero [2 x i32] %t0
  %t1 = wadd %t0, 0
  store.32 %t1, %k
  %t2 = wadd %t0, 4
  mov.s32 %t3, 1
  %t4 = add.s32 %k, %t3
  store.32 %t2, %t4
  %t5 = addrof %in
  zero %In %t5
  %t6 = wadd %t5, 4
  store.32 %t6, %k
  %t7 = addrof %out
  zero %Out %t7
  %t8 = wadd %t7, 0
  %t9 = addrof %in
  copy %In %t8, %t9
  %t10 = wadd %t7, 8
  mov.s8 %t11, 122
  store.8 %t10, %t11
  %t12 = addrof %name
  zero [4 x i8] %t12
  %t13 = wadd %t12, 0
  mov.s8 %t14, 108
  store.8 %t13, %t14
  %t15 = wadd %t12, 1
  mov.s8 %t16, 111
  store.8 %t15, %t16
  %t17 = wadd %t12, 2
  mov.s8 %t18, 99
  store.8 %t17, %t18
  %t19 = addrof %zero
  zero [3 x i32] %t19
  ret
}

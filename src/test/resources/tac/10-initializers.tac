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
  i32 %t2
  i32 %t3
  ptr %t4
  ptr %t5
  ptr %t6
  ptr %t7
  ptr %t8
  i8 %t9
  ptr %t10
  i8 %t11
  ptr %t12
  i8 %t13
  ptr %t14
  i8 %t15
  ptr %t16
.entry:
  %t0 = addrof %loc
  zero [2 x i32] %t0
  store.32 %t0, %k
  %t1 = wadd %t0, 4
  mov %t2, 1
  %t3 = add %k, %t2
  store.32 %t1, %t3
  %t4 = addrof %in
  zero %In %t4
  %t5 = wadd %t4, 4
  store.32 %t5, %k
  %t6 = addrof %out
  zero %Out %t6
  %t7 = addrof %in
  copy %In %t6, %t7
  %t8 = wadd %t6, 8
  mov %t9, 122
  store.8 %t8, %t9
  %t10 = addrof %name
  zero [4 x i8] %t10
  mov %t11, 108
  store.8 %t10, %t11
  %t12 = wadd %t10, 1
  mov %t13, 111
  store.8 %t12, %t13
  %t14 = wadd %t10, 2
  mov %t15, 99
  store.8 %t14, %t15
  %t16 = addrof %zero
  zero [3 x i32] %t16
  ret
}

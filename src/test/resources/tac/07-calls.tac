target x86_64-sysv
type %S = { i32 @0 } size 4 align 4
global internal @.str.d153ce427d7ff938 : [9 x i8] align 1 readonly = { 0 : bytes "%d %f %s\00" }
global internal @.str.d98ac5bc9eec51cb : [2 x i8] align 1 readonly = { 0 : bytes "x\00" }
global @table : [2 x ptr] align 8
declare @add(i32, i32) -> i32
declare @half(f64) -> f64
declare @printf(ptr, ...) -> i32
declare @noargs() -> void
declare @mk(i32) -> %S
define @f() -> void {
  i8 %c
  f32 %fl
  i32 %i
  %S %tmp13
  i32 %t0
  i32 %t1
  i32 %t2
  f64 %t3
  f64 %t4
  f32 %t5
  ptr %t6
  i32 %t7
  f64 %t8
  ptr %t9
  i32 %t10
  ptr %t11
  i32 %t12
  i64 %t13
  i64 %t14
  ptr %t15
  ptr %t16
  i32 %t17
  i32 %t18
  i32 %t19
  ptr %t20
  i32 %t21
  i32 %t22
  i32 %t23
.entry:
  mov %t0, %c
  mov %t1, 2
  %t2 = call (i32, i32) -> i32 @add(%t0, %t1)
  mov %i, %t2
  %t3 = i2f %c
  %t4 = call (f64) -> f64 @half(%t3)
  %t5 = fcvt %t4
  mov %fl, %t5
  %t6 = addrof @.str.d153ce427d7ff938
  mov %t7, %c
  %t8 = fcvt %fl
  %t9 = addrof @.str.d98ac5bc9eec51cb
  %t10 = call (ptr, ...) -> i32 @printf(%t6, %t7, %t8, %t9)
  call () -> void @noargs()
  %t11 = addrof @table
  mov %t12, 1
  mov %t13, %t12
  %t13 = shl %t13, 32
  %t13 = ashr %t13, 32
  %t14 = wmul %t13, 8
  %t15 = wadd %t11, %t14
  %t16 = load.u64 %t15
  mov %t17, 1
  mov %t18, 2
  %t19 = icall (i32, i32) -> i32 %t16(%t17, %t18)
  mov %i, %t19
  %t20 = addrof %tmp13
  mov %t21, 1
  call (i32) -> %S @mk(%t21) into %t20
  %t22 = load.s32 %t20
  mov %i, %t22
  %t23 = call (i32, i32) -> i32 @add(%i, %i)
  mov %i, %t23
  ret
}

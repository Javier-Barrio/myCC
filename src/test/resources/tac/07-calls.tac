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
  f64 %t2
  f64 %t3
  f32 %t4
  ptr %t5
  f64 %t6
  ptr %t7
  i32 %t8
  ptr %t9
  i32 %t10
  i64 %t11
  i64 %t12
  ptr %t13
  ptr %t14
  i32 %t15
  i32 %t16
  i32 %t17
  ptr %t18
  i32 %t19
  i32 %t20
  i32 %t21
.entry:
  mov %t0, 2
  %t1 = call (i32, i32) -> i32 @add(%c, %t0)
  mov %i, %t1
  %t2 = i2f %c
  %t3 = call (f64) -> f64 @half(%t2)
  %t4 = fcvt %t3
  mov %fl, %t4
  %t5 = addrof @.str.d153ce427d7ff938
  %t6 = fcvt %fl
  %t7 = addrof @.str.d98ac5bc9eec51cb
  %t8 = call (ptr, ...) -> i32 @printf(%t5, %c, %t6, %t7)
  call () -> void @noargs()
  %t9 = addrof @table
  mov %t10, 1
  mov %t11, %t10
  %t11 = shl %t11, 32
  %t11 = ashr %t11, 32
  %t12 = wmul %t11, 8
  %t13 = wadd %t9, %t12
  %t14 = load.u64 %t13
  mov %t15, 1
  mov %t16, 2
  %t17 = icall (i32, i32) -> i32 %t14(%t15, %t16)
  mov %i, %t17
  %t18 = addrof %tmp13
  mov %t19, 1
  call (i32) -> %S @mk(%t19) into %t18
  %t20 = load.s32 %t18
  mov %i, %t20
  %t21 = call (i32, i32) -> i32 @add(%i, %i)
  mov %i, %t21
  ret
}

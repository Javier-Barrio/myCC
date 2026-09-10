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
  ptr %t6
  i32 %t7
  ptr %t8
  i32 %t9
  i64 %t10
  ptr %t11
  ptr %t12
  i32 %t13
  i32 %t14
  i32 %t15
  ptr %t16
  i32 %t17
  ptr %t18
  i32 %t19
  i32 %t20
.entry:
  mov.s32 %t0, 2
  %t1 = call (i32, i32) -> i32 @add(%c, %t0)
  mov.s32 %i, %t1
  %t2 = i2f.64 %c
  %t3 = call (f64) -> f64 @half(%t2)
  %t4 = fcvt.32 %t3
  mov.32 %fl, %t4
  %t5 = addrof @.str.d153ce427d7ff938
  %t6 = addrof @.str.d98ac5bc9eec51cb
  %t7 = call (ptr, ...) -> i32 @printf(%t5, %c, %fl, %t6)
  call () -> void @noargs()
  %t8 = addrof @table
  mov.s32 %t9, 1
  %t10 = wmul %t9, 8
  %t11 = wadd %t8, %t10
  %t12 = load.u64 %t11
  mov.s32 %t13, 1
  mov.s32 %t14, 2
  %t15 = icall (i32, i32) -> i32 %t12(%t13, %t14)
  mov.s32 %i, %t15
  %t16 = addrof %tmp13
  mov.s32 %t17, 1
  call (i32) -> %S @mk(%t17) into %t16
  %t18 = wadd %t16, 0
  %t19 = load.s32 %t18
  mov.s32 %i, %t19
  %t20 = call (i32, i32) -> i32 @add(%i, %i)
  mov.s32 %i, %t20
  ret
}

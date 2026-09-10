target x86_64-sysv
global @t : i32 align 4
global internal @s : i32 align 4 = { 0 : i32 1 }
global @a : [2 x i32] align 4
global internal @count.static : i32 align 4 = { 0 : i32 0 }
global @later : f64 align 8 = { 0 : f64 2.5 }
declare @e : i32
declare @never_defined : [0 x i32]
define @f() -> void {
  ptr %t0
  i32 %t1
  ptr %t2
  i32 %t3
  i32 %t4
  ptr %t5
  ptr %t6
  i32 %t7
  f64 %t8
.entry:
  %t0 = addrof @count.static
  %t1 = load.s32 %t0
  %t2 = addrof @e
  %t3 = load.s32 %t2
  %t4 = add.s32 %t1, %t3
  store.32 %t0, %t4
  %t5 = addrof @later
  %t6 = addrof @count.static
  %t7 = load.s32 %t6
  %t8 = i2f.64 %t7
  store.f64 %t5, %t8
  ret
}

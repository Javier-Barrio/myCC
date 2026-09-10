target x86_64-sysv
global internal @.str.0af87612f988a9e7 : [3 x i8] align 1 readonly = { 0 : bytes "hi\00" }
global internal @.str.a76ec4e678e95b81 : [2 x i8] align 1 readonly = { 0 : bytes "s\00" }
global @g : i32 align 4 = { 0 : i32 3 }
global @msg : ptr align 8 = { 0 : addr @.str.0af87612f988a9e7 }
global @np : ptr align 8 = { 0 : u64 0 }
global @nq : ptr align 8 = { 0 : u64 0 }
global @fp : ptr align 8
global @d : f64 align 8 = { 0 : f64 0.5 }
global @big : i64 align 8 = { 0 : i64 1000000000000 }
global @u : u32 align 4 = { 0 : u32 255 }
global @c : i8 align 1 = { 0 : i8 120 }
global @pg : ptr align 8 = { 0 : addr @g }
global @pf : ptr align 8 = { 0 : addr @f }
define @f() -> i32 {
  ptr %t0
  i32 %t1
.entry:
  %t0 = addrof @g
  %t1 = load.s32 %t0
  ret %t1
}
define @use() -> void {
  ptr %t0
  i32 %t1
  ptr %t2
  i32 %t3
  f64 %t4
  i32 %t5
  ptr %t6
  ptr %t7
  u8 %t8
.entry:
  %t0 = addrof @g
  %t1 = load.s32 %t0
  %t2 = addrof @f
  mov.s32 %t3, 42
  mov.64 %t4, 1.5
  mov.s32 %t5, 97
  %t6 = addrof @.str.a76ec4e678e95b81
  mov.u64 %t7, 0
  mov.u8 %t8, 1
  ret
}

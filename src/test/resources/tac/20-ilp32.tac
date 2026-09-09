target ilp32
global @l : i32 align 4 = { 0 : i32 2147483647 }
global @ul : u32 align 4 = { 0 : u32 4294967295 }
global @sizes : i32 align 4 = { 0 : i32 44 }
define @f(u32 %u, i32 %l, ptr %p, ptr %q) -> void {
  u32 %z
  i32 %diff
  u8 %c
  i32 %ci
  u32 %t0
  u32 %t1
  i32 %t2
  u32 %t3
  i32 %t4
  i32 %t5
  u8 %t6
  i32 %t7
.entry:
  mov %t0, %l
  %t1 = wadd %u, %t0
  mov %t2, %t1
  mov %l, %t2
  mov %t3, 4
  mov %z, %t3
  %t4 = wsub %p, %q
  %t4 = sdiv %t4, 4
  mov %diff, %t4
  mov %t5, 255
  mov %t6, %t5
  %t6 = and %t6, 255
  mov %c, %t6
  mov %t7, %c
  mov %ci, %t7
  ret
}

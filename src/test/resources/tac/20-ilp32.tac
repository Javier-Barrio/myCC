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
  i32 %t3
  u8 %t4
.entry:
  %t0 = wadd %u, %l
  mov %l, %t0
  mov %t1, 4
  mov %z, %t1
  %t2 = wsub %p, %q
  %t2 = sdiv %t2, 4
  mov %diff, %t2
  mov %t3, 255
  mov %t4, %t3
  %t4 = and %t4, 255
  mov %c, %t4
  mov %ci, %c
  ret
}

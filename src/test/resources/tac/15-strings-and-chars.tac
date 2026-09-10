target x86_64-sysv
global internal @.str.bb6387e49ecda94f : [13 x i8] align 1 readonly = { 0 : bytes "hello, world\00" }
global internal @.str.0bb18fefca4c05a2 : [3 x u8] align 1 readonly = { 0 : bytes "\c3\a9\00" }
global internal @.str.1d9ca2563d0335cb : [3 x u16] align 2 readonly = { 0 : u16 65535, 2 : u16 122, 4 : u16 0 }
global internal @.str.0994cff493d7329c : [2 x u32] align 4 readonly = { 0 : u32 97, 4 : u32 0 }
global internal @.str.e86deaca6d100ae7 : [5 x i32] align 4 readonly = { 0 : i32 119, 4 : i32 105, 8 : i32 100, 12 : i32 101, 16 : i32 0 }
global internal @.str.9fc04ac1b91e3774 : [7 x i8] align 1 readonly = { 0 : bytes "unused\00" }
global @g : ptr align 8 = { 0 : addr @.str.bb6387e49ecda94f }
global @u8 : ptr align 8 = { 0 : addr @.str.0bb18fefca4c05a2 }
global @u16 : ptr align 8 = { 0 : addr @.str.1d9ca2563d0335cb }
global @u32 : ptr align 8 = { 0 : addr @.str.0994cff493d7329c }
global @w : ptr align 8 = { 0 : addr @.str.e86deaca6d100ae7 }
global @lens : i32 align 4 = { 0 : i32 36 }
global @chars : i32 align 4 = { 0 : i32 709 }
define @f() -> void {
  ptr %t0
  u64 %t1
.entry:
  %t0 = addrof @.str.9fc04ac1b91e3774
  mov.u64 %t1, 14
  ret
}

package org.jbm.mycc.cc.backend.arch.x86_64;

import java.util.List;

/** The SysV calling convention's fixed facts: the argument registers and the register names by width. */
public final class X86Abi {

    private X86Abi() {
    }

    /** Integer and pointer arguments, in order. */
    public static final List<String> INT_ARGS = List.of("rdi", "rsi", "rdx", "rcx", "r8", "r9");

    /** Floating arguments, in order. */
    public static final List<String> FLOAT_ARGS = List.of("xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7");

    /** The name of a 64-bit register's part of {@code width} bits: rax, eax, ax, al; r8, r8d, r8w, r8b. */
    public static String part(String reg, int width) {
        if (width == 64) {
            return reg;
        }
        if (reg.startsWith("r") && Character.isDigit(reg.charAt(1))) {
            return reg + switch (width) {
                case 32 -> "d";
                case 16 -> "w";
                default -> "b";
            };
        }
        String base = reg.substring(1);   // "ax" of "rax", "di" of "rdi"
        return switch (width) {
            case 32 -> "e" + base;
            case 16 -> base;
            default -> base.endsWith("x") ? base.charAt(0) + "l" : base + "l";   // al, dl, sil, dil
        };
    }

    /** The mnemonic suffix for a width. */
    public static String suffix(int width) {
        return switch (width) {
            case 8 -> "b";
            case 16 -> "w";
            case 32 -> "l";
            default -> "q";
        };
    }
}

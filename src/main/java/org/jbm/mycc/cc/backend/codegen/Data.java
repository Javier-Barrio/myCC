package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.backend.lower.tac.Global;
import org.jbm.mycc.cc.backend.lower.tac.Linkage;
import org.jbm.mycc.cc.backend.lower.tac.Module;

import java.util.TreeMap;

/**
 * The globals as directives. An initialized object is a byte image
 * built from its items as the VM's loader builds it, with each address
 * item a {@code .quad name+addend} at its offset; read-only images go
 * to {@code .rodata}; an object without an initializer is {@code .zero}
 * in {@code .bss}.
 */
final class Data {

    private Data() {
    }

    static void emit(Asm asm, Module module) {
        String section = "";
        for (Global g : module.globals) {
            // Read-only data holding an address is relocated by the dynamic
            // linker, so it goes where gcc puts it, .data.rel.ro, and not in
            // .rodata, which a position-independent executable cannot patch.
            boolean relocated = g.init() != null && g.init().stream().anyMatch(item -> item instanceof Global.AddrItem);
            String wanted = g.init() == null ? ".bss" : !g.readonly() ? ".data" : relocated ? ".section .data.rel.ro" : ".section .rodata";
            if (!wanted.equals(section)) {
                asm.section(wanted);
                section = wanted;
            }
            if (g.linkage() == Linkage.EXTERNAL) {
                asm.global(g.name());
            }
            asm.align(Math.max(g.align(), 1));
            asm.label(g.name());
            long size = module.imageSize(g);
            if (g.init() == null) {
                asm.zero(size);
                continue;
            }
            byte[] image = new byte[(int) size];
            TreeMap<Long, Operand.Sym> addresses = new TreeMap<>();
            for (Global.Item item : g.init()) {
                apply(image, item, addresses);
            }
            emitImage(asm, image, addresses);
        }
    }

    private static void apply(byte[] image, Global.Item item, TreeMap<Long, Operand.Sym> addresses) {
        int at = (int) item.offset();
        if (item instanceof Global.IntItem x) {
            putInt(image, at, x.type().width() / 8, x.value());
        } else if (item instanceof Global.FloatItem x) {
            if (x.type().width() == 32) {
                putInt(image, at, 4, Float.floatToRawIntBits((float) x.value()));
            } else {
                putInt(image, at, 8, Double.doubleToRawLongBits(x.value()));
            }
        } else if (item instanceof Global.BytesItem x) {
            System.arraycopy(x.bytes(), 0, image, at, x.bytes().length);
        } else if (item instanceof Global.AddrItem x) {
            addresses.put(item.offset(), Operand.sym(x.name(), x.addend()));
        } else if (item instanceof Global.BitItem x) {
            for (int k = 0; k < x.width(); k++) {
                int bit = x.bit() + k;
                int index = at + bit / 8;
                int mask = 1 << (bit % 8);
                if (((x.value() >> k) & 1) != 0) {
                    image[index] |= (byte) mask;
                } else {
                    image[index] &= (byte) ~mask;
                }
            }
        }
    }

    private static void putInt(byte[] image, int at, int count, long value) {
        long v = value;
        for (int k = 0; k < count; k++) {
            image[at + k] = (byte) v;
            v >>= 8;
        }
    }

    // Byte runs of at most 16, an address item as a word where it lies.
    private static void emitImage(Asm asm, byte[] image, TreeMap<Long, Operand.Sym> addresses) {
        int at = 0;
        while (at < image.length) {
            Operand.Sym symbol = addresses.get((long) at);
            if (symbol != null) {
                asm.address(symbol);
                at += 8;
                continue;
            }
            Long next = addresses.ceilingKey((long) at);
            int end = (int) Math.min(next == null ? image.length : next, at + 16);
            asm.bytes(java.util.Arrays.copyOfRange(image, at, end));
            at = end;
        }
    }
}

package org.jbm.mycc.cc.codegen;

import org.jbm.mycc.cc.lower.tac.Global;
import org.jbm.mycc.cc.lower.tac.Linkage;
import org.jbm.mycc.cc.lower.tac.Module;

import java.util.ArrayList;
import java.util.List;
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
            String wanted = g.init() == null ? ".bss" : g.readonly() ? ".section .rodata" : ".data";
            if (!wanted.equals(section)) {
                asm.directive(wanted);
                section = wanted;
            }
            if (g.linkage() == Linkage.EXTERNAL) {
                asm.directive(".globl " + g.name());
            }
            asm.directive(".balign " + Math.max(g.align(), 1));
            asm.label(g.name());
            long size = module.sizeOf(g.type());
            if (g.init() == null) {
                asm.directive(".zero " + size);
                continue;
            }
            byte[] image = new byte[(int) size];
            TreeMap<Long, String> addresses = new TreeMap<>();
            for (Global.Item item : g.init()) {
                apply(image, item, addresses);
            }
            emitImage(asm, image, addresses);
        }
    }

    private static void apply(byte[] image, Global.Item item, TreeMap<Long, String> addresses) {
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
            String addend = x.addend() == 0 ? "" : x.addend() > 0 ? "+" + x.addend() : String.valueOf(x.addend());
            addresses.put(item.offset(), x.name() + addend);
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

    // Byte runs of at most 16, an address item as a quad where it lies.
    private static void emitImage(Asm asm, byte[] image, TreeMap<Long, String> addresses) {
        int at = 0;
        while (at < image.length) {
            String symbol = addresses.get((long) at);
            if (symbol != null) {
                asm.directive(".quad " + symbol);
                at += 8;
                continue;
            }
            Long next = addresses.ceilingKey((long) at);
            int end = (int) Math.min(next == null ? image.length : next, at + 16);
            List<String> bytes = new ArrayList<>();
            for (int k = at; k < end; k++) {
                bytes.add(Integer.toString(image[k] & 0xff));
            }
            asm.directive(".byte " + String.join(", ", bytes));
            at = end;
        }
    }
}

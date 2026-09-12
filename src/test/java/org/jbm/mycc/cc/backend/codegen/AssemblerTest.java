package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.backend.arch.x86_64.X86Encoder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.jbm.mycc.cc.backend.codegen.Operand.imm;
import static org.jbm.mycc.cc.backend.codegen.Operand.plt;
import static org.jbm.mycc.cc.backend.codegen.Operand.reg;
import static org.jbm.mycc.cc.backend.codegen.Operand.rip;
import static org.jbm.mycc.cc.backend.codegen.Operand.sym;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Labels forward, back and numeric; jumps at the shortest form that reaches; symbols and relocations. */
class AssemblerTest {

    static ObjectFile assemble(Item... items) {
        return new Assembler(new X86Encoder()).assemble(List.of(items));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    @Test
    void labelsAndShortJumps() {
        ObjectFile o = assemble(
                new Item.Section(".text"),
                new Item.Label("f"),
                new Item.Insn("jmp", List.of(sym(".L_end"))),
                new Item.Label("1"),
                new Item.Insn("ret", List.of()),
                new Item.Insn("jne", List.of(sym("1f"))),
                new Item.Insn("js", List.of(sym("f"))),
                new Item.Label("1"),
                new Item.Label(".L_end"),
                new Item.Insn("ret", List.of()));
        // jmp +5 (eb 05), ret, jne +2 (75 02), js -7 (78 f9), ret
        assertEquals("eb05c375027" + "8f9c3", hex(o.section(".text").bytes()));
        assertEquals(List.of("f"), o.symbols().stream().map(ObjectFile.Symbol::name).toList(), "labels the assembler owns are not symbols");
        assertTrue(o.relocations().isEmpty());
    }

    @Test
    void aFarJumpGrows() {
        Item[] items = new Item[140];
        items[0] = new Item.Section(".text");
        items[1] = new Item.Insn("jmp", List.of(sym(".L_far")));
        for (int k = 2; k < 138; k++) {
            items[k] = new Item.Insn("ret", List.of());
        }
        items[138] = new Item.Label(".L_far");
        items[139] = new Item.Insn("ret", List.of());
        ObjectFile o = assemble(items);
        byte[] text = o.section(".text").bytes();
        assertEquals((byte) 0xE9, text[0], "136 bytes of ret do not fit an 8-bit displacement");
        assertEquals(136, text[1] & 0xff);
        assertEquals(5 + 136 + 1, text.length);
    }

    @Test
    void symbolsAndRelocations() {
        ObjectFile o = assemble(
                new Item.Section(".text"),
                new Item.Global("main"),
                new Item.Label("main"),
                new Item.Insn("call", List.of(plt("printf"))),
                new Item.Insn("call", List.of(sym("helper"))),
                new Item.Insn("leaq", List.of(rip("counter"), reg("rax"))),
                new Item.Insn("leaq", List.of(rip(".LC0"), reg("rax"))),
                new Item.Insn("movq", List.of(Operand.got("stdout"), reg("rax"))),
                new Item.Insn("ret", List.of()),
                new Item.Label("helper"),
                new Item.Insn("ret", List.of()),
                new Item.Section(".data"),
                new Item.Global("counter"),
                new Item.Align(4),
                new Item.Label("counter"),
                new Item.Bytes(new byte[] {7, 0, 0, 0}),
                new Item.Label("table"),
                new Item.Address(sym("counter", 2)),
                new Item.Address(sym("helper")),
                new Item.Section(".section .rodata"),
                new Item.Label(".LC0"),
                new Item.Word(1, "one"),
                new Item.Section(".bss"),
                new Item.Global("zeros"),
                new Item.Label("zeros"),
                new Item.Zero(16));
        List<String> names = o.symbols().stream().map(s -> s.name() + (s.global() ? "!" : "") + "@" + s.section()).toList();
        assertEquals(List.of("main!@.text", "helper@.text", "counter!@.data", "table@.data", "zeros!@.bss"), names);
        List<String> relocs = o.relocations().stream()
                .map(r -> r.section() + "+" + r.offset() + " " + r.type() + " " + r.symbol() + (r.addend() >= 0 ? "+" : "") + r.addend()).toList();
        assertEquals(List.of(
                ".text+1 R_X86_64_PLT32 printf-4",
                ".text+13 R_X86_64_PC32 counter-4",
                ".text+20 R_X86_64_PC32 .rodata-4",
                ".text+27 R_X86_64_REX_GOTPCRELX stdout-4",
                ".data+4 R_X86_64_64 counter+2",
                ".data+12 R_X86_64_64 .text+32"), relocs,
                "a global stays a relocation against itself; a local one in another section goes against that section");
        byte[] text = o.section(".text").bytes();
        assertEquals(0x16, text[6] & 0xff, "call helper resolved within the section: helper at 32, the call ends at 10");
        assertEquals(16, o.section(".bss").size());
        assertEquals(0, o.section(".bss").bytes().length);
        assertEquals(4, o.section(".data").align());
    }

    @Test
    void theElfIsWellFormed() {
        ObjectFile o = assemble(new Item.Section(".text"), new Item.Global("f"), new Item.Label("f"), new Item.Insn("ret", List.of()));
        byte[] elf = Elf64.write(o);
        assertArrayEquals(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1}, java.util.Arrays.copyOf(elf, 7));
        assertEquals(1, elf[16], "ET_REL");
        assertEquals(0x3E, elf[18], "x86-64");
    }
}

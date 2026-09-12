package org.jbm.mycc.cc.backend.codegen;

import lombok.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An {@link ObjectFile} as an ELF64 relocatable for x86-64 Linux: the
 * header, the content sections, a {@code .rela} for each that has
 * relocations, {@code .symtab}, {@code .strtab}, {@code .shstrtab},
 * and {@code .note.GNU-stack}; the section headers last.
 */
public final class Elf64 {

    private Elf64() {
    }

    // One section as written: its header fields and its bytes.
    private record Out(String name, int type, long flags, byte[] bytes, long size, int link, int info, int align, long entsize) {
    }

    private static final int SHT_PROGBITS = 1;
    private static final int SHT_SYMTAB = 2;
    private static final int SHT_STRTAB = 3;
    private static final int SHT_RELA = 4;
    private static final int SHT_NOBITS = 8;
    private static final long SHF_WRITE = 1;
    private static final long SHF_ALLOC = 2;
    private static final long SHF_EXECINSTR = 4;
    private static final long SHF_INFO_LINK = 0x40;

    public static byte[] write(@NonNull ObjectFile object) {
        List<Out> outs = new ArrayList<>();
        outs.add(new Out("", 0, 0, new byte[0], 0, 0, 0, 0, 0));
        Map<String, Integer> sectionIndex = new LinkedHashMap<>();
        for (ObjectFile.Section s : object.sections()) {
            long flags = SHF_ALLOC | (s.writable() ? SHF_WRITE : 0) | (s.code() ? SHF_EXECINSTR : 0);
            sectionIndex.put(s.name(), outs.size());
            outs.add(new Out(s.name(), s.bss() ? SHT_NOBITS : SHT_PROGBITS, flags, s.bytes(), s.size(), 0, 0, s.align(), 0));
        }

        // symbols: null, one per section, the locals, then the globals; a
        // name a relocation needs that is defined nowhere is undefined
        StringTable strtab = new StringTable();
        List<byte[]> symbols = new ArrayList<>();
        Map<String, Integer> symbolIndex = new LinkedHashMap<>();
        symbols.add(new byte[24]);
        for (ObjectFile.Section s : object.sections()) {
            symbolIndex.put(s.name(), symbols.size());
            symbols.add(symbol(0, 0, 3, sectionIndex.get(s.name()), 0, 0));
        }
        List<ObjectFile.Symbol> ordered = new ArrayList<>();
        for (ObjectFile.Symbol s : object.symbols()) {
            if (!s.global()) {
                ordered.add(s);
            }
        }
        int firstGlobal = symbols.size() + ordered.size();
        Set<String> named = new LinkedHashSet<>();
        for (ObjectFile.Symbol s : object.symbols()) {
            named.add(s.name());
            if (s.global()) {
                ordered.add(s);
            }
        }
        for (ObjectFile.Relocation r : object.relocations()) {
            if (!named.contains(r.symbol()) && !sectionIndex.containsKey(r.symbol())) {
                named.add(r.symbol());
                ordered.add(new ObjectFile.Symbol(r.symbol(), null, 0, 0, true, ObjectFile.Kind.NOTYPE));
            }
        }
        for (ObjectFile.Symbol s : ordered) {
            symbolIndex.put(s.name(), symbols.size());
            int binding = s.global() ? 1 : 0;
            int type = switch (s.kind()) {
                case FUNC -> 2;
                case OBJECT -> 1;
                case SECTION -> 3;
                default -> 0;
            };
            int shndx = s.section() == null ? 0 : sectionIndex.get(s.section());
            symbols.add(symbol(strtab.add(s.name()), binding, type, shndx, s.offset(), s.size()));
        }
        int symtabIndex = outs.size();
        outs.add(new Out(".symtab", SHT_SYMTAB, 0, concat(symbols), 0, symtabIndex + 1, firstGlobal, 8, 24));
        outs.add(new Out(".strtab", SHT_STRTAB, 0, strtab.bytes(), 0, 0, 0, 1, 0));

        // relocations, one .rela per section that has any
        for (ObjectFile.Section s : object.sections()) {
            List<byte[]> entries = new ArrayList<>();
            for (ObjectFile.Relocation r : object.relocations()) {
                if (r.section().equals(s.name())) {
                    ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                    b.putLong(r.offset());
                    b.putLong(((long) symbolIndex.get(r.symbol()) << 32) | r.type().code);
                    b.putLong(r.addend());
                    entries.add(b.array());
                }
            }
            if (!entries.isEmpty()) {
                outs.add(new Out(".rela" + s.name(), SHT_RELA, SHF_INFO_LINK, concat(entries), 0, symtabIndex, sectionIndex.get(s.name()), 8, 24));
            }
        }
        outs.add(new Out(".note.GNU-stack", SHT_PROGBITS, 0, new byte[0], 0, 0, 0, 1, 0));
        StringTable shstrtab = new StringTable();
        int[] names = new int[outs.size() + 1];
        for (int k = 1; k < outs.size(); k++) {
            names[k] = shstrtab.add(outs.get(k).name());
        }
        names[outs.size()] = shstrtab.add(".shstrtab");
        outs.add(new Out(".shstrtab", SHT_STRTAB, 0, shstrtab.bytes(), 0, 0, 0, 1, 0));

        // the file: header, contents, section headers
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[64], 0, 64);
        long[] offsets = new long[outs.size()];
        for (int k = 1; k < outs.size(); k++) {
            Out o = outs.get(k);
            int align = Math.max(o.align(), 1);
            while (file.size() % align != 0) {
                file.write(0);
            }
            offsets[k] = file.size();
            file.write(o.bytes(), 0, o.bytes().length);
        }
        while (file.size() % 8 != 0) {
            file.write(0);
        }
        long shoff = file.size();
        for (int k = 0; k < outs.size(); k++) {
            Out o = outs.get(k);
            ByteBuffer h = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(names[k]);
            h.putInt(o.type());
            h.putLong(o.flags());
            h.putLong(0);
            h.putLong(k == 0 ? 0 : offsets[k]);
            h.putLong(o.type() == SHT_NOBITS ? o.size() : o.bytes().length);
            h.putInt(o.link());
            h.putInt(o.info());
            h.putLong(o.align());
            h.putLong(o.entsize());
            file.write(h.array(), 0, 64);
        }
        byte[] all = file.toByteArray();
        ByteBuffer header = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        header.putShort((short) 1);        // ET_REL
        header.putShort((short) 0x3E);     // x86-64
        header.putInt(1);
        header.putLong(0);                 // entry
        header.putLong(0);                 // program headers
        header.putLong(shoff);
        header.putInt(0);                  // flags
        header.putShort((short) 64);
        header.putShort((short) 0);
        header.putShort((short) 0);
        header.putShort((short) 64);
        header.putShort((short) outs.size());
        header.putShort((short) (outs.size() - 1));
        return all;
    }

    private static byte[] symbol(int name, int binding, int type, int shndx, long value, long size) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(name);
        b.put((byte) ((binding << 4) | type));
        b.put((byte) 0);
        b.putShort((short) shndx);
        b.putLong(value);
        b.putLong(size);
        return b.array();
    }

    private static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static final class StringTable {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        StringTable() {
            bytes.write(0);
        }

        int add(String s) {
            int at = bytes.size();
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            bytes.write(b, 0, b.length);
            bytes.write(0);
            return at;
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}

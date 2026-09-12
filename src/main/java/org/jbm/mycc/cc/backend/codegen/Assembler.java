package org.jbm.mycc.cc.backend.codegen;

import lombok.NonNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The assembly IR to an {@link ObjectFile}: each section's bytes laid
 * out with its labels, jumps within a section at the shortest form
 * that reaches, symbol references resolved when the symbol is local
 * to the object and left as relocations otherwise.
 */
public final class Assembler {

    private final Encoder encoder;

    public Assembler(@NonNull Encoder encoder) {
        this.encoder = encoder;
    }

    // ---- what a section holds before its bytes are final -----------------------------------------

    private sealed interface Chunk permits Fixed, JumpChunk, LabelChunk {
    }

    private record Fixed(byte[] bytes, List<Encoder.Fixup> fixups) implements Chunk {
    }

    private record JumpChunk(Encoder.Jump jump) implements Chunk {
    }

    private record LabelChunk(String name) implements Chunk {
    }

    private static final class Section {
        final String name;
        final List<Chunk> chunks = new ArrayList<>();
        int align = 1;
        long bss;

        Section(String name) {
            this.name = name;
        }

        boolean isBss() {
            return name.equals(".bss");
        }
    }

    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final Set<String> globals = new LinkedHashSet<>();

    public ObjectFile assemble(@NonNull List<Item> items) {
        Section current = section(".text");
        for (Item item : items) {
            if (item instanceof Item.Section s) {
                current = section(s.name().startsWith(".section ") ? s.name().substring(9).split(",")[0] : s.name());
            } else if (item instanceof Item.Label l) {
                current.chunks.add(new LabelChunk(l.name()));
            } else if (item instanceof Item.Global g) {
                globals.add(g.name());
            } else if (item instanceof Item.Align a) {
                current.align = Math.max(current.align, a.bytes());
                current.chunks.add(new Fixed(new byte[0], List.of()));   // the alignment is applied at layout
                current.chunks.add(new LabelChunk(".align" + a.bytes()));
            } else if (item instanceof Item.Bytes b) {
                current.chunks.add(new Fixed(b.bytes(), List.of()));
            } else if (item instanceof Item.Word w) {
                current.chunks.add(new Fixed(littleEndian(w.value(), 8), List.of()));
            } else if (item instanceof Item.Address a) {
                current.chunks.add(new Fixed(new byte[8], List.of(new Encoder.Fixup(0, Encoder.Fix.ABS64, a.symbol().name(), a.symbol().addend()))));
            } else if (item instanceof Item.Zero z) {
                if (current.isBss()) {
                    current.bss += z.bytes();
                    current.chunks.add(new Fixed(new byte[0], List.of()));
                    current.chunks.add(new LabelChunk(".zero" + z.bytes()));
                } else {
                    current.chunks.add(new Fixed(new byte[(int) z.bytes()], List.of()));
                }
            } else if (item instanceof Item.Insn insn) {
                var jump = encoder.jump(insn);
                if (jump.isPresent()) {
                    current.chunks.add(new JumpChunk(jump.get()));
                } else {
                    Encoder.Encoded e = encoder.encode(insn);
                    current.chunks.add(new Fixed(e.bytes(), e.fixups()));
                }
            }
        }
        return layout();
    }

    private Section section(String name) {
        return sections.computeIfAbsent(name, Section::new);
    }

    // ---- layout --------------------------------------------------------------------------------

    // A section laid out: label offsets, and each chunk's offset.
    private static final class Laid {
        final Map<String, Long> labels = new LinkedHashMap<>();
        final List<Long> offsets = new ArrayList<>();
        final List<Boolean> longJump = new ArrayList<>();
        long size;
    }

    // Offsets with the jumps at their current sizes; numeric labels and
    // alignment marks are laid like the rest.
    private Laid lay(Section s, List<Boolean> longJump) {
        Laid laid = new Laid();
        long at = 0;
        int j = 0;
        for (Chunk c : s.chunks) {
            if (c instanceof LabelChunk l && l.name().startsWith(".align")) {
                int a = Integer.parseInt(l.name().substring(6));
                at = (at + a - 1) / a * a;
                laid.offsets.add(at);
                continue;
            }
            if (c instanceof LabelChunk l && l.name().startsWith(".zero")) {
                laid.offsets.add(at);
                at += Long.parseLong(l.name().substring(5));
                continue;
            }
            laid.offsets.add(at);
            if (c instanceof LabelChunk l) {
                laid.labels.putIfAbsent(l.name(), at);
            } else if (c instanceof Fixed f) {
                at += f.bytes().length;
            } else {
                at += longJump.get(j++) ? ((JumpChunk) c).jump().longOpcode().length + 4 : 2;
            }
        }
        laid.size = at;
        laid.longJump.addAll(longJump);
        return laid;
    }

    // The target of a jump: a numeric label is the next definition after the jump.
    private long target(Section s, Laid laid, int chunkIndex, String name) {
        if (name.endsWith("f") && Character.isDigit(name.charAt(0))) {
            String number = name.substring(0, name.length() - 1);
            for (int k = chunkIndex + 1; k < s.chunks.size(); k++) {
                if (s.chunks.get(k) instanceof LabelChunk l && l.name().equals(number)) {
                    return laid.offsets.get(k);
                }
            }
            throw new IllegalStateException("no label " + number + " after a reference to " + name);
        }
        Long at = laid.labels.get(name);
        if (at == null) {
            throw new IllegalStateException("jump to " + name + ", which is not a label of " + s.name);
        }
        return at;
    }

    private ObjectFile layout() {
        List<ObjectFile.Section> out = new ArrayList<>();
        List<ObjectFile.Relocation> relocations = new ArrayList<>();
        Map<String, Map<String, Long>> labelsBySection = new LinkedHashMap<>();
        Map<String, Laid> laidBySection = new LinkedHashMap<>();
        // pass 1: every section's layout, jumps relaxed to a fixed point
        for (Section s : sections.values()) {
            List<Boolean> longJump = new ArrayList<>();
            for (Chunk c : s.chunks) {
                if (c instanceof JumpChunk) {
                    longJump.add(false);
                }
            }
            Laid laid;
            while (true) {
                laid = lay(s, longJump);
                boolean changed = false;
                int j = 0;
                for (int k = 0; k < s.chunks.size(); k++) {
                    if (s.chunks.get(k) instanceof JumpChunk jc) {
                        long end = laid.offsets.get(k) + 2;
                        long disp = target(s, laid, k, jc.jump().target()) - end;
                        if (!longJump.get(j) && disp != (byte) disp) {
                            longJump.set(j, true);
                            changed = true;
                        }
                        j++;
                    }
                }
                if (!changed) {
                    break;
                }
            }
            laidBySection.put(s.name, laid);
            labelsBySection.put(s.name, laid.labels);
        }
        // pass 2: the bytes, with references resolved or kept as relocations
        for (Section s : sections.values()) {
            Laid laid = laidBySection.get(s.name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int j = 0;
            for (int k = 0; k < s.chunks.size(); k++) {
                Chunk c = s.chunks.get(k);
                long at = laid.offsets.get(k);
                while (bytes.size() < at) {
                    bytes.write(0);
                }
                if (c instanceof Fixed f) {
                    byte[] b = f.bytes().clone();
                    for (Encoder.Fixup fx : f.fixups()) {
                        resolve(s.name, at + fx.offset(), fx, b, labelsBySection, relocations);
                    }
                    bytes.write(b, 0, b.length);
                } else if (c instanceof JumpChunk jc) {
                    boolean wide = laid.longJump.get(j++);
                    byte[] opcode = wide ? jc.jump().longOpcode() : jc.jump().shortOpcode();
                    long end = at + opcode.length + (wide ? 4 : 1);
                    long disp = target(s, laid, k, jc.jump().target()) - end;
                    bytes.write(opcode, 0, opcode.length);
                    byte[] d = littleEndian(disp, wide ? 4 : 1);
                    bytes.write(d, 0, d.length);
                }
            }
            boolean code = s.name.equals(".text");
            boolean writable = s.name.equals(".data") || s.isBss();
            long size = s.isBss() ? laid.size : bytes.size();
            out.add(new ObjectFile.Section(s.name, s.isBss() ? new byte[0] : bytes.toByteArray(), size, s.align, code, writable, s.isBss()));
        }
        return new ObjectFile(out, symbols(labelsBySection), relocations);
    }

    // A reference to a symbol of this object that is not global is
    // resolved here when it is in the same section, or turned into a
    // relocation against the section it is in; a global or an undefined
    // symbol always becomes a relocation against itself.
    private void resolve(String section, long at, Encoder.Fixup fx, byte[] bytes, Map<String, Map<String, Long>> labels,
                         List<ObjectFile.Relocation> relocations) {
        String definedIn = null;
        long offset = 0;
        for (var e : labels.entrySet()) {
            Long o = e.getValue().get(fx.symbol());
            if (o != null) {
                definedIn = e.getKey();
                offset = o;
                break;
            }
        }
        boolean local = definedIn != null && !globals.contains(fx.symbol());
        boolean address = fx.fix() == Encoder.Fix.ABS64;
        if (address) {
            if (local) {
                relocations.add(new ObjectFile.Relocation(section, at, ObjectFile.RelocType.R_X86_64_64, definedIn, offset + fx.addend()));
            } else {
                relocations.add(new ObjectFile.Relocation(section, at, ObjectFile.RelocType.R_X86_64_64, fx.symbol(), fx.addend()));
            }
            return;
        }
        if (fx.fix() == Encoder.Fix.GOTPCREL) {
            relocations.add(new ObjectFile.Relocation(section, at, ObjectFile.RelocType.R_X86_64_REX_GOTPCRELX, fx.symbol(), fx.addend()));
            return;
        }
        if (local && definedIn.equals(section)) {
            long disp = offset + fx.addend() - at;
            byte[] d = littleEndian(disp, 4);
            System.arraycopy(d, 0, bytes, fx.offset(), 4);
            return;
        }
        ObjectFile.RelocType type = fx.fix() == Encoder.Fix.PLT32 ? ObjectFile.RelocType.R_X86_64_PLT32 : ObjectFile.RelocType.R_X86_64_PC32;
        if (local) {
            relocations.add(new ObjectFile.Relocation(section, at, type, definedIn, offset + fx.addend()));
        } else {
            relocations.add(new ObjectFile.Relocation(section, at, type, fx.symbol(), fx.addend()));
        }
    }

    // Labels become symbols, except the assembler's own: `.L` names, the
    // numeric labels, and the layout marks. Globals are the names a
    // Global item gave; a referenced name defined nowhere is undefined.
    private List<ObjectFile.Symbol> symbols(Map<String, Map<String, Long>> labels) {
        List<ObjectFile.Symbol> out = new ArrayList<>();
        Set<String> defined = new LinkedHashSet<>();
        for (var e : labels.entrySet()) {
            for (var l : e.getValue().entrySet()) {
                String name = l.getKey();
                if (name.startsWith(".L") || name.startsWith(".align") || name.startsWith(".zero") || Character.isDigit(name.charAt(0))) {
                    continue;
                }
                defined.add(name);
                ObjectFile.Kind kind = e.getKey().equals(".text") ? ObjectFile.Kind.FUNC : ObjectFile.Kind.OBJECT;
                out.add(new ObjectFile.Symbol(name, e.getKey(), l.getValue(), 0, globals.contains(name), kind));
            }
        }
        for (String g : globals) {
            if (!defined.contains(g)) {
                out.add(new ObjectFile.Symbol(g, null, 0, 0, true, ObjectFile.Kind.NOTYPE));
            }
        }
        return out;
    }

    static byte[] littleEndian(long value, int size) {
        byte[] b = new byte[size];
        for (int k = 0; k < size; k++) {
            b[k] = (byte) (value >> (8 * k));
        }
        return b;
    }
}

package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.backend.arch.x86_64.X86Encoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every instruction form the emitter produces, encoded and compared
 * with the bytes GNU as gave for the same text, listed in
 * {@code src/test/resources/x86/forms.txt}. A field a symbol fills is
 * compared as zero on both sides.
 */
class X86EncoderTest {

    static Stream<String> forms() throws IOException {
        return Files.readAllLines(Path.of("src/test/resources/x86/forms.txt")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forms")
    void matchesAs(String line) {
        String[] parts = line.split("  ", 2);
        String expected = parts[0];
        Item.Insn insn = parse(parts[1].strip());
        X86Encoder encoder = new X86Encoder();
        byte[] ours;
        List<Encoder.Fixup> fixups;
        if (encoder.jump(insn).isPresent()) {
            Encoder.Jump j = encoder.jump(insn).get();
            ours = j.longOpcode();
            fixups = List.of();
        } else {
            Encoder.Encoded e = encoder.encode(insn);
            ours = e.bytes();
            fixups = e.fixups();
        }
        byte[] theirs = hex(expected);
        for (Encoder.Fixup f : fixups) {
            for (int k = 0; k < 4 && f.offset() + k < theirs.length; k++) {
                theirs[f.offset() + k] = 0;
                ours[f.offset() + k] = 0;
            }
        }
        assertEquals(expected.length() / 2 == theirs.length ? toHex(theirs) : expected, toHex(ours), parts[1]);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int k = 0; k < out.length; k++) {
            out[k] = (byte) Integer.parseInt(s.substring(2 * k, 2 * k + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ---- AT&T text to IR, for the forms in the table -----------------------------------------------

    private static final Pattern MEM = Pattern.compile("(-?\\d+)?\\(%(\\w+)\\)");

    static Item.Insn parse(String text) {
        String mnemonic;
        String rest;
        if (text.startsWith("rep ")) {
            return new Item.Insn(text, List.of());
        }
        int space = text.indexOf(' ');
        if (space < 0) {
            return new Item.Insn(text, List.of());
        }
        mnemonic = text.substring(0, space);
        rest = text.substring(space + 1);
        List<Operand> ops = new ArrayList<>();
        for (String o : rest.split(",\\s*")) {
            o = o.strip();
            if (o.startsWith("*%")) {
                mnemonic = mnemonic.equals("call") ? "icall" : "ijmp";
                ops.add(Operand.reg(o.substring(2)));
            } else if (o.startsWith("%")) {
                ops.add(Operand.reg(o.substring(1)));
            } else if (o.startsWith("$")) {
                ops.add(Operand.imm(Long.parseLong(o.substring(1))));
            } else if (o.endsWith("@GOTPCREL(%rip)")) {
                ops.add(Operand.got(o.substring(0, o.indexOf('@'))));
            } else if (o.endsWith("(%rip)")) {
                ops.add(Operand.rip(o.substring(0, o.indexOf('('))));
            } else if (o.endsWith("@PLT")) {
                ops.add(Operand.plt(o.substring(0, o.indexOf('@'))));
            } else {
                Matcher m = MEM.matcher(o);
                if (m.matches()) {
                    long disp = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
                    ops.add(Operand.mem(disp, m.group(2)));
                } else {
                    ops.add(Operand.sym(o));
                }
            }
        }
        return new Item.Insn(mnemonic, ops);
    }
}

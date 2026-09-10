package org.jbm.cc.sema.types;

import org.jbm.cc.lower.arch.Ilp32;
import org.jbm.cc.lower.arch.X86_64SysV;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Struct and union layout against the numbers GCC produces for the
 * x86-64 SysV ABI, and against the ILP32 test target to check the
 * algorithm only asks the target.
 */
class LayoutTest {

    private static Layout.Field f(String name, CType t) {
        return new Layout.Field(Optional.of(name), t, OptionalInt.empty());
    }

    private static Layout.Field bf(String name, CType t, int width) {
        return new Layout.Field(Optional.ofNullable(name), t, OptionalInt.of(width));
    }

    private static String describe(Layout l) {
        var sb = new StringBuilder("size " + l.size() + " align " + l.align());
        l.members().values().forEach(m -> sb.append(" ").append(m.name()).append("@").append(m.offset())
                .append(m.bits().map(b -> ":" + b.bitOffset() + "/" + b.width()).orElse("")));
        return sb.toString();
    }

    @Test
    void bitFieldsOnX86_64() {
        var t = new Types(X86_64SysV.INSTANCE);
        assertEquals("size 4 align 4 a@0:0/3 b@0:3/5", describe(Layout.of(t, false, List.of(bf("a", t.int_(), 3), bf("b", t.int_(), 5)))));
        assertEquals("size 4 align 4 c@0 b@0:8/4", describe(Layout.of(t, false, List.of(f("c", t.char_()), bf("b", t.int_(), 4)))),
                "packs into the int unit that holds bit 8");
        assertEquals("size 8 align 4 a@0:0/30 b@4:0/4", describe(Layout.of(t, false, List.of(bf("a", t.int_(), 30), bf("b", t.int_(), 4)))),
                "no straddling: b moves to the next unit");
        assertEquals("size 2 align 1 a@0:0/4 b@0:4/4 c@1:0/1", describe(Layout.of(t, false,
                List.of(bf("a", t.char_(), 4), bf("b", t.char_(), 4), bf("c", t.char_(), 1)))));
        assertEquals("size 8 align 4 a@0:0/3 b@4:0/3", describe(Layout.of(t, false,
                List.of(bf("a", t.int_(), 3), bf(null, t.int_(), 0), bf("b", t.int_(), 3)))), "zero width pads to the next int");
        assertEquals("size 8 align 8 c@0 l@0:8/5", describe(Layout.of(t, false, List.of(f("c", t.char_()), bf("l", t.llong(), 5)))));
        assertEquals("size 2 align 1 c@1", describe(Layout.of(t, false, List.of(bf(null, t.uint(), 3), f("c", t.char_())))),
                "an unnamed bit-field takes space but not alignment");
        assertEquals("size 1 align 1 b@0:0/1", describe(Layout.of(t, false, List.of(bf("b", t.bool_(), 1)))));
        assertEquals("size 8 align 4 a@0:0/3 i@4", describe(Layout.of(t, false, List.of(bf("a", t.int_(), 3), f("i", t.int_())))));
        assertEquals("size 4 align 4 a@0:0/3 b@0:0/9", describe(Layout.of(t, true, List.of(bf("a", t.int_(), 3), bf("b", t.int_(), 9)))));
        assertEquals("size 12 align 4 a@0:0/32 b@4:0/32 c@8:0/1", describe(Layout.of(t, false,
                List.of(bf("a", t.uint(), 32), bf("b", t.uint(), 32), bf("c", t.uint(), 1)))));
    }

    @Test
    void structsOnX86_64() {
        var t = new Types(X86_64SysV.INSTANCE);
        assertEquals("size 8 align 4 c@0 i@4", describe(Layout.of(t, false, List.of(f("c", t.char_()), f("i", t.int_())))));
        assertEquals("size 16 align 8 c@0 d@8", describe(Layout.of(t, false, List.of(f("c", t.char_()), f("d", t.double_())))));
        assertEquals("size 8 align 4 i@0 c@4", describe(Layout.of(t, false, List.of(f("i", t.int_()), f("c", t.char_())))));
        assertEquals("size 6 align 2 a@0 b@2 c@4", describe(Layout.of(t, false,
                List.of(f("a", t.char_()), f("b", t.short_()), f("c", t.char_())))));
        assertEquals("size 24 align 8 c@0 l@8 d@16", describe(Layout.of(t, false,
                List.of(f("c", t.char_()), f("l", t.long_()), f("d", t.char_())))));
        assertEquals("size 6 align 2 c@0 s@4", describe(Layout.of(t, false,
                List.of(f("c", t.array(t.char_(), 3)), f("s", t.short_())))));
        assertEquals("size 16 align 8 c@0 p@8", describe(Layout.of(t, false, List.of(f("c", t.char_()), f("p", t.pointer(t.int_()))))));
        assertEquals("size 32 align 16 ld@0 c@16", describe(Layout.of(t, false, List.of(f("ld", t.longDouble()), f("c", t.char_())))));
        assertEquals("size 8 align 4 f@0 c@4", describe(Layout.of(t, false, List.of(f("f", t.float_()), f("c", t.char_())))));
        assertEquals("size 1 align 1 c@0", describe(Layout.of(t, false, List.of(f("c", t.char_())))));
        assertEquals("size 4 align 4 n@0 fam@4", describe(Layout.of(t, false,
                List.of(f("n", t.int_()), f("fam", t.incompleteArray(t.char_()))))), "a flexible array member adds no size");
        assertEquals("size 8 align 8 c@0 fam@8", describe(Layout.of(t, false,
                List.of(f("c", t.char_()), f("fam", t.incompleteArray(t.double_()))))), "but it is placed at its alignment");
    }

    @Test
    void unionsAndNesting() {
        var t = new Types(X86_64SysV.INSTANCE);
        assertEquals("size 4 align 4 c@0 i@0", describe(Layout.of(t, true, List.of(f("c", t.char_()), f("i", t.int_())))));
        assertEquals("size 16 align 8 a@0 d@0", describe(Layout.of(t, true, List.of(f("a", t.array(t.char_(), 9)), f("d", t.double_())))));
        var inner = new StubTag("in", false, Layout.of(t, false, List.of(f("c", t.char_()), f("i", t.int_()))));
        CType innerType = t.record(inner);
        assertEquals("size 12 align 4 in@0 d@8", describe(Layout.of(t, false, List.of(f("in", innerType), f("d", t.char_())))));
        // Anonymous member: its members are entered with composed offsets.
        var anon = new StubTag(null, true, Layout.of(t, true, List.of(f("x", t.int_()), f("y", t.float_()))));
        assertEquals("size 8 align 4 tag@0 x@4 y@4", describe(Layout.of(t, false,
                List.of(f("tag", t.char_()), new Layout.Field(Optional.empty(), t.record(anon), OptionalInt.empty())))));
    }

    @Test
    void layoutAsksTheTarget() {
        var t = new Types(Ilp32.INSTANCE);
        assertEquals("size 12 align 4 c@0 d@4", describe(Layout.of(t, false, List.of(f("c", t.char_()), f("d", t.double_())))));
        assertEquals("size 8 align 4 c@0 l@4", describe(Layout.of(t, false, List.of(f("c", t.char_()), f("l", t.long_())))));
        assertEquals("size 16 align 4 ld@0 c@12", describe(Layout.of(t, false, List.of(f("ld", t.longDouble()), f("c", t.char_())))));
        assertEquals("size 8 align 4 c@0 p@4", describe(Layout.of(t, false, List.of(f("c", t.char_()), f("p", t.pointer(t.int_()))))));
    }

    /** A tag with a fixed layout, standing in for sema's tag symbol. */
    static final class StubTag implements Tag {
        private final String name;
        private final boolean isUnion;
        private final Layout layout;

        StubTag(String name, boolean isUnion, Layout layout) {
            this.name = name;
            this.isUnion = isUnion;
            this.layout = layout;
        }

        @Override
        public Optional<String> name() {
            return Optional.ofNullable(name);
        }

        @Override
        public String keyword() {
            return isUnion ? "union" : "struct";
        }

        @Override
        public boolean isUnion() {
            return isUnion;
        }

        @Override
        public Optional<Layout> layout() {
            return Optional.of(layout);
        }
    }
}

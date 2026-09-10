package org.jbm.vm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arena: little-endian at every width, the extensions, floats, and the faults. */
class MemoryTest {

    static final long A = Memory.NULL_PAGE;

    @Test
    void littleEndianAtEveryWidth() {
        Memory m = new Memory();
        m.storeInt(A, 32, 0x04030201);
        assertEquals(0x01, m.loadInt(A, 8, false));
        assertEquals(0x0201, m.loadInt(A, 16, false));
        assertEquals(0x04030201, m.loadInt(A, 32, false));
        assertEquals(0x0403, m.loadInt(A + 2, 16, false));
        m.storeInt(A, 64, 0x0807060504030201L);
        assertEquals(0x0807060504030201L, m.loadInt(A, 64, false));
        assertEquals(0x08, m.loadInt(A + 7, 8, false));
    }

    @Test
    void storeWritesTheLowBitsOnly() {
        Memory m = new Memory();
        m.storeInt(A, 64, -1);
        m.storeInt(A, 8, 0x1234);
        assertEquals(0x34, m.loadInt(A, 8, false));
        assertEquals(0xff, m.loadInt(A + 1, 8, false), "the next byte is untouched");
    }

    @Test
    void extensions() {
        Memory m = new Memory();
        m.storeInt(A, 8, 0xff);
        assertEquals(-1, m.loadInt(A, 8, true));
        assertEquals(255, m.loadInt(A, 8, false));
        m.storeInt(A, 32, 0x80000000L);
        assertEquals(Integer.MIN_VALUE, m.loadInt(A, 32, true));
        assertEquals(0x80000000L, m.loadInt(A, 32, false));
        m.storeInt(A, 64, Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, m.loadInt(A, 64, true));
        assertEquals(Long.MIN_VALUE, m.loadInt(A, 64, false));
    }

    @Test
    void floats() {
        Memory m = new Memory();
        m.storeFloat(A, 64, 2.5);
        assertEquals(2.5, m.loadFloat(A, 64));
        m.storeFloat(A, 32, 0.1);
        assertEquals((double) (float) 0.1, m.loadFloat(A, 32));
        assertEquals(Float.floatToRawIntBits(0.1f), (int) m.loadInt(A, 32, false), "the IEEE bits");
        m.storeFloat(A, 80, 1.0 / 3);
        assertEquals(1.0 / 3, m.loadFloat(A, 80));
    }

    @Test
    void faults() {
        Memory m = new Memory();
        String nul = assertThrows(IllegalStateException.class, () -> m.loadInt(0, 32, true)).getMessage();
        assertEquals("null pointer dereference at 0x0", nul);
        String page = assertThrows(IllegalStateException.class, () -> m.storeInt(100, 8, 1)).getMessage();
        assertEquals("null pointer dereference at 0x64", page);
        String end = assertThrows(IllegalStateException.class, () -> m.loadInt(16L << 20, 8, false)).getMessage();
        assertTrue(end.startsWith("invalid address 0x1000000"), end);
        String straddle = assertThrows(IllegalStateException.class, () -> m.loadInt((16L << 20) - 2, 32, false)).getMessage();
        assertTrue(straddle.startsWith("invalid address"), straddle);
        assertEquals(0, m.loadInt((16L << 20) - 4, 32, false), "the last word is readable");
    }
}

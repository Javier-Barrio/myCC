package org.jbm.mycc.repl.vm;

/**
 * The arena: one flat little-endian address space. The first page is
 * unmapped so a null pointer faults. Widths are in bits.
 */
public class Memory {

    public static final long NULL_PAGE = 4096;

    public long size() {
        return bytes.length;
    }

    private final byte[] bytes = new byte[16 << 20];

    // Static data grows up from the null page; the stack grows down from
    // the top; they fault where they would meet.
    private long dataTop = NULL_PAGE;
    private long sp = bytes.length;

    /** Static storage, never released: globals, strings, code addresses. */
    public long allocate(long size, int align) {
        long start = alignUp(dataTop, align);
        long end = start + Math.max(size, 1);
        if (end > sp) {
            throw new IllegalStateException("out of memory allocating " + size + " bytes");
        }
        dataTop = end;
        return start;
    }

    /** Stack storage for a frame, released by restoring {@link #stackPointer()} to its value before. */
    public long push(long size, int align) {
        long start = alignDown(sp - Math.max(size, 1), align);
        if (start < dataTop) {
            throw new IllegalStateException("stack overflow");
        }
        sp = start;
        return start;
    }

    public long stackPointer() {
        return sp;
    }

    public void stackPointer(long value) {
        sp = value;
    }

    private static long alignUp(long value, int align) {
        long a = Math.max(align, 1);
        return (value + a - 1) / a * a;
    }

    private static long alignDown(long value, int align) {
        long a = Math.max(align, 1);
        return value / a * a;
    }

    // The index of `count` bytes at `address`, or a fault.
    private int index(long address, int count) {
        if (address < NULL_PAGE) {
            throw new IllegalStateException("null pointer dereference at 0x" + Long.toHexString(address));
        }
        if (address + count > bytes.length) {
            throw new IllegalStateException("invalid address 0x" + Long.toHexString(address));
        }
        return (int) address;
    }

    /** {@code width} bits at the address, sign- or zero-extended to 64. */
    public long loadInt(long address, int width, boolean signed) {
        int count = width / 8;
        int i = index(address, count);
        long value = 0;
        for (int k = count - 1; k >= 0; k--) {
            value = (value << 8) | (bytes[i + k] & 0xff);
        }
        if (signed && width < 64) {
            int shift = 64 - width;
            value = (value << shift) >> shift;
        }
        return value;
    }

    /** The low {@code width} bits of the value at the address. */
    public void storeInt(long address, int width, long value) {
        int count = width / 8;
        int i = index(address, count);
        long v = value;
        for (int k = 0; k < count; k++) {
            bytes[i + k] = (byte) v;
            v >>= 8;
        }
    }

    /** A floating value of {@code width} bits; 80 and 128 are held as a double in the first 8 bytes. */
    public double loadFloat(long address, int width) {
        if (width == 32) {
            return Float.intBitsToFloat((int) loadInt(address, 32, false));
        }
        return Double.longBitsToDouble(loadInt(address, 64, false));
    }

    /** {@code count} bytes at the address, copied out. */
    public byte[] read(long address, int count) {
        int i = index(address, count);
        return java.util.Arrays.copyOfRange(bytes, i, i + count);
    }

    /** The NUL-terminated string at the address, one byte per character. */
    public String string(long address) {
        int start = index(address, 1);
        int end = start;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        if (end == bytes.length) {
            throw new IllegalStateException("unterminated string at 0x" + Long.toHexString(address));
        }
        return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Writes the string and its NUL at the address. */
    public void string(long address, String s) {
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        write(address, data);
        storeInt(address + data.length, 8, 0);
    }

    /** The bytes written at the address. */
    public void write(long address, byte[] data) {
        int i = index(address, data.length);
        System.arraycopy(data, 0, bytes, i, data.length);
    }

    /** {@code count} bytes from one address to another; the ranges may overlap. */
    public void copy(long to, long from, long count) {
        int src = index(from, (int) count);
        int dst = index(to, (int) count);
        System.arraycopy(bytes, src, bytes, dst, (int) count);
    }

    /** {@code count} bytes at the address set to a value. */
    public void fill(long address, long count, byte value) {
        int i = index(address, (int) count);
        java.util.Arrays.fill(bytes, i, i + (int) count, value);
    }

    public void storeFloat(long address, int width, double value) {
        if (width == 32) {
            storeInt(address, 32, Float.floatToRawIntBits((float) value));
            return;
        }
        storeInt(address, 64, Double.doubleToRawLongBits(value));
    }
}

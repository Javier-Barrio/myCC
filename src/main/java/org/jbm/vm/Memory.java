package org.jbm.vm;

/**
 * The arena: one flat little-endian address space. The first page is
 * unmapped so a null pointer faults. Widths are in bits.
 */
public class Memory {

    static final long NULL_PAGE = 4096;

    private final byte[] bytes = new byte[16 << 20];

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

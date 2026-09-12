package org.jbm.mycc.repl.vm.builtins;

import org.jbm.mycc.repl.vm.Builtin;
import org.jbm.mycc.repl.vm.Memory;
import org.jbm.mycc.repl.vm.VM;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The C library the VM provides, written against the bundled headers:
 * {@code stdio.h} output, {@code string.h}, the {@code malloc} family
 * (a bump allocator; {@code free} keeps nothing), {@code stdlib.h}
 * numbers and {@code math.h} through {@link Math}. Bound by name on a
 * VM with {@link #bind}; a program's own definition of a name wins.
 */
public final class Libc {

    /** The standard streams' handles, what the {@code stdin}, {@code stdout} and {@code stderr} objects hold. */
    public static final long STDIN = 1;
    public static final long STDOUT = 2;
    public static final long STDERR = 3;

    private Libc() {
    }

    /** Binds every builtin and the standard stream objects on the VM. */
    public static void bind(VM vm) {
        all().forEach(vm::bind);
        objects().forEach(vm::bindObject);
    }

    /** The objects the library provides: {@code stdin}, {@code stdout}, {@code stderr} hold their stream handles. */
    public static Map<String, byte[]> objects() {
        Map<String, byte[]> o = new LinkedHashMap<>();
        o.put("stdin", handle(STDIN));
        o.put("stdout", handle(STDOUT));
        o.put("stderr", handle(STDERR));
        return o;
    }

    private static byte[] handle(long h) {
        byte[] b = new byte[8];
        for (int k = 0; k < 8; k++) {
            b[k] = (byte) (h >> (8 * k));
        }
        return b;
    }

    // Open files by handle, from 4 up; 1 to 3 are the standard streams.
    private static final class Files {
        final Map<Long, java.io.RandomAccessFile> open = new LinkedHashMap<>();
        long next = 4;
    }

    private static Files files(VM vm) {
        return vm.attachment("files", Files::new);
    }

    private static java.io.RandomAccessFile file(VM vm, long handle) {
        java.io.RandomAccessFile f = files(vm).open.get(handle);
        if (f == null) {
            throw new IllegalStateException("not an open file: handle " + handle);
        }
        return f;
    }

    // Bytes to a handle: the standard streams to the VM's output, a file to its position.
    private static long writeTo(VM vm, long handle, byte[] data) {
        if (handle == STDOUT || handle == STDERR) {
            vm.out().print(new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
            vm.out().flush();
            return data.length;
        }
        try {
            file(vm, handle).write(data);
            return data.length;
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    // Bytes from a file handle into `data`; the count read, -1 at the end.
    private static int readFrom(VM vm, long handle, byte[] data) {
        if (handle == STDIN) {
            try {
                return System.in.read(data);
            } catch (java.io.IOException e) {
                return -1;
            }
        }
        try {
            return file(vm, handle).read(data);
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    public static Map<String, Builtin> all() {
        Map<String, Builtin> b = new LinkedHashMap<>();
        // stdio.h
        b.put("printf", new Printf());
        b.put("sprintf", (vm, a) -> {
            String text = Printf.format(vm.memory(), vm.memory().string(integer(a, 1)), a.subList(2, a.size()));
            vm.memory().string(integer(a, 0), text);
            return new VM.IntValue(text.length());
        });
        b.put("snprintf", (vm, a) -> {
            String text = Printf.format(vm.memory(), vm.memory().string(integer(a, 2)), a.subList(3, a.size()));
            long n = integer(a, 1);
            if (n > 0) {
                String fitted = text.length() < n ? text : text.substring(0, (int) n - 1);
                vm.memory().string(integer(a, 0), fitted);
            }
            return new VM.IntValue(text.length());
        });
        b.put("puts", (vm, a) -> {
            vm.out().print(vm.memory().string(integer(a, 0)) + "\n");
            vm.out().flush();
            return new VM.IntValue(1);
        });
        b.put("putchar", (vm, a) -> {
            int c = (int) (integer(a, 0) & 0xff);
            vm.out().print((char) c);
            vm.out().flush();
            return new VM.IntValue(c);
        });
        b.put("fflush", (vm, a) -> {
            vm.out().flush();
            return new VM.IntValue(0);
        });
        b.put("fopen", (vm, a) -> {
            String name = vm.memory().string(integer(a, 0));
            String mode = vm.memory().string(integer(a, 1));
            try {
                java.io.File f = new java.io.File(name);
                if (mode.startsWith("r") && !f.exists()) {
                    return new VM.IntValue(0);
                }
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                if (mode.startsWith("w")) {
                    raf.setLength(0);
                } else if (mode.startsWith("a")) {
                    raf.seek(raf.length());
                }
                Files files = files(vm);
                long handle = files.next++;
                files.open.put(handle, raf);
                return new VM.IntValue(handle);
            } catch (java.io.IOException e) {
                return new VM.IntValue(0);
            }
        });
        b.put("fclose", (vm, a) -> {
            try {
                java.io.RandomAccessFile f = files(vm).open.remove(integer(a, 0));
                if (f != null) {
                    f.close();
                }
                return new VM.IntValue(f == null ? -1 : 0);
            } catch (java.io.IOException e) {
                return new VM.IntValue(-1);
            }
        });
        b.put("fwrite", (vm, a) -> {
            long count = integer(a, 1) * integer(a, 2);
            byte[] data = vm.memory().read(integer(a, 0), (int) count);
            return new VM.IntValue(writeTo(vm, integer(a, 3), data) / Math.max(integer(a, 1), 1));
        });
        b.put("fread", (vm, a) -> {
            long count = integer(a, 1) * integer(a, 2);
            byte[] data = new byte[(int) count];
            int n = readFrom(vm, integer(a, 3), data);
            vm.memory().write(integer(a, 0), java.util.Arrays.copyOf(data, Math.max(n, 0)));
            return new VM.IntValue(Math.max(n, 0) / Math.max(integer(a, 1), 1));
        });
        b.put("fgetc", (vm, a) -> {
            byte[] one = new byte[1];
            int n = readFrom(vm, integer(a, 0), one);
            return new VM.IntValue(n <= 0 ? -1 : one[0] & 0xff);
        });
        b.put("getc", b.get("fgetc"));
        b.put("fgets", (vm, a) -> {
            long buf = integer(a, 0);
            int n = (int) integer(a, 1);
            StringBuilder sb = new StringBuilder();
            byte[] one = new byte[1];
            while (sb.length() < n - 1) {
                if (readFrom(vm, integer(a, 2), one) <= 0) {
                    break;
                }
                sb.append((char) (one[0] & 0xff));
                if (one[0] == '\n') {
                    break;
                }
            }
            if (sb.length() == 0) {
                return new VM.IntValue(0);
            }
            vm.memory().string(buf, sb.toString());
            return new VM.IntValue(buf);
        });
        b.put("feof", (vm, a) -> {
            try {
                java.io.RandomAccessFile f = file(vm, integer(a, 0));
                return new VM.IntValue(f.getFilePointer() >= f.length() ? 1 : 0);
            } catch (java.io.IOException e) {
                return new VM.IntValue(1);
            }
        });
        b.put("fprintf", (vm, a) -> {
            String text = Printf.format(vm.memory(), vm.memory().string(integer(a, 1)), a.subList(2, a.size()));
            writeTo(vm, integer(a, 0), text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            return new VM.IntValue(text.length());
        });
        b.put("fputs", (vm, a) -> {
            String s = vm.memory().string(integer(a, 0));
            writeTo(vm, integer(a, 1), s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            return new VM.IntValue(1);
        });
        b.put("fputc", (vm, a) -> {
            int c = (int) (integer(a, 0) & 0xff);
            writeTo(vm, integer(a, 1), new byte[] {(byte) c});
            return new VM.IntValue(c);
        });
        b.put("putc", b.get("fputc"));
        b.put("remove", (vm, a) -> new VM.IntValue(new java.io.File(vm.memory().string(integer(a, 0))).delete() ? 0 : -1));
        // string.h
        b.put("strlen", (vm, a) -> new VM.IntValue(vm.memory().string(integer(a, 0)).length()));
        b.put("strcmp", (vm, a) -> new VM.IntValue(compare(vm.memory().string(integer(a, 0)), vm.memory().string(integer(a, 1)))));
        b.put("strncmp", (vm, a) -> {
            int n = (int) integer(a, 2);
            String x = vm.memory().string(integer(a, 0));
            String y = vm.memory().string(integer(a, 1));
            return new VM.IntValue(compare(x.substring(0, Math.min(n, x.length())), y.substring(0, Math.min(n, y.length()))));
        });
        b.put("strcpy", (vm, a) -> {
            long dst = integer(a, 0);
            vm.memory().string(dst, vm.memory().string(integer(a, 1)));
            return new VM.IntValue(dst);
        });
        b.put("strncpy", (vm, a) -> {
            long dst = integer(a, 0);
            long n = integer(a, 2);
            String src = vm.memory().string(integer(a, 1));
            for (long i = 0; i < n; i++) {
                vm.memory().storeInt(dst + i, 8, i < src.length() ? src.charAt((int) i) : 0);
            }
            return new VM.IntValue(dst);
        });
        b.put("strcat", (vm, a) -> {
            long dst = integer(a, 0);
            vm.memory().string(dst, vm.memory().string(dst) + vm.memory().string(integer(a, 1)));
            return new VM.IntValue(dst);
        });
        b.put("strrchr", (vm, a) -> {
            long s = integer(a, 0);
            int c = (int) (integer(a, 1) & 0xff);
            String text = vm.memory().string(s);
            int at = c == 0 ? text.length() : text.lastIndexOf((char) c);
            return new VM.IntValue(at < 0 ? 0 : s + at);
        });
        b.put("strchr", (vm, a) -> {
            long s = integer(a, 0);
            int at = vm.memory().string(s).indexOf((char) (integer(a, 1) & 0xff));
            if ((integer(a, 1) & 0xff) == 0) {
                at = vm.memory().string(s).length();
            }
            return new VM.IntValue(at < 0 ? 0 : s + at);
        });
        b.put("strstr", (vm, a) -> {
            long s = integer(a, 0);
            int at = vm.memory().string(s).indexOf(vm.memory().string(integer(a, 1)));
            return new VM.IntValue(at < 0 ? 0 : s + at);
        });
        b.put("strdup", (vm, a) -> {
            String s = vm.memory().string(integer(a, 0));
            long p = vm.memory().allocate(s.length() + 1, 16);
            vm.memory().string(p, s);
            return new VM.IntValue(p);
        });
        b.put("memcpy", (vm, a) -> {
            vm.memory().copy(integer(a, 0), integer(a, 1), integer(a, 2));
            return new VM.IntValue(integer(a, 0));
        });
        b.put("memmove", b.get("memcpy"));
        b.put("memset", (vm, a) -> {
            vm.memory().fill(integer(a, 0), integer(a, 2), (byte) integer(a, 1));
            return new VM.IntValue(integer(a, 0));
        });
        b.put("memcmp", (vm, a) -> {
            byte[] x = vm.memory().read(integer(a, 0), (int) integer(a, 2));
            byte[] y = vm.memory().read(integer(a, 1), (int) integer(a, 2));
            return new VM.IntValue(Integer.signum(Arrays.compareUnsigned(x, y)));
        });
        // stdlib.h
        b.put("malloc", (vm, a) -> new VM.IntValue(vm.memory().allocate(integer(a, 0), 16)));
        b.put("calloc", (vm, a) -> {
            long size = integer(a, 0) * integer(a, 1);
            long p = vm.memory().allocate(size, 16);
            vm.memory().fill(p, size, (byte) 0);
            return new VM.IntValue(p);
        });
        b.put("realloc", (vm, a) -> {
            long old = integer(a, 0);
            long size = integer(a, 1);
            long p = vm.memory().allocate(size, 16);
            if (old != 0) {
                // the old block's size is not tracked: what fits in the new one is copied
                long usable = Math.min(size, vm.memory().size() - old);
                vm.memory().copy(p, old, usable);
            }
            return new VM.IntValue(p);
        });
        b.put("free", (vm, a) -> null);
        b.put("abs", (vm, a) -> new VM.IntValue(Math.abs((int) integer(a, 0))));
        b.put("labs", (vm, a) -> new VM.IntValue(Math.abs(integer(a, 0))));
        b.put("llabs", (vm, a) -> new VM.IntValue(Math.abs(integer(a, 0))));
        b.put("atoi", (vm, a) -> new VM.IntValue(parseLeading(vm.memory().string(integer(a, 0)))));
        b.put("atol", (vm, a) -> new VM.IntValue(parseLeading(vm.memory().string(integer(a, 0)))));
        b.put("atoll", (vm, a) -> new VM.IntValue(parseLeading(vm.memory().string(integer(a, 0)))));
        b.put("exit", (vm, a) -> {
            throw new Exit((int) integer(a, 0));
        });
        b.put("abort", (vm, a) -> {
            throw new IllegalStateException("abort() called");
        });
        // math.h
        b.put("sqrt", (vm, a) -> new VM.FloatValue(Math.sqrt(floating(a, 0))));
        b.put("pow", (vm, a) -> new VM.FloatValue(Math.pow(floating(a, 0), floating(a, 1))));
        b.put("sin", (vm, a) -> new VM.FloatValue(Math.sin(floating(a, 0))));
        b.put("cos", (vm, a) -> new VM.FloatValue(Math.cos(floating(a, 0))));
        b.put("tan", (vm, a) -> new VM.FloatValue(Math.tan(floating(a, 0))));
        b.put("atan2", (vm, a) -> new VM.FloatValue(Math.atan2(floating(a, 0), floating(a, 1))));
        b.put("exp", (vm, a) -> new VM.FloatValue(Math.exp(floating(a, 0))));
        b.put("log", (vm, a) -> new VM.FloatValue(Math.log(floating(a, 0))));
        b.put("log10", (vm, a) -> new VM.FloatValue(Math.log10(floating(a, 0))));
        b.put("fabs", (vm, a) -> new VM.FloatValue(Math.abs(floating(a, 0))));
        b.put("floor", (vm, a) -> new VM.FloatValue(Math.floor(floating(a, 0))));
        b.put("ceil", (vm, a) -> new VM.FloatValue(Math.ceil(floating(a, 0))));
        b.put("round", (vm, a) -> new VM.FloatValue(Math.round(floating(a, 0))));
        b.put("trunc", (vm, a) -> new VM.FloatValue(floating(a, 0) < 0 ? Math.ceil(floating(a, 0)) : Math.floor(floating(a, 0))));
        b.put("fmod", (vm, a) -> new VM.FloatValue(floating(a, 0) % floating(a, 1)));
        b.put("fmin", (vm, a) -> new VM.FloatValue(Math.min(floating(a, 0), floating(a, 1))));
        b.put("fmax", (vm, a) -> new VM.FloatValue(Math.max(floating(a, 0), floating(a, 1))));
        b.put("hypot", (vm, a) -> new VM.FloatValue(Math.hypot(floating(a, 0), floating(a, 1))));
        return b;
    }

    /** Thrown by {@code exit}: unwinds every frame to whoever called into the VM. */
    public static final class Exit extends RuntimeException {
        public final int status;

        public Exit(int status) {
            super("exit(" + status + ")");
            this.status = status;
        }
    }

    private static int compare(String x, String y) {
        int n = Math.min(x.length(), y.length());
        for (int i = 0; i < n; i++) {
            int d = (x.charAt(i) & 0xff) - (y.charAt(i) & 0xff);
            if (d != 0) {
                return Integer.signum(d);
            }
        }
        return Integer.signum(x.length() - y.length());
    }

    // atoi: optional blanks and sign, then digits; nothing else.
    private static long parseLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        long v = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        return negative ? -v : v;
    }

    static long integer(List<VM.Value> args, int i) {
        if (i >= args.size()) {
            throw new IllegalStateException("missing argument " + (i + 1) + " to a library function");
        }
        if (args.get(i) instanceof VM.IntValue v) {
            return v.value();
        }
        throw new IllegalStateException("argument " + (i + 1) + " to a library function is not an integer");
    }

    static double floating(List<VM.Value> args, int i) {
        if (i >= args.size()) {
            throw new IllegalStateException("missing argument " + (i + 1) + " to a library function");
        }
        if (args.get(i) instanceof VM.FloatValue v) {
            return v.value();
        }
        throw new IllegalStateException("argument " + (i + 1) + " to a library function is not floating");
    }
}

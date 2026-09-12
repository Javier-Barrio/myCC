package org.jbm.mycc.cc.backend.lower;

import lombok.NonNull;
import org.jbm.mycc.cc.sema.Symbol;
import org.jbm.mycc.cc.sema.tast.StringData;
import org.jbm.mycc.cc.sema.tast.TUnit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * TAC names for symbols, stable for the same source: a name with linkage
 * is itself; a string literal is {@code .str.<hash of its units>}; a
 * static local or an anonymous static object gets an ordinal; a local of
 * a function gets {@code .2}, {@code .3} when it shadows another of the
 * same name.
 */
final class Names {

    private final Map<Symbol, String> global = new IdentityHashMap<>();

    Names(@NonNull TUnit unit) {
        for (StringData s : unit.strings()) global.put(s.symbol(), ".str." + hash(s.units()));
        var statics = new HashMap<String, Integer>();
        int literals = 0;
        for (var g : unit.globals()) {
            Symbol s = g.symbol();
            if (global.containsKey(s)) continue;
            if (s.linkage() != Symbol.Linkage.NONE) {
                global.put(s, s.name);
            } else if (s.name.startsWith("<literal")) {
                global.put(s, ".lit." + ++literals);
            } else {
                int n = statics.merge(s.name, 1, Integer::sum);
                global.put(s, s.name + ".static" + (n > 1 ? "." + n : ""));
            }
        }
    }

    /** The name of a global object, function or string literal. */
    String of(@NonNull Symbol s) {
        String name = global.get(s);
        if (name != null) return name;
        if (s instanceof Symbol.Function) return s.name;
        throw new IllegalArgumentException("no global name for " + s);
    }

    /** Names for the parameters and locals of one function. */
    static final class Local {
        private final Map<String, Integer> seen = new HashMap<>();

        String of(@NonNull Symbol s) {
            String base = s.name;
            if (base.startsWith("<temp")) {
                base = "tmp" + base.substring(5, base.length() - 1);
            } else if (base.startsWith("<literal")) {
                base = "lit" + base.substring(8, base.length() - 1);
            }
            int n = seen.merge(base, 1, Integer::sum);
            return n == 1 ? base : base + "." + n;
        }
    }

    private static String hash(int[] units) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var sb = new StringBuilder();
            for (int u : units) sb.append(u).append(',');
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

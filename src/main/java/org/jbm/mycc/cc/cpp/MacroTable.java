package org.jbm.mycc.cc.cpp;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jbm.mycc.cc.cpp.CppTokenizer.Token;

/**
 * The macros in force, as a map of the current definitions, with their
 * history: every definition and undefinition is a version, and a name's
 * definition can be asked as of any version. A token is stamped with the
 * version of the moment it was scanned, so a name is expanded with the
 * definitions of that moment however the table changes later in the file,
 * and a macro body is rescanned with the definitions of the moment of use.
 */
public final class MacroTable extends AbstractMap<String, Token> {

    private record Version(int at, @Nullable Token definition) {
    }

    private final Map<String, Token> current = new LinkedHashMap<>();
    private final Map<String, List<Version>> history = new HashMap<>();
    private int version;

    /** The version now: the count of definitions and undefinitions so far. */
    public int version() {
        return version;
    }

    /** The definition of the name as of a version, or null when it was not defined then. */
    public @Nullable Token at(@NonNull String name, int at) {
        List<Version> versions = history.get(name);
        if (versions == null) {
            return null;
        }
        Token definition = null;
        for (Version v : versions) {
            if (v.at > at) {
                break;
            }
            definition = v.definition;
        }
        return definition;
    }

    @Override
    public Token put(String name, Token definition) {
        record(name, definition);
        return current.put(name, definition);
    }

    @Override
    public Token remove(Object name) {
        if (!current.containsKey(name)) {
            return null;
        }
        record((String) name, null);
        return current.remove(name);
    }

    private void record(String name, @Nullable Token definition) {
        version++;
        history.computeIfAbsent(name, k -> new ArrayList<>()).add(new Version(version, definition));
    }

    @Override
    public Token get(Object name) {
        return current.get(name);
    }

    @Override
    public boolean containsKey(Object name) {
        return current.containsKey(name);
    }

    @Override
    public Set<Entry<String, Token>> entrySet() {
        return Collections.unmodifiableMap(current).entrySet();
    }
}

package org.jbm.mycc.cc.cpp;

import java.util.LinkedHashSet;

public class CallMacro {
    private final LinkedHashSet<Macro> args = new LinkedHashSet<>();

    public LinkedHashSet<Macro> args() {
        return new LinkedHashSet<>(args);
    }
}

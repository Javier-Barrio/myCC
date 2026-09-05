package org.jbm.cc.cpp;

import org.jbm.cc.CppTokenizer;

import java.util.LinkedHashSet;

public class CppToken {
    public final CppTokenizer.Token token;
    public final LinkedHashSet<CppTokenizer.Token> hideSet = new LinkedHashSet<>();

    // Set once the occurrence is resolved against the macro table; non-null
    // iff token.type is OBJECT_MACRO or CALL_MACRO.
    public Macro macro;

    public CppToken(CppTokenizer.Token token) {
        this.token = token;
    }

    public CppToken clone() {
        CppToken clone = new CppToken(this.token);
        clone.hideSet.addAll(this.hideSet);
        clone.macro = this.macro;
        return clone;
    }
}

package org.jbm.cc.cpp;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;

public class CppToken {
    public final CppTokenizer.Token token;
    public final LinkedHashSet<CppTokenizer.Token> hideSet = new LinkedHashSet<>();

    // Set once the occurrence is resolved against the macro table; non-null
    // iff token.type is OBJECT_MACRO or CALL_MACRO.
    public @Nullable Macro macro;

    // Whether white space separates this occurrence from the previous
    // token. Starts as what the lexer saw; macro expansion gives the first
    // token of a replacement the spacing of the macro name or parameter it
    // replaced (6.10.5.3p2 relies on this for the stringize operator).
    public boolean spaceBefore;

    public CppToken(@NonNull CppTokenizer.Token token) {
        this.token = token;
        this.spaceBefore = token.spaceBefore;
    }

    public CppToken clone() {
        CppToken clone = new CppToken(this.token);
        clone.hideSet.addAll(this.hideSet);
        clone.macro = this.macro;
        clone.spaceBefore = this.spaceBefore;
        return clone;
    }
}

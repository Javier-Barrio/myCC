package org.jbm.mycc.cc.cpp;

import java.util.LinkedHashSet;

import static org.jbm.mycc.cc.cpp.CppTokenizer.Token;

public abstract class Macro {
    Token token;

    private final LinkedHashSet<Token> hideSet = new LinkedHashSet<>();

    private CppTokenizer.TokenSet expansion;

    public LinkedHashSet<Token> hideSet() {
        return new LinkedHashSet<>(hideSet);
    }

    public void addToHideSet(Token token) {
        hideSet.add(token);
    }

    public CppTokenizer.TokenSet expansion() {
        return expansion;
    }

    public void setExpansion(CppTokenizer.TokenSet expansion) {
        this.expansion = expansion;
    }
}

package org.jbm.cc.sema;

import org.jbm.cc.cpp.CppTokenizer.Token;

public final class SemaException extends RuntimeException {
    public final Token token;

    public SemaException(String message, Token token) {
        super(message + " at " + token.line + ":" + token.column);
        this.token = token;
    }
}

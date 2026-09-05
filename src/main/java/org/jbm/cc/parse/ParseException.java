package org.jbm.cc.parse;

import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;

public final class ParseException extends RuntimeException {
    public final Token token;

    public ParseException(String message, Token token) {
        super(message + " at " + token.line + ":" + token.column
                + (token.type == TokenType.EOF ? " (end of input)" : " near '" + token.text + "'"));
        this.token = token;
    }
}

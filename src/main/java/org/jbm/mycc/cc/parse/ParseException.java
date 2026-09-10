package org.jbm.mycc.cc.parse;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;

public final class ParseException extends RuntimeException {
    public final Token token;

    public ParseException(@NonNull String message, @NonNull Token token) {
        super(message + " at " + token.location()
                + (token.type == TokenType.EOF ? " (end of input)" : " near '" + token.text + "'"));
        this.token = token;
    }
}

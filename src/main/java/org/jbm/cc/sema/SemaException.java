package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;

public final class SemaException extends RuntimeException {
    public final Token token;

    public SemaException(@NonNull String message, @NonNull Token token) {
        super(message + " at " + token.location());
        this.token = token;
    }
}

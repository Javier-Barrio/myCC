package org.jbm.cc.parse;

import lombok.NonNull;
import org.jbm.cc.cpp.CppToken;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;

import java.util.List;

/**
 * Read-only cursor over the phase-7 token list with the k-token lookahead
 * the parser needs (k <= 2 in practice). Keyword and punctuator spellings
 * are disjoint, so {@link #at(String)} matches either by text alone.
 */
public final class TokenCursor {
    private final List<CppToken> tokens;
    private final Token eof;
    private int pos;

    public TokenCursor(@NonNull List<CppToken> tokens) {
        this.tokens = tokens;
        this.eof = !tokens.isEmpty() && tokens.get(tokens.size() - 1).token.type == TokenType.EOF
                ? tokens.get(tokens.size() - 1).token
                : new Token(TokenType.EOF, "", 0, 0);
    }

    public Token peek() {
        return peek(0);
    }

    public Token peek(int k) {
        int i = pos + k;
        return i < tokens.size() ? tokens.get(i).token : eof;
    }

    public Token next() {
        Token t = peek();
        if (t.type != TokenType.EOF) pos++;
        return t;
    }

    public boolean atEof() {
        return peek().type == TokenType.EOF;
    }

    public boolean at(@NonNull String text) {
        return at(0, text);
    }

    public boolean at(int k, @NonNull String text) {
        Token t = peek(k);
        return (t.type == TokenType.PUNCTUATOR || t.type == TokenType.KEYWORD) && t.text.equals(text);
    }

    public boolean atAny(String... texts) {
        for (String s : texts) {
            if (at(s)) return true;
        }
        return false;
    }

    public boolean atIdentifier() {
        return peek().type == TokenType.IDENTIFIER;
    }

    public boolean atIdentifier(int k) {
        return peek(k).type == TokenType.IDENTIFIER;
    }

    public boolean accept(@NonNull String text) {
        if (!at(text)) return false;
        next();
        return true;
    }

    public Token expect(@NonNull String text) {
        if (!at(text)) throw error("expected '" + text + "'");
        return next();
    }

    public Token expectIdentifier() {
        if (!atIdentifier()) throw error("expected identifier");
        return next();
    }

    public ParseException error(@NonNull String message) {
        return new ParseException(message, peek());
    }
}

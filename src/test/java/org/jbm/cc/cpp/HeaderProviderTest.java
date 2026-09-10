package org.jbm.cc.cpp;

import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The header providers, the file a token carries, and the directive strip keyed by file and line. */
class HeaderProviderTest {

    @Test
    void mapProviderFindsByNameInEitherForm() {
        HeaderProvider p = HeaderProvider.of(Map.of("a.h", "int a;"));
        assertEquals(Optional.of(new Header("a.h", "int a;")), p.find("a.h", true, "main.c"));
        assertEquals(Optional.of(new Header("a.h", "int a;")), p.find("a.h", false, "main.c"));
        assertTrue(p.find("b.h", true, "main.c").isEmpty());
    }

    @Test
    void chainTakesTheFirstHit() {
        HeaderProvider first = HeaderProvider.of(Map.of("a.h", "first"));
        HeaderProvider second = HeaderProvider.of(Map.of("a.h", "second", "b.h", "b"));
        HeaderProvider chain = HeaderProvider.chain(List.of(first, second));
        assertEquals("first", chain.find("a.h", false, "").orElseThrow().text());
        assertEquals("b", chain.find("b.h", false, "").orElseThrow().text());
        assertTrue(chain.find("c.h", false, "").isEmpty());
    }

    @Test
    void tokensCarryTheirFile() {
        TokenSet set = CppTokenizer.tokenSet("#define X 1\nX + 2", "main.c");
        for (CppToken t : set.tokens) {
            assertEquals("main.c", t.token.file, t.token.toString());
        }
        assertEquals("<source>", CppTokenizer.tokenSet("x").tokens.get(0).token.file);
    }

    @Test
    void directiveLinesAreStrippedPerFile() {
        Token hash = token(TokenType.PUNCTUATOR, "#", "a.h", 1);
        Token define = token(TokenType.IDENTIFIER, "define", "a.h", 1);
        Token name = token(TokenType.OBJECT_MACRO, "X", "a.h", 1);
        Token other = token(TokenType.IDENTIFIER, "y", "main.c", 1);
        Token eof = token(TokenType.EOF, "", "main.c", 1);
        TokenSet set = TokenSet.fromTokens(List.of(hash, define, name, other, eof));
        List<String> texts = new Scanner().expand(set).tokens.stream().map(t -> t.token.text).toList();
        assertEquals(List.of("y", ""), texts, "line 1 of main.c is not line 1 of a.h");
    }

    private static Token token(TokenType type, String text, String file, int line) {
        Token t = new Token(type, text, line, 1);
        t.file = file;
        return t;
    }
}

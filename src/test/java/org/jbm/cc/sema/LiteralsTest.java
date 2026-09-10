package org.jbm.cc.sema;

import org.jbm.cc.lower.arch.Ilp32;
import org.jbm.mycc.cc.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.sema.SemaException;
import org.jbm.mycc.repl.cc.sema.Literals;
import org.jbm.mycc.cc.sema.tast.TExpr;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests of constant decoding (6.4.5) on tokens, without the pipeline. */
class LiteralsTest {

    private final Types x64 = new Types(X86_64SysV.INSTANCE);
    private final Literals literals = new Literals(x64);

    private static Token token(TokenType type, String text) {
        return new Token(type, text, 1, 1);
    }

    private String integer(String text) {
        TExpr.IntConst c = literals.integer(token(TokenType.INTEGER_CONSTANT, text));
        return c.value() + ":" + c.type().spelling();
    }

    private String floating(String text) {
        TExpr.FloatConst c = literals.floating(token(TokenType.FLOATING_CONSTANT, text));
        return c.value() + ":" + c.type().spelling();
    }

    private String character(String text) {
        TExpr.IntConst c = literals.character(token(TokenType.CHARACTER_LITERAL, text));
        return c.value() + ":" + c.type().spelling();
    }

    private String string(String... parts) {
        var value = literals.string(Arrays.stream(parts).map(p -> token(TokenType.STRING_LITERAL, p)).toList());
        return value.elementType().spelling() + Arrays.toString(value.units());
    }

    @Test
    void integerBasesAndSeparators() {
        assertEquals("0:int", integer("0"));
        assertEquals("255:int", integer("0xff"));
        assertEquals("255:int", integer("0XFF"));
        assertEquals("8:int", integer("010"));
        assertEquals("8:int", integer("0o10"));
        assertEquals("5:int", integer("0b101"));
        assertEquals("1234567:int", integer("1'234'567"));
        assertEquals("255:int", integer("0x'f'f".replace("0x'", "0x")));
    }

    @Test
    void integerTypesBySizeAndSuffix() {
        assertEquals("2147483647:int", integer("2147483647"));
        assertEquals("2147483648:long", integer("2147483648"));
        assertEquals("2147483648:unsigned int", integer("0x80000000"));
        assertEquals("4294967296:long", integer("0x100000000"));
        assertEquals("-9223372036854775808:unsigned long", integer("9223372036854775808u"));
        assertEquals("-1:unsigned long", integer("0xFFFFFFFFFFFFFFFF"));
        assertEquals("1:unsigned int", integer("1U"));
        assertEquals("1:long", integer("1L"));
        assertEquals("1:unsigned long", integer("1Ul"));
        assertEquals("1:long long", integer("1LL"));
        assertEquals("1:unsigned long long", integer("1LLU"));
        assertEquals("1:unsigned long long", integer("1ull"));
        assertEquals("5:_BitInt(4)", integer("5wb"));
        assertEquals("5:unsigned _BitInt(3)", integer("5uwb"));
        assertEquals("0:unsigned _BitInt(1)", integer("0uwb"));
        assertEquals("0:_BitInt(2)", integer("0wb"));
    }

    @Test
    void integerErrors() {
        assertThrows(SemaException.class, () -> integer("18446744073709551616"));
        assertThrows(SemaException.class, () -> integer("18446744073709551615"), "too large for a signed type");
        assertThrows(SemaException.class, () -> integer("0x10000000000000000"));
    }

    @Test
    void floatingSuffixesAndForms() {
        assertEquals("1.5:double", floating("1.5"));
        assertEquals("1.5:float", floating("1.5f"));
        assertEquals("1.5:long double", floating("1.5L"));
        assertEquals("100.0:double", floating("1e2"));
        assertEquals("0.001:double", floating("1E-3"));
        assertEquals("0.5:double", floating(".5"));
        assertEquals("2.0:double", floating("2."));
        assertEquals("1.0:double", floating("0x1p0"));
        assertEquals("1.5:double", floating("0x1.8p0"));
        assertEquals("255.0:float", floating("0xFFp0f"));
        assertEquals("0.10000000149011612:float", floating("0.1f"), "rounded to single precision");
        assertEquals("1000.0:double", floating("1'000.0"));
        assertThrows(SemaException.class, () -> floating("1.0i"));
        assertThrows(SemaException.class, () -> floating("1.0dd"));
    }

    @Test
    void characterConstantsAndEscapes() {
        assertEquals("97:int", character("'a'"));
        assertEquals("10:int", character("'\\n'"));
        assertEquals("9:int", character("'\\t'"));
        assertEquals("0:int", character("'\\0'"));
        assertEquals("39:int", character("'\\''"));
        assertEquals("92:int", character("'\\\\'"));
        assertEquals("63:int", character("'\\?'"));
        assertEquals("7:int", character("'\\a'"));
        assertEquals("-1:int", character("'\\xff'"));
        assertEquals("-1:int", character("'\\377'"));
        assertEquals("83:int", character("'\\123'"));
        assertEquals("120:int", character("L'x'"));
        assertEquals("233:int", character("L'\\u00e9'"));
        assertEquals("120:unsigned short", character("u'x'"));
        assertEquals("120:unsigned int", character("U'x'"));
        assertEquals("128512:unsigned int", character("U'\\U0001F600'"));
        assertEquals("120:unsigned char", character("u8'x'"));
        assertEquals("233:int", character("'\u00e9'".replace("\u00e9", "\\u00e9").replace("'\\u00e9'", "L'\\u00e9'")));
        assertThrows(SemaException.class, () -> character("''"));
        assertThrows(SemaException.class, () -> character("'ab'"));
        assertThrows(SemaException.class, () -> character("'\\q'"));
        assertThrows(SemaException.class, () -> character("'\\x'"));
        assertThrows(SemaException.class, () -> character("'\\x100'"));
        assertThrows(SemaException.class, () -> character("u8'\\u00e9'"), "not a single UTF-8 byte");
        assertThrows(SemaException.class, () -> character("u'\\U0001F600'"), "not a single UTF-16 unit");
    }

    @Test
    void plainCharSignednessComesFromTheTarget() {
        var ilp32 = new Literals(new Types(Ilp32.INSTANCE));
        TExpr.IntConst c = ilp32.character(token(TokenType.CHARACTER_LITERAL, "'\\xff'"));
        assertEquals(255, c.value());
        assertEquals("120:int", character("'x'"));
    }

    @Test
    void stringsConcatenateAndEncode() {
        assertEquals("char[104, 105, 0]", string("\"hi\""));
        assertEquals("char[97, 98, 99, 0]", string("\"a\"", "\"bc\""));
        assertEquals("char[0]", string("\"\""));
        assertEquals("char[10, 92, 0]", string("\"\\n\\\\\""));
        assertEquals("char[255, 0]", string("\"\\xff\""), "an escape is one byte, unencoded");
        assertEquals("char[195, 169, 0]", string("\"\\u00e9\""), "a universal character name is UTF-8 encoded");
        assertEquals("unsigned char[195, 169, 0]", string("u8\"\\u00e9\""));
        assertEquals("unsigned short[233, 0]", string("u\"\\u00e9\""));
        assertEquals("unsigned short[55357, 56832, 0]", string("u\"\\U0001F600\""), "a surrogate pair");
        assertEquals("unsigned int[128512, 0]", string("U\"\\U0001F600\""));
        assertEquals("int[119, 0]", string("L\"w\""), "wchar_t is int on x86-64");
        assertEquals("char[97, 92, 110, 0]", string("R\"x(a\\n)x\""), "raw: the backslash and n stay");
        assertEquals("int[97, 98, 0]", string("\"a\"", "L\"b\""), "an unprefixed part takes the prefixed part's type");
        assertThrows(SemaException.class, () -> string("u\"a\"", "U\"b\""));
    }

    @Test
    void textOfAStringValue() {
        assertEquals("hi", Literals.text(literals.string(List.of(token(TokenType.STRING_LITERAL, "\"hi\"")))));
        assertEquals("\u00e9", Literals.text(literals.string(List.of(token(TokenType.STRING_LITERAL, "\"\\u00e9\"")))));
        assertEquals("wide", Literals.text(literals.string(List.of(token(TokenType.STRING_LITERAL, "L\"wide\"")))));
        assertEquals("x", Literals.text(literals.string(List.of(token(TokenType.STRING_LITERAL, "u\"x\"")))));
    }
}

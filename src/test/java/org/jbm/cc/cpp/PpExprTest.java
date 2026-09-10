package org.jbm.cc.cpp;

import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.LexException;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.cpp.PpExpr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The evaluator on token lists: each operator, the unsigned rules, every constant form, each error. */
class PpExprTest {

    private static PpExpr.Value eval(String source) {
        List<Token> tokens = CppTokenizer.tokenize(source).stream().filter(t -> t.type != TokenType.EOF).toList();
        return PpExpr.evaluate(tokens, new Token(TokenType.PUNCTUATOR, "#", 1, 1));
    }

    private static long value(String source) {
        return eval(source).bits();
    }

    private static String fails(String source) {
        return assertThrows(LexException.class, () -> eval(source)).getMessage();
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(7, value("1 + 2 * 3"));
        assertEquals(9, value("(1 + 2) * 3"));
        assertEquals(3, value("7 / 2"));
        assertEquals(1, value("7 % 2"));
        assertEquals(-3, value("-7 / 2"));
        assertEquals(-1, value("-7 % 2"));
        assertEquals(8, value("1 << 3"));
        assertEquals(-4, value("-8 >> 1"));
        assertEquals(6, value("2 * 3 - 0"));
        assertEquals(1, value("+1"));
        assertEquals(-1, value("~0"));
    }

    @Test
    void comparisonsAndLogic() {
        assertEquals(1, value("1 < 2"));
        assertEquals(0, value("2 < 1"));
        assertEquals(1, value("2 >= 2 && 3 > 2"));
        assertEquals(1, value("0 || 2"));
        assertEquals(0, value("1 && 0"));
        assertEquals(1, value("!0"));
        assertEquals(0, value("!5"));
        assertEquals(1, value("3 == 3 && 3 != 4"));
        assertEquals(2, value("1 ? 2 : 3"));
        assertEquals(3, value("0 ? 2 : 3"));
        assertEquals(4, value("0 ? 2 : 1 ? 4 : 5"), "the conditional operator nests to the right");
        assertEquals(7, value("6 | 1"));
        assertEquals(2, value("6 & 3"));
        assertEquals(5, value("6 ^ 3"));
    }

    @Test
    void unsignedRules() {
        assertEquals(0, value("-1 < 0u"), "an unsigned operand makes the comparison unsigned");
        assertEquals(1, value("-1 < 0"));
        assertEquals(1, value("0xffffffffffffffff == -1"), "a constant too large for a signed value is unsigned");
        assertTrue(eval("0xffffffffffffffff").unsigned());
        assertFalse(eval("0x7fffffffffffffff").unsigned());
        assertEquals(1, value("10u / 3 == 3"));
        assertEquals(Long.divideUnsigned(-1L, 2), value("-1 / 2u"));
        assertTrue(eval("1 ? 2u : 3").unsigned());
        assertEquals(1, value("(-1 >> 1) == -1"));
        assertEquals(1, value("(-1u >> 1) == 0x7fffffffffffffff"));
    }

    @Test
    void constants() {
        assertEquals(255, value("0xff"));
        assertEquals(255, value("0XFF"));
        assertEquals(5, value("0b101"));
        assertEquals(15, value("017"));
        assertEquals(0, value("0"));
        assertEquals(1000, value("1'000"));
        assertEquals(10, value("10u"));
        assertEquals(10, value("10ULL"));
        assertEquals(10, value("10ll"));
        assertEquals(97, value("'a'"));
        assertEquals(10, value("'\\n'"));
        assertEquals(65, value("'\\x41'"));
        assertEquals(8, value("'\\10'"));
        assertEquals(-1, value("'\\xff'"), "plain char is signed on this target");
        assertEquals(0x6162, value("'ab'"));
        assertEquals(1, value("true"));
        assertEquals(0, value("false"));
        assertEquals(1, value("FOO + 1 == 1"), "an identifier that survived expansion is 0");
    }

    @Test
    void errors() {
        assertTrue(fails("1.5").contains("not an integer constant"));
        assertTrue(fails("\"s\"").contains("string literal"));
        assertTrue(fails("1 / 0").contains("division by zero"));
        assertTrue(fails("1 % 0").contains("division by zero"));
        assertTrue(fails("1 +").contains("ends early"));
        assertTrue(fails("(1").contains("expected ')'"));
        assertTrue(fails("1 2").contains("unexpected '2'"));
        assertTrue(fails("1 << 64").contains("shift count"));
        assertTrue(fails("").contains("no expression"));
        assertTrue(fails("1 ? 2").contains("expected ':'"));
        assertTrue(fails("0xfffffffffffffffff").contains("too large"));
    }
}

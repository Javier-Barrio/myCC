package org.jbm.mycc.cc.cpp;

import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.cpp.Scanner;
import org.jbm.mycc.cc.cpp.TokenConversion;
import org.jbm.mycc.cc.backend.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.cpp.CppTokenizer.LexException;
import org.jbm.mycc.cc.parse.ParseException;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.sema.Desugar;
import org.jbm.mycc.cc.sema.Resolver;
import org.jbm.mycc.cc.sema.SemaException;
import org.jbm.mycc.cc.sema.Typer;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every stage names the file an error is in, which matters once headers are included. */
class DiagnosticsTest {

    private static final HeaderProvider HEADERS = HeaderProvider.of(Map.of(
            "else.h", "#else\n",
            "syntax.h", "int f(;\n",
            "sema.h", "int x = y;\n",
            "number.h", "int x = 0xE+2;\n"));

    private static void compile(String source) {
        CppTokenizer.TokenSet tokens = CppTokenizer.tokenSet(source, HEADERS, "main.c");
        List<Decl> unit = Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(tokens))));
        Typer.type(unit, Resolver.resolve(unit), new Types(X86_64SysV.INSTANCE));
    }

    @Test
    void tokenLocationIncludesTheFile() {
        CppTokenizer.Token t = CppTokenizer.tokenSet("\n  x", null, "main.c").tokens.get(0).token;
        assertEquals("main.c:2:3", t.location());
        assertEquals("2:3", new CppTokenizer.Token(CppTokenizer.TokenType.IDENTIFIER, "x", 2, 3).location());
    }

    @Test
    void lexErrorsNameTheFile() {
        String main = assertThrows(LexException.class, () -> compile("int a;\n#endif")).getMessage();
        assertTrue(main.endsWith("at main.c:2:2"), main);
        String header = assertThrows(LexException.class, () -> compile("#include \"else.h\"")).getMessage();
        assertTrue(header.endsWith("at else.h:1:2"), header);
        String condition = assertThrows(LexException.class, () -> compile("\n#if 1 /\n#endif")).getMessage();
        assertTrue(condition.contains("at main.c:2:"), condition);
    }

    @Test
    void laterStagesNameTheFile() {
        String parse = assertThrows(ParseException.class, () -> compile("#include \"syntax.h\"")).getMessage();
        assertTrue(parse.contains("at syntax.h:1:"), parse);
        String sema = assertThrows(SemaException.class, () -> compile("#include \"sema.h\"")).getMessage();
        assertTrue(sema.contains("at sema.h:1:9"), sema);
        String conversion = assertThrows(TokenConversion.ConversionException.class,
                () -> compile("#include \"number.h\"")).getMessage();
        assertTrue(conversion.contains("at number.h:1:9"), conversion);
    }
}

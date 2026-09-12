package org.jbm.mycc.cc;

import org.jbm.mycc.cc.sema.types.Std;
import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.cpp.Scanner;
import org.jbm.mycc.cc.cpp.TokenConversion;
import org.jbm.mycc.cc.backend.lower.Lower;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.sema.Desugar;
import org.jbm.mycc.cc.sema.Resolver;
import org.jbm.mycc.cc.sema.Typer;
import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.sema.tast.TUnit;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Target;
import org.jbm.mycc.cc.sema.types.Types;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The compiler's one entry: source text to the TAC, through every pass
 * in order. {@link #compileScript} is the shell's variant, where
 * statements may appear at file scope and become {@code .file}.
 */
public final class Compiler {

    /** Everything one compilation produced, for whoever needs an earlier stage: the AST, the typed tree, the TAC. */
    public record Compiled(@NonNull TokenSet tokens, @NonNull List<Decl> ast, @NonNull TUnit typed, @NonNull Module tac) {
    }

    /** The front half: preprocessed tokens and the syntax tree, before any semantic check. */
    public record Parsed(@NonNull TokenSet tokens, @NonNull List<Decl> ast) {
    }

    private Compiler() {
    }

    /** Preprocesses and parses a script, without resolving or typing it. */
    public static Parsed parseScript(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                     @NonNull Std std) {
        TokenSet tokens = TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source, headers, file)));
        return new Parsed(tokens, Parser.parseScript(tokens, std));
    }

    public static Compiled compile(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                   @NonNull Types types) {
        return run(source, headers, file, types, false);
    }

    /** The preprocessed tokens alone, for {@code -E}. */
    public static TokenSet preprocess(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                      @NonNull Types types) {
        return TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source, headers, file, predefined(types))));
    }

    public static Compiled compileScript(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                         @NonNull Types types) {
        return run(source, headers, file, types, true);
    }

    /** The macros the target and the compiler predefine: the data model, the architecture, the standard. */
    public static String predefined(@NonNull Types types) {
        Target t = types.target();
        StringBuilder sb = new StringBuilder();
        sb.append("#define __STDC__ 1\n");
        sb.append("#define __STDC_VERSION__ ").append(types.std().version).append('\n');
        sb.append("#define __STDC_HOSTED__ 1\n");
        sb.append("#define __CHAR_BIT__ 8\n");
        sb.append("#define __SIZEOF_SHORT__ ").append(t.width(CType.Int.Rank.SHORT) / 8).append('\n');
        sb.append("#define __SIZEOF_INT__ ").append(t.width(CType.Int.Rank.INT) / 8).append('\n');
        sb.append("#define __SIZEOF_LONG__ ").append(t.width(CType.Int.Rank.LONG) / 8).append('\n');
        sb.append("#define __SIZEOF_LONG_LONG__ ").append(t.width(CType.Int.Rank.LLONG) / 8).append('\n');
        sb.append("#define __SIZEOF_POINTER__ ").append(t.pointerWidth() / 8).append('\n');
        if (t.width(CType.Int.Rank.LONG) == 64 && t.pointerWidth() == 64) {
            sb.append("#define __LP64__ 1\n#define _LP64 1\n");
        } else if (t.pointerWidth() == 32) {
            sb.append("#define __ILP32__ 1\n#define _ILP32 1\n");
        }
        if (t.pointerWidth() == 64) {
            sb.append("#define __x86_64__ 1\n#define __x86_64 1\n#define __amd64__ 1\n");
        } else {
            sb.append("#define __i386__ 1\n");
        }
        sb.append("#define __linux__ 1\n#define __linux 1\n#define __unix__ 1\n#define __unix 1\n#define __gnu_linux__ 1\n");
        // The spellings C23 keeps for older code, as macros for the keywords.
        sb.append("#define _Bool bool\n#define _Alignas alignas\n#define _Alignof alignof\n");
        sb.append("#define _Static_assert static_assert\n#define _Thread_local thread_local\n");
        sb.append("#define __ORDER_LITTLE_ENDIAN__ 1234\n#define __ORDER_BIG_ENDIAN__ 4321\n#define __BYTE_ORDER__ __ORDER_LITTLE_ENDIAN__\n");
        return sb.toString();
    }

    private static Compiled run(String source, @Nullable HeaderProvider headers, String file, Types types, boolean script) {
        TokenSet tokens = TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source, headers, file, predefined(types))));
        List<Decl> ast = script ? Parser.parseScript(tokens, types.std()) : Parser.parse(tokens, types.std());
        List<Decl> desugared = Desugar.desugar(ast);
        TUnit typed = Typer.type(desugared, Resolver.resolve(desugared), types);
        Module tac = Lower.lower(typed, types);
        return new Compiled(tokens, ast, typed, tac);
    }
}

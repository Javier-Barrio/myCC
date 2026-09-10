package org.jbm.cc;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.HeaderProvider;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.lower.Lower;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.parse.ast.Decl;
import org.jbm.cc.sema.Desugar;
import org.jbm.cc.sema.Resolver;
import org.jbm.cc.sema.Typer;
import org.jbm.cc.lower.tac.Module;
import org.jbm.cc.sema.tast.TUnit;
import org.jbm.cc.sema.types.Types;
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
    public static Parsed parseScript(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file) {
        TokenSet tokens = TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source, headers, file)));
        return new Parsed(tokens, Parser.parseScript(tokens));
    }

    public static Compiled compile(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                   @NonNull Types types) {
        return run(source, headers, file, types, false);
    }

    public static Compiled compileScript(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                         @NonNull Types types) {
        return run(source, headers, file, types, true);
    }

    private static Compiled run(String source, @Nullable HeaderProvider headers, String file, Types types, boolean script) {
        TokenSet tokens = TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source, headers, file)));
        List<Decl> ast = script ? Parser.parseScript(tokens) : Parser.parse(tokens);
        List<Decl> desugared = Desugar.desugar(ast);
        TUnit typed = Typer.type(desugared, Resolver.resolve(desugared), types);
        Module tac = Lower.lower(typed, types);
        return new Compiled(tokens, ast, typed, tac);
    }
}

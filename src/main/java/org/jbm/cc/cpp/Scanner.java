package org.jbm.cc.cpp;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Macro expander implementing Dave Prosser's expand/subst/glue/hsadd
 * algorithm with hide sets, as reproduced in
 * https://www.spinellis.gr/blog/20060626/cpp.algo.pdf.
 */
public class Scanner {

    // Macro table captured from the incoming TokenSet. Recursive expansion
    // builds fresh intermediate TokenSets, so the table lives here rather
    // than being threaded through each of them.
    private Map<String, Token> macros = Map.of();

    public TokenSet expand(@NonNull TokenSet tokenSet) {
        macros = tokenSet.macros;
        return doExpand(stripDirectiveLines(tokenSet));
    }

    // expand(TS) from the paper, over a stream with directive lines
    // already removed.
    private TokenSet doExpand(TokenSet tokenSet) {
        if (tokenSet.tokens.isEmpty()) {
            return TokenSet.empty();
        }

        var first = tokenSet.tokens.get(0);
        var rest = new TokenSet(tokenSet.tokens.subList(1, tokenSet.tokens.size()));

        // T is in its own hide set: painted blue, never expanded again.
        if (hideSetContains(first, first.token.text)) {
            return new TokenSet(first, doExpand(rest));
        }

        var definition = definitionOf(first);

        // T is an object-like macro:
        // expand(subst(ts(T), {}, {}, HS ∪ {T}, {}) • TS')
        if (definition != null && definition.type == TokenType.OBJECT_MACRO) {
            var hs = hideSetPlus(first, Optional.empty());
            var replaced = substitute(TokenSet.fromTokens(definition.expansion),
                    new ArrayList<>(), new ArrayList<>(), hs, TokenSet.empty());
            return doExpand(withLeadingSpace(replaced, first.spaceBefore).concat(rest));
        }

        // T is a function-like macro followed by '(' actuals ')':
        // expand(subst(ts(T), fp(T), actuals, (HS ∩ HS') ∪ {T}, {}) • TS'')
        if (definition != null && definition.type == TokenType.CALL_MACRO && startsWithOpenParen(rest)) {
            int close = matchingCloseParen(rest);
            if (close >= 0) {
                var closeParen = rest.tokens.get(close);
                var args = splitArguments(rest.tokens.subList(1, close), definition.params.size());
                var remainder = new TokenSet(rest.tokens.subList(close + 1, rest.tokens.size()));

                var fp = new ArrayList<TokenSet>();
                definition.params.forEach(p -> fp.add(TokenSet.from(new CppToken(p))));

                var hs = hideSetPlus(first, Optional.of(closeParen));
                var replaced = substitute(TokenSet.fromTokens(definition.expansion),
                        fp, args, hs, TokenSet.empty());
                return doExpand(withLeadingSpace(replaced, first.spaceBefore).concat(remainder));
            }
        }

        return new TokenSet(first, doExpand(rest));
    }

    // subst(IS, FP, AP, HS, OS) from the paper.
    TokenSet substitute(TokenSet inSet,
                        ArrayList<TokenSet> params,
                        ArrayList<TokenSet> args,
                        TokenSet hs,
                        TokenSet outSet) {
        if (inSet.tokens.isEmpty()) {
            return hsAdd(hs, outSet);
        }

        var first = inSet.tokens.get(0);
        var rest = new TokenSet(inSet.tokens.subList(1, inSet.tokens.size()));

        // IS = # • T • IS', T ∈ FP:  OS • stringize(AP[i])
        if (first.token.type == TokenType.STRINGIZE && !rest.tokens.isEmpty()) {
            int i = paramIndex(params, rest.tokens.get(0));
            if (i >= 0 && i < args.size()) {
                var restp = new TokenSet(rest.tokens.subList(1, rest.tokens.size()));
                return substitute(restp, params, args, hs,
                        outSet.concat(TokenSet.from(stringize(args.get(i)))));
            }
        }

        // IS = ## • T • IS'
        if (first.token.type == TokenType.PASTE && !rest.tokens.isEmpty()) {
            var second = rest.tokens.get(0);
            var restp = new TokenSet(rest.tokens.subList(1, rest.tokens.size()));
            int i = paramIndex(params, second);
            if (i >= 0 && i < args.size()) {
                var arg = args.get(i);
                if (arg.tokens.isEmpty()) {
                    // Pasting an empty actual is a no-op.
                    return substitute(restp, params, args, hs, outSet);
                }
                return substitute(restp, params, args, hs, glue(outSet, arg));
            }
            // T is an ordinary token: glue it on directly.
            return substitute(restp, params, args, hs, glue(outSet, TokenSet.from(second)));
        }

        int i = paramIndex(params, first);
        if (i >= 0 && i < args.size()) {
            var arg = args.get(i);

            // IS = T • ## • IS', T ∈ FP
            if (!rest.tokens.isEmpty() && rest.tokens.get(0).token.type == TokenType.PASTE) {
                if (arg.tokens.isEmpty()) {
                    var restp = new TokenSet(rest.tokens.subList(1, rest.tokens.size())); // after ##
                    if (!restp.tokens.isEmpty()) {
                        int j = paramIndex(params, restp.tokens.get(0));
                        if (j >= 0 && j < args.size()) {
                            var restpp = new TokenSet(restp.tokens.subList(1, restp.tokens.size()));
                            return substitute(restpp, params, args, hs,
                                    outSet.concat(withLeadingSpace(args.get(j), first.spaceBefore)));
                        }
                    }
                    return substitute(restp, params, args, hs, outSet);
                }
                // Leave the ## in place; it will glue OS's new tail next.
                return substitute(rest, params, args, hs, outSet.concat(withLeadingSpace(arg, first.spaceBefore)));
            }

            // IS = T • IS', T ∈ FP: plain occurrence, substitute the fully
            // macro-expanded actual.
            return substitute(rest, params, args, hs,
                    outSet.concat(withLeadingSpace(doExpand(arg), first.spaceBefore)));
        }

        // Ordinary token: copy through.
        return substitute(rest, params, args, hs, outSet.concat(TokenSet.from(first)));
    }

    // The '#' operator (6.10.5.3): quote the actual's spelling as one string
    // literal, with each run of white space between its tokens as one space
    // and leading/trailing white space dropped.
    CppToken stringize(TokenSet set) {
        var str = new StringBuilder("\"");
        for (var t : set.tokens) {
            if (str.length() > 1 && t.spaceBefore) {
                str.append(' ');
            }
            var text = t.token.text;
            if (t.token.type == TokenType.STRING_LITERAL || t.token.type == TokenType.CHARACTER_LITERAL) {
                text = text.replace("\\", "\\\\").replace("\"", "\\\"");
            }
            str.append(text);
        }
        str.append('"');

        int line = set.tokens.isEmpty() ? 0 : set.tokens.get(0).token.line;
        int column = set.tokens.isEmpty() ? 0 : set.tokens.get(0).token.column;
        return new CppToken(new Token(TokenType.STRING_LITERAL, str.toString(), line, column));
    }

    // glue(LS, RS): paste the last token of LS with the first token of RS,
    // keeping everything else in place. The pasted token's hide set is the
    // intersection of the two operands' hide sets.
    TokenSet glue(TokenSet lhs, TokenSet rhs) {
        if (lhs.tokens.isEmpty()) {
            return new TokenSet(rhs.tokens);
        }
        if (rhs.tokens.isEmpty()) {
            return new TokenSet(lhs.tokens);
        }
        if (lhs.tokens.size() == 1) {
            var l = lhs.tokens.get(0);
            var r = rhs.tokens.get(0);
            var text = l.token.text + r.token.text;
            var pasted = new CppToken(new Token(pastedType(text), text, l.token.line, l.token.column));
            pasted.spaceBefore = l.spaceBefore;
            l.hideSet.stream()
                    .filter(h -> hideSetContains(r, h.text))
                    .forEach(pasted.hideSet::add);
            return new TokenSet(pasted, new TokenSet(rhs.tokens.subList(1, rhs.tokens.size())));
        }
        var lhsRest = new TokenSet(lhs.tokens.subList(1, lhs.tokens.size()));
        return new TokenSet(lhs.tokens.get(0), glue(lhsRest, rhs));
    }

    // A copy of `ts` whose first token is separated from what precedes it
    // iff `space`: the tokens that replace a macro name or a parameter take
    // over the spacing of the token they replace, not the spacing they had
    // on their #define line.
    private static TokenSet withLeadingSpace(TokenSet ts, boolean space) {
        var result = new TokenSet(ts.tokens); // clones each token
        if (!result.tokens.isEmpty()) {
            result.tokens.get(0).spaceBefore = space;
        }
        return result;
    }

    // Re-lex the pasted spelling so e.g. "A" ## "B" -> identifier AB, which
    // rescanning can then recognize as a macro name.
    private static TokenType pastedType(String text) {
        try {
            var lexed = CppTokenizer.tokenize(text);
            return lexed.size() == 2 ? lexed.get(0).type : TokenType.UNKNOWN;
        } catch (CppTokenizer.LexException e) {
            return TokenType.UNKNOWN;
        }
    }

    // hsadd(HS, TS): union HS into every token of TS, keeping TS's tokens
    // and order unchanged.
    TokenSet hsAdd(TokenSet hs, TokenSet ts) {
        var result = new TokenSet(ts.tokens); // clones each token
        for (var t : result.tokens) {
            for (var h : hs.tokens) {
                t.hideSet.add(h.token);
            }
        }
        return result;
    }

    // Resolves what macro (if any) this occurrence refers to.
    private @Nullable Token definitionOf(CppToken t) {
        return switch (t.token.type) {
            // Resolved at scan time; carries the definition in force at its
            // position in the source (matters across #undef/redefine).
            case OBJECT_MACRO -> t.token;
            // Function-like occurrences and rescanned identifiers resolve
            // against the macro table.
            case CALL_MACRO, IDENTIFIER -> macros.get(t.token.text);
            default -> null;
        };
    }

    // HS ∪ {T}; or (HS ∩ HS') ∪ {T} when the invocation's closing paren
    // carries hide set HS'. Wrapped as a TokenSet for hsAdd.
    private static TokenSet hideSetPlus(CppToken occurrence, Optional<CppToken> closeParen) {
        var list = new ArrayList<CppToken>();
        for (var t : occurrence.hideSet) {
            if (closeParen.map(c -> hideSetContains(c, t.text)).orElse(true)) {
                list.add(new CppToken(t));
            }
        }
        list.add(new CppToken(occurrence.token));
        return new TokenSet(list);
    }

    // Hide sets track macro *names*: occurrences from different source
    // positions must still be considered hidden.
    private static boolean hideSetContains(CppToken t, String name) {
        return t.hideSet.stream().anyMatch(h -> h.text.equals(name));
    }

    private static int paramIndex(ArrayList<TokenSet> params, CppToken t) {
        if (t.token.type != TokenType.IDENTIFIER) {
            return -1;
        }
        for (int i = 0; i < params.size(); i++) {
            var p = params.get(i).tokens;
            if (!p.isEmpty() && p.get(0).token.text.equals(t.token.text)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWithOpenParen(TokenSet ts) {
        if (ts.tokens.isEmpty()) {
            return false;
        }
        var t = ts.tokens.get(0).token;
        return t.type == TokenType.PUNCTUATOR && t.text.equals("(");
    }

    // Index of the ')' matching the '(' at index 0, or -1.
    private static int matchingCloseParen(TokenSet ts) {
        int depth = 0;
        for (int i = 0; i < ts.tokens.size(); i++) {
            var t = ts.tokens.get(i).token;
            if (t.type == TokenType.PUNCTUATOR && t.text.equals("(")) {
                depth++;
            } else if (t.type == TokenType.PUNCTUATOR && t.text.equals(")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // Splits the tokens between an invocation's parens into one TokenSet
    // per top-level comma. `THUNK()` with no formals yields no arguments,
    // while `ONE_ARG()` with one formal yields a single empty argument.
    private static ArrayList<TokenSet> splitArguments(List<CppToken> inner, int paramCount) {
        var args = new ArrayList<TokenSet>();
        if (inner.isEmpty() && paramCount == 0) {
            return args;
        }
        var current = new ArrayList<CppToken>();
        int depth = 0;
        for (var t : inner) {
            if (t.token.type == TokenType.PUNCTUATOR) {
                switch (t.token.text) {
                    case "(" -> depth++;
                    case ")" -> depth--;
                    case "," -> {
                        if (depth == 0) {
                            args.add(new TokenSet(current));
                            current = new ArrayList<>();
                            continue;
                        }
                    }
                }
            }
            current.add(t);
        }
        args.add(new TokenSet(current));
        return args;
    }

    // The tokenizer keeps directive lines ('#define ...', '#undef ...') in
    // the stream, echoing the replacement-list tokens it also captured onto
    // the macro token. Preprocessing consumes directives, so drop those
    // lines before expanding. A '#' is only lexed as a PUNCTUATOR when it
    // starts a line, i.e. when it introduces a directive. A line is the
    // same line only within the same file: an included header's tokens
    // follow the includer's in the stream with their own numbering.
    private static TokenSet stripDirectiveLines(TokenSet ts) {
        var kept = new ArrayList<CppToken>();
        int directiveLine = -1;
        String directiveFile = "";
        for (var t : ts.tokens) {
            if (t.token.type == TokenType.PUNCTUATOR && t.token.text.equals("#")) {
                directiveLine = t.token.line;
                directiveFile = t.token.file;
                continue;
            }
            boolean onDirectiveLine = t.token.line == directiveLine && t.token.file.equals(directiveFile);
            if (onDirectiveLine && t.token.type != TokenType.EOF) {
                continue;
            }
            kept.add(t);
        }
        return new TokenSet(kept);
    }
}

package org.jbm.cc.cpp;

import org.jbm.cc.CppTokenizer;

import java.util.ArrayList;

public class Scanner {

    private CppToken stringize(CppTokenizer.TokenSet set) {
        var str = new StringBuilder();

        for (var t : set.tokens) {
            str.append(t.token.text);
        }

        // TODO line numbers
        return new CppToken(new CppTokenizer.Token(CppTokenizer.TokenType.STRING_LITERAL, str.toString(), 0, 0));
    }

    private CppTokenizer.TokenSet glue(CppTokenizer.TokenSet lhs, CppTokenizer.TokenSet rhs) {
        var lhsFirst = lhs.tokens.get(0);

        if (lhs.tokens.size() == 1 && rhs.tokens.size() > 1) {
            var intersect = rhs.tokens.stream()
                    .filter(t -> t.token.equals(lhsFirst.token))
                    .peek(t ->
                        t.hideSet.retainAll(lhsFirst.hideSet)
                    ).toList();
            return new CppTokenizer.TokenSet(intersect);
        }

        var lhsRest = new CppTokenizer.TokenSet(lhs.tokens.subList(1, lhs.tokens.size()));
        return new CppTokenizer.TokenSet(lhsFirst, glue(lhsRest, rhs));
    }

    private CppTokenizer.TokenSet hsAdd(CppTokenizer.TokenSet hs, CppTokenizer.TokenSet ts) {
        if (ts.tokens.isEmpty()) {
            return CppTokenizer.TokenSet.empty();
        }

        var first = ts.tokens.get(0);
        var tsp = new CppTokenizer.TokenSet(ts.tokens.subList(1, ts.tokens.size()));
        var lhs = hs.dup();
        lhs.tokens.forEach(t -> t.hideSet.addAll(first.hideSet));
        return lhs.concat(hsAdd(hs, tsp));
    }

    private CppTokenizer.TokenSet substitute(CppTokenizer.TokenSet inSet,
                                             ArrayList<CppTokenizer.TokenSet> params,
                                             ArrayList<CppTokenizer.TokenSet> args,
                                             CppTokenizer.TokenSet hs,
                                             CppTokenizer.TokenSet outSet) {
        if (inSet.tokens.isEmpty()) {
            return hsAdd(hs, outSet);
        }

        var first = inSet.tokens.get(0);

        // #define FOO(x) #x
        if (first.token.type == CppTokenizer.TokenType.STRINGIZE) {
            var secondToken = inSet.tokens.get(1);
            var secondSet = CppTokenizer.TokenSet.from(secondToken);

            if (params.contains(secondSet)) {
                var i = params.indexOf(secondSet);
                if (args.size() <= i) {
                    // Bad input
                    // TODO report error
                    return CppTokenizer.TokenSet.empty();
                }
                var list = inSet.tokens.subList(2, inSet.tokens.size());
                var isp = new CppTokenizer.TokenSet(list);

                var arg = args.get(i);
                return substitute(isp, params, args, hs,
                        outSet.concat(stringize(arg)));
            }
        }

        // #define FOO(x, y) x ## y
        if (first.token.type == CppTokenizer.TokenType.PASTE) {
            var secondToken = inSet.tokens.get(1);
            var secondSet = CppTokenizer.TokenSet.from(secondToken);
            if (params.contains(secondSet)) {
                var second = CppTokenizer.TokenSet.from(inSet.tokens.get(1));
                if (!args.contains(second)) {
                    var list = inSet.tokens.subList(2, inSet.tokens.size());
                    var isp = new CppTokenizer.TokenSet(list);
                    return substitute(isp, params, args, hs, outSet);
                }
                var list = inSet.tokens.subList(2, inSet.tokens.size());
                var isp = new CppTokenizer.TokenSet(list);
                var secondArg = args.get(args.indexOf(second));
                var glued = glue(outSet, secondArg);
                return substitute(isp, params, args, hs, glued);
            }
        }

        // #define FOO(x) y ## bar
        if (inSet.tokens.size() > 1) {
            if (first.token.type == CppTokenizer.TokenType.PASTE) {
                var secondToken = inSet.tokens.get(1);
                var secondSet = CppTokenizer.TokenSet.from(secondToken);
                var list = inSet.tokens.subList(2, inSet.tokens.size());
                var isp = new CppTokenizer.TokenSet(list);
                return substitute(isp, params, args, hs, glue(outSet, secondSet));
            }
        }

        // #define FOO(x) x ## bar
        if (inSet.tokens.size() > 1) {
            var firstSet = CppTokenizer.TokenSet.from(first);
            var secondToken = inSet.tokens.get(1);
            if (params.contains(firstSet) && secondToken.token.type == CppTokenizer.TokenType.PASTE) {
                var list = inSet.tokens.subList(2, inSet.tokens.size());
                var isp = new CppTokenizer.TokenSet(list);

                if (!args.contains(firstSet)) {
                    var isppList = isp.tokens.subList(1, isp.tokens.size());
                    var ispp = new CppTokenizer.TokenSet(isppList);
                    var isppFirst = ispp.tokens.get(0);
                    var isppFirstSet = CppTokenizer.TokenSet.from(isppFirst);
                    // IS' = T' + IS'' && T' is PARAM
                    if (params.contains(isppFirstSet)) {
                        var arg = args.get(params.indexOf(isppFirstSet));
                        var out1 = outSet.concat(arg);
                        return substitute(isp, params, args, hs, out1);
                    } else {
                        return substitute(isp, params, args, hs, outSet);
                    }
                } else {
                    var secondSet = CppTokenizer.TokenSet.from(secondToken);
                    return substitute(secondSet.concat(isp),
                            params, args, hs, outSet.concat(args.get(params.indexOf(firstSet))));
                }
            }
        }

        var firstSet = CppTokenizer.TokenSet.from(first);
        var isp = new CppTokenizer.TokenSet(inSet.tokens.subList(1, inSet.tokens.size()));
        return substitute(isp, params, args, hs, outSet.concat(firstSet));
    }

    public CppTokenizer.TokenSet expand(CppTokenizer.TokenSet tokenSet) {
        if (tokenSet.tokens.isEmpty()) {
            return new CppTokenizer.TokenSet(new ArrayList<>());
        }

        var first = tokenSet.tokens.get(0);
        if (first.hideSet.contains(first.token)) {
            return new CppTokenizer.TokenSet(first,
                    expand(new CppTokenizer.TokenSet(
                            tokenSet.tokens.subList(1, tokenSet.tokens.size())))
            );
        }

        if (first.token.type == CppTokenizer.TokenType.IDENTIFIER) {
            var macro = tokenSet.macros.get(first.token.text);
            if (macro.type == CppTokenizer.TokenType.OBJECT_MACRO) {
                var list = macro.expansion.stream().map(CppToken::new).toList();
                var replacement = new CppTokenizer.TokenSet(list);
                var hs = new CppTokenizer.TokenSet(new ArrayList<>());
                hs.concat(first);
                return expand(substitute(replacement,
                        new ArrayList<>(), new ArrayList<>(),
                        hs,
                        CppTokenizer.TokenSet.empty()
                ));
            }

            // CALL_MACRO
            // #define FOO(x) x + 1
            //
            // FOO(x)

            var callMacro = tokenSet.macros.get(first.token.text);
            // TODO each param can be a token set
            var fp = new ArrayList<>(callMacro.params.stream().map(t -> CppTokenizer.TokenSet.from(new CppToken(t))).toList());
            var replacement = callMacro.expansion.stream().map(CppToken::new).toList();
            var replacementSet = new CppTokenizer.TokenSet(replacement);

            var _args = new ArrayList<CppTokenizer.TokenSet>();
            first.token.arguments.stream().map(CppTokenizer.TokenSet::fromTokens).forEach(_args::add);

            var argsHs = new ArrayList<CppToken>();
            for (var arg : _args) {
                argsHs.addAll(arg.tokens);
            }
            var intersect = replacementSet
                    .tokens
                    .stream()
                    .filter(t -> argsHs.contains(new CppToken(t.token))).toList();

            var hs = new CppTokenizer.TokenSet(intersect);
            return expand(substitute(replacementSet, fp, _args, hs, new CppTokenizer.TokenSet(new ArrayList<>())));
        }

        var isp = tokenSet.tokens.subList(1, tokenSet.tokens.size());
        return expand(new CppTokenizer.TokenSet(isp)).concat(first);
    }
}

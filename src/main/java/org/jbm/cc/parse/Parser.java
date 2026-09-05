package org.jbm.cc.parse;

import org.jbm.cc.ast.Attribute;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.ast.Initializer;
import org.jbm.cc.ast.Specifiers;
import org.jbm.cc.ast.Type;
import org.jbm.cc.ast.Type.Quals;
import org.jbm.cc.cpp.CppToken;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.CppTokenizer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Recursive-descent parser for the C2y phrase structure grammar (N3886
 * Annex A.3), being built up section by section (see docs/parser-plan.md). Consumes the phase-7 token list produced by
 * {@code TokenConversion.convert} and produces the {@code org.jbm.cc.ast}
 * tree. Methods are grouped and named after the Annex A nonterminals.
 * <p>
 * The only semantic state kept is the {@link ScopeStack} of typedef names,
 * which the grammar needs to tell {@code T * x;} (declaration) from
 * {@code a * b;} (expression) and {@code (T) x} (cast) from {@code (a) x}.
 * Everything else - lvalues, constant expressions, redeclarations - is left
 * to later passes. Errors are fatal {@link ParseException}s.
 */
public final class Parser {

    private final TokenCursor cur;
    private final ScopeStack scopes = new ScopeStack();

    public Parser(TokenSet tokens) {
        this(tokens.tokens);
    }

    public Parser(List<CppToken> tokens) {
        this.cur = new TokenCursor(tokens);
    }

    // ---- keyword classes --------------------------------------------------

    private static final Set<String> STORAGE_CLASSES = Set.of(
            "auto", "constexpr", "extern", "register", "static", "thread_local", "typedef");

    private static final Set<String> TYPE_QUALIFIERS = Set.of("const", "restrict", "volatile", "_Atomic");

    private static final Set<String> FUNCTION_SPECIFIERS = Set.of("inline", "_Noreturn");

    // Keywords that start a type-specifier (6.7.3.1), i.e. that can begin a
    // type-name once a typedef-name is ruled out.
    private static final Set<String> TYPE_SPECIFIERS = Set.of(
            "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned",
            "_BitInt", "bool", "_Complex", "_Decimal32", "_Decimal64", "_Decimal128",
            "_Atomic", "struct", "union", "enum", "typeof", "typeof_unqual");

    // Binary operator precedence for 6.5.6 - 6.5.15, higher binds tighter.
    private static final Map<String, Integer> BINARY_PRECEDENCE = Map.ofEntries(
            Map.entry("||", 1), Map.entry("&&", 2), Map.entry("|", 3), Map.entry("^", 4),
            Map.entry("&", 5), Map.entry("==", 6), Map.entry("!=", 6),
            Map.entry("<", 7), Map.entry(">", 7), Map.entry("<=", 7), Map.entry(">=", 7),
            Map.entry("<<", 8), Map.entry(">>", 8), Map.entry("+", 9), Map.entry("-", 9),
            Map.entry("*", 10), Map.entry("/", 10), Map.entry("%", 10));

    private static final Set<String> ASSIGNMENT_OPERATORS = Set.of(
            "=", "*=", "/=", "%=", "+=", "-=", "<<=", ">>=", "&=", "^=", "|=");

    /** Can this token begin a type-name (6.7.8), given the current typedef scope? */
    private boolean startsTypeName(Token t) {
        if (t.type == TokenType.KEYWORD) {
            return TYPE_SPECIFIERS.contains(t.text) || TYPE_QUALIFIERS.contains(t.text) || t.text.equals("alignas");
        }
        return t.type == TokenType.IDENTIFIER && scopes.isTypeName(t.text);
    }

    /** Can this token begin declaration-specifiers (6.7.1)? */
    private boolean startsDeclarationSpecifiers(Token t) {
        return startsTypeName(t)
                || (t.type == TokenType.KEYWORD
                && (STORAGE_CLASSES.contains(t.text) || FUNCTION_SPECIFIERS.contains(t.text)));
    }

    private boolean atAttributeSpecifier() {
        return cur.at("[") && cur.at(1, "[");
    }

    // ---- A.3.2 declarations (6.7): the type-name subset ----------------------
    // Casts, sizeof, compound literals and _Generic need type-name (6.7.8),
    // which drags in specifiers, abstract declarators, struct/enum/typeof
    // specifiers, parameter lists and braced initializers. Declarations
    // proper (init-declarators, typedef registration) come in phase 2.

    /**
     * declaration-specifiers (6.7.1) or, with allowStorage false, a
     * specifier-qualifier-list (6.7.3.2). Specifiers may come in any order,
     * so they are collected into a bag and folded to a Type at the end.
     */
    private Specifiers parseDeclarationSpecifiers(boolean allowStorage) {
        Token start = cur.peek();
        var storage = new ArrayList<Token>();
        var functionSpecs = new ArrayList<Token>();
        Specifiers.Alignas alignas = null;
        Quals quals = Quals.NONE;

        // Type-specifier bookkeeping. `named` holds a specifier that is a
        // complete type by itself (struct, enum, typedef-name, typeof,
        // _Atomic(T), _BitInt(N)); the rest are the combinable keywords.
        Type named = null;
        Token base = null;         // void char int float double bool _DecimalN
        Token signedness = null;   // signed | unsigned
        Token shortTok = null;
        Token complexTok = null;
        int longCount = 0;
        boolean seenTypeSpecifier = false;

        while (true) {
            Token t = cur.peek();
            if (atAttributeSpecifier()) {
                // declaration-specifier attribute-specifier-sequenceopt:
                // trailing attributes appertain to the type; not kept.
                parseAttributeSpecifierSequence();
                continue;
            }
            if (t.type == TokenType.IDENTIFIER) {
                // A typedef-name is a type-specifier only while no other
                // type-specifier has been seen (6.7.3.1p2, 6.7.9): in
                // `typedef int T; unsigned T x;` T is the declarator.
                if (seenTypeSpecifier || !scopes.isTypeName(t.text)) break;
                cur.next();
                named = new Type.TypedefName(t, scopes.typedefType(t.text), Quals.NONE);
                seenTypeSpecifier = true;
                continue;
            }
            if (t.type != TokenType.KEYWORD) break;

            if (STORAGE_CLASSES.contains(t.text)) {
                if (!allowStorage) throw cur.error("storage class specifier not allowed here");
                storage.add(cur.next());
            } else if (FUNCTION_SPECIFIERS.contains(t.text)) {
                if (!allowStorage) throw cur.error("function specifier not allowed here");
                functionSpecs.add(cur.next());
            } else if (t.text.equals("_Atomic") && cur.at(1, "(")) {
                // atomic-type-specifier (6.7.3.5): _Atomic ( type-name ).
                if (seenTypeSpecifier) throw cur.error("cannot combine '_Atomic(...)' with other type specifiers");
                cur.next();
                cur.expect("(");
                named = parseTypeName().withQuals(Quals.NONE.plus("_Atomic"));
                cur.expect(")");
                seenTypeSpecifier = true;
            } else if (TYPE_QUALIFIERS.contains(t.text)) {
                quals = quals.plus(cur.next().text);
            } else if (t.text.equals("alignas")) {
                alignas = parseAlignmentSpecifier();
            } else if (TYPE_SPECIFIERS.contains(t.text)) {
                seenTypeSpecifier = true;
                switch (t.text) {
                    case "signed", "unsigned" -> {
                        if (signedness != null) throw cur.error("duplicate signedness specifier");
                        signedness = cur.next();
                    }
                    case "short" -> {
                        if (shortTok != null) throw cur.error("duplicate 'short'");
                        shortTok = cur.next();
                    }
                    case "long" -> {
                        if (++longCount > 2) throw cur.error("too many 'long' specifiers");
                        cur.next();
                    }
                    case "_Complex" -> {
                        if (complexTok != null) throw cur.error("duplicate '_Complex'");
                        complexTok = cur.next();
                    }
                    case "struct", "union" -> named = parseStructOrUnionSpecifier();
                    case "enum" -> named = parseEnumSpecifier();
                    case "typeof", "typeof_unqual" -> named = parseTypeofSpecifier();
                    case "_BitInt" -> {
                        cur.next();
                        cur.expect("(");
                        Expr width = parseConditionalExpression();
                        cur.expect(")");
                        named = new Type.BitInt(t, false, width, Quals.NONE);
                    }
                    default -> {
                        if (base != null) throw cur.error("cannot combine '" + t.text + "' with '" + base.text + "'");
                        base = cur.next();
                    }
                }
            } else {
                break;
            }
        }

        Type type = foldTypeSpecifiers(start, named, base, signedness, shortTok, complexTok, longCount);
        if (type == null && !storage.stream().anyMatch(s -> s.text.equals("auto"))) {
            if (!seenTypeSpecifier && cur.peek() == start) {
                throw cur.error("expected declaration specifiers");
            }
            throw new ParseException("expected a type specifier", start);
        }
        if (type != null) {
            type = type.withQuals(type.quals().plus(quals));
        }
        return new Specifiers(start, storage, functionSpecs, alignas, type);
    }

    // Folds the collected type-specifier keywords into one type per the
    // multiset table of 6.7.3.1p2. Returns null when no type specifier at
    // all was given (legal only with `auto`).
    private Type foldTypeSpecifiers(Token start, Type named, Token base, Token signedness,
                                    Token shortTok, Token complexTok, int longCount) {
        boolean modifiers = signedness != null || shortTok != null || longCount > 0 || complexTok != null;
        boolean unsigned = signedness != null && signedness.text.equals("unsigned");

        if (named != null) {
            if (named instanceof Type.BitInt bi && signedness != null && shortTok == null
                    && longCount == 0 && complexTok == null) {
                return new Type.BitInt(bi.token(), unsigned, bi.width(), Quals.NONE);
            }
            if (modifiers || base != null) throw new ParseException("invalid type specifier combination", start);
            return named;
        }
        if (base == null && !modifiers) return null;

        String b = base == null ? "int" : base.text;
        boolean complex = complexTok != null;
        Type.Kind kind;
        switch (b) {
            case "char" -> {
                if (shortTok != null || longCount > 0 || complex) throw bad(start, b);
                kind = signedness == null ? Type.Kind.CHAR : unsigned ? Type.Kind.UCHAR : Type.Kind.SCHAR;
            }
            case "int" -> {
                if (complex) throw bad(start, b);
                if (shortTok != null) {
                    if (longCount > 0) throw bad(start, "short long");
                    kind = unsigned ? Type.Kind.USHORT : Type.Kind.SHORT;
                } else if (longCount == 1) {
                    kind = unsigned ? Type.Kind.ULONG : Type.Kind.LONG;
                } else if (longCount == 2) {
                    kind = unsigned ? Type.Kind.ULLONG : Type.Kind.LLONG;
                } else {
                    kind = unsigned ? Type.Kind.UINT : Type.Kind.INT;
                }
            }
            case "float" -> {
                if (signedness != null || shortTok != null || longCount > 0) throw bad(start, b);
                kind = Type.Kind.FLOAT;
            }
            case "double" -> {
                if (signedness != null || shortTok != null || longCount > 1) throw bad(start, b);
                kind = longCount == 1 ? Type.Kind.LDOUBLE : Type.Kind.DOUBLE;
            }
            default -> {
                // void bool _Decimal32 _Decimal64 _Decimal128: no modifiers.
                if (modifiers) throw bad(start, b);
                kind = switch (b) {
                    case "void" -> Type.Kind.VOID;
                    case "bool" -> Type.Kind.BOOL;
                    case "_Decimal32" -> Type.Kind.DECIMAL32;
                    case "_Decimal64" -> Type.Kind.DECIMAL64;
                    case "_Decimal128" -> Type.Kind.DECIMAL128;
                    default -> throw bad(start, b);
                };
            }
        }
        return new Type.Basic(start, kind, complex, Quals.NONE);
    }

    private static ParseException bad(Token at, String what) {
        return new ParseException("invalid type specifier combination with '" + what + "'", at);
    }

    // alignment-specifier (6.7.6): alignas ( type-name ) | alignas ( constant-expression ).
    private Specifiers.Alignas parseAlignmentSpecifier() {
        Token kw = cur.expect("alignas");
        cur.expect("(");
        Specifiers.Alignas result;
        if (startsTypeName(cur.peek())) {
            result = new Specifiers.Alignas(kw, parseTypeName(), null);
        } else {
            result = new Specifiers.Alignas(kw, null, parseConditionalExpression());
        }
        cur.expect(")");
        return result;
    }

    // struct-or-union-specifier (6.7.3.2).
    private Type parseStructOrUnionSpecifier() {
        Token kw = cur.next();
        parseAttributeSpecifierSequence();
        Token tag = cur.atIdentifier() ? cur.next() : null;
        List<Type.MemberDecl> members = null;
        if (cur.accept("{")) {
            members = new ArrayList<>();
            while (!cur.accept("}")) {
                parseMemberDeclaration(members);
            }
        } else if (tag == null) {
            throw cur.error("expected identifier or '{' after '" + kw.text + "'");
        }
        return new Type.Struct(kw, tag, members, Quals.NONE);
    }

    // member-declaration (6.7.3.2): attributes, specifier-qualifier-list,
    // then member-declarators (each optionally a bit-field) or nothing for
    // an anonymous struct/union member.
    private void parseMemberDeclaration(List<Type.MemberDecl> out) {
        var attrs = parseAttributeSpecifierSequence();
        if (cur.at("static_assert")) {
            out.add(parseStaticAssertion());
            cur.expect(";");
            return;
        }
        var specs = parseDeclarationSpecifiers(false);
        if (cur.accept(";")) {
            out.add(new Type.Member(attrs, specs.type(), null, null));
            return;
        }
        do {
            Token name = null;
            Type type = specs.type();
            if (!cur.at(":")) {
                var declarator = parseDeclarator(DeclaratorKind.NAMED);
                name = declarator.name();
                type = declarator.build().apply(type);
            }
            Expr width = cur.accept(":") ? parseConditionalExpression() : null;
            out.add(new Type.Member(attrs, type, name, width));
        } while (cur.accept(","));
        cur.expect(";");
    }

    // enum-specifier (6.7.3.3), including the C23 enum-type-specifier.
    private Type parseEnumSpecifier() {
        Token kw = cur.expect("enum");
        parseAttributeSpecifierSequence();
        Token tag = cur.atIdentifier() ? cur.next() : null;
        Type underlying = null;
        if (cur.accept(":")) {
            underlying = parseDeclarationSpecifiers(false).type();
        }
        List<Type.Enumerator> enumerators = null;
        if (cur.accept("{")) {
            enumerators = new ArrayList<>();
            while (!cur.at("}")) {
                Token name = cur.expectIdentifier();
                var attrs = parseAttributeSpecifierSequence();
                Expr value = cur.accept("=") ? parseConditionalExpression() : null;
                // Enumeration constants are ordinary identifiers in the
                // enclosing scope (6.2.1), so they hide typedef names.
                scopes.declareOrdinary(name.text);
                enumerators.add(new Type.Enumerator(name, attrs, value));
                if (!cur.accept(",")) break;
            }
            cur.expect("}");
        } else if (tag == null) {
            throw cur.error("expected identifier or '{' after 'enum'");
        }
        return new Type.Enum(kw, tag, underlying, enumerators, Quals.NONE);
    }

    // typeof-specifier (6.7.3.6): the operand is a type-name if it can be.
    private Type parseTypeofSpecifier() {
        Token kw = cur.next();
        cur.expect("(");
        Type result;
        if (startsTypeName(cur.peek())) {
            result = new Type.Typeof(kw, null, parseTypeName(), Quals.NONE);
        } else {
            result = new Type.Typeof(kw, parseExpression(), null, Quals.NONE);
        }
        cur.expect(")");
        return result;
    }

    /** type-name (6.7.8): specifier-qualifier-list abstract-declaratoropt. */
    private Type parseTypeName() {
        var specs = parseDeclarationSpecifiers(false);
        return parseDeclarator(DeclaratorKind.ABSTRACT).build().apply(specs.type());
    }

    // ---- declarators (6.7.7, 6.7.8) ------------------------------------------

    private enum DeclaratorKind {
        NAMED,      // declarator: an identifier is required
        ABSTRACT,   // abstract-declarator: no identifier allowed
        PARAMETER   // parameter-declaration: either
    }

    /**
     * A parsed (possibly abstract) declarator: the declared name, if any,
     * and a function that wraps the declaration-specifiers' type into the
     * declared type. Pointer prefixes and array/function suffixes compose
     * as closures, so `int (*a)[3]` and `int *a[3]` come out right without
     * any inside-out bookkeeping.
     */
    private record Declarator(Token name, UnaryOperator<Type> build, List<Attribute> attributes) {
    }

    private Declarator parseDeclarator(DeclaratorKind kind) {
        if (cur.at("*")) {
            // pointer: * attribute-specifier-sequenceopt type-qualifier-listopt
            Token star = cur.next();
            parseAttributeSpecifierSequence();
            Quals quals = parseTypeQualifierList();
            Declarator inner = parseDeclarator(kind);
            return new Declarator(inner.name(),
                    t -> inner.build().apply(new Type.Pointer(star, t, quals)), inner.attributes());
        }
        return parseDirectDeclarator(kind);
    }

    private Declarator parseDirectDeclarator(DeclaratorKind kind) {
        Token name = null;
        UnaryOperator<Type> build = t -> t;
        List<Attribute> attrs = List.of();

        if (cur.atIdentifier() && kind != DeclaratorKind.ABSTRACT) {
            name = cur.next();
            attrs = parseAttributeSpecifierSequence();
        } else if (cur.at("(") && startsNestedDeclarator(kind)) {
            cur.next();
            Declarator inner = parseDeclarator(kind);
            cur.expect(")");
            name = inner.name();
            build = inner.build();
            attrs = inner.attributes();
        } else if (kind == DeclaratorKind.NAMED) {
            throw cur.error("expected identifier or '(' in declarator");
        }

        // array-declarator / function-declarator suffixes. Each suffix
        // applies to the specifiers' type first, and the declarator built
        // so far wraps the result: `a[2][3]` is array 2 of array 3.
        while (true) {
            if (cur.at("[") && !atAttributeSpecifier()) {
                UnaryOperator<Type> array = parseArrayDeclaratorSuffix();
                UnaryOperator<Type> prev = build;
                build = t -> prev.apply(array.apply(t));
            } else if (cur.at("(")) {
                Token paren = cur.next();
                var params = parseParameterTypeList();
                UnaryOperator<Type> prev = build;
                build = t -> prev.apply(new Type.Function(paren, t, params.parameters(), params.variadic(), Quals.NONE));
            } else {
                break;
            }
            parseAttributeSpecifierSequence();
        }
        return new Declarator(name, build, attrs);
    }

    // A '(' inside an abstract or parameter declarator either nests a
    // declarator or opens a parameter list. It is a parameter list when
    // what follows can start a parameter-declaration or ends the list.
    private boolean startsNestedDeclarator(DeclaratorKind kind) {
        if (kind == DeclaratorKind.NAMED) return true;
        Token t = cur.peek(1);
        if (t.type == TokenType.PUNCTUATOR) {
            return !t.text.equals(")") && !t.text.equals("...");
        }
        return !startsDeclarationSpecifiers(t);
    }

    // [ type-qualifier-listopt assignment-expressionopt ]
    // [ static type-qualifier-listopt assignment-expression ]
    // [ type-qualifier-list static assignment-expression ]
    // [ type-qualifier-listopt * ]
    private UnaryOperator<Type> parseArrayDeclaratorSuffix() {
        Token bracket = cur.expect("[");
        boolean isStatic = cur.accept("static");
        Quals quals = parseTypeQualifierList();
        if (!isStatic && cur.accept("static")) isStatic = true;
        boolean star = false;
        Expr size = null;
        if (cur.at("*") && cur.at(1, "]")) {
            cur.next();
            star = true;
        } else if (!cur.at("]")) {
            size = parseAssignmentExpression();
        } else if (isStatic) {
            throw cur.error("'static' array parameter requires a size");
        }
        cur.expect("]");
        final boolean fStatic = isStatic, fStar = star;
        final Expr fSize = size;
        return t -> new Type.Array(bracket, t, fSize, fStar, fStatic, quals);
    }

    private Quals parseTypeQualifierList() {
        Quals quals = Quals.NONE;
        while (cur.peek().type == TokenType.KEYWORD && TYPE_QUALIFIERS.contains(cur.peek().text)) {
            quals = quals.plus(cur.next().text);
        }
        return quals;
    }

    private record ParameterTypeList(List<Type.Parameter> parameters, boolean variadic) {
    }

    // parameter-type-list (6.7.7.1), '(' already consumed. Parameter names
    // live in a function prototype scope that ends at the ')' (6.2.1p4).
    private ParameterTypeList parseParameterTypeList() {
        var params = new ArrayList<Type.Parameter>();
        boolean variadic = false;
        if (cur.accept(")")) {
            return new ParameterTypeList(params, false);
        }
        scopes.push();
        while (true) {
            if (cur.at("...")) {
                cur.next();
                variadic = true;
                break;
            }
            params.add(parseParameterDeclaration());
            if (!cur.accept(",")) break;
        }
        scopes.pop();
        cur.expect(")");
        // 6.7.7.4p10: a lone unnamed `void` means no parameters.
        if (params.size() == 1 && !variadic && params.get(0).name() == null
                && params.get(0).type() instanceof Type.Basic b
                && b.kind() == Type.Kind.VOID && b.quals().isEmpty()) {
            params.clear();
        }
        return new ParameterTypeList(params, variadic);
    }

    private Type.Parameter parseParameterDeclaration() {
        var attrs = parseAttributeSpecifierSequence();
        var specs = parseDeclarationSpecifiers(true);
        if (specs.type() == null) throw cur.error("parameter declaration requires a type");
        var declarator = parseDeclarator(DeclaratorKind.PARAMETER);
        if (declarator.name() != null) scopes.declareOrdinary(declarator.name().text);
        return new Type.Parameter(attrs, specs.storageClasses(),
                declarator.build().apply(specs.type()), declarator.name());
    }

    // ---- initializers (6.7.11) ---------------------------------------------

    private Initializer parseInitializer() {
        if (cur.at("{")) return parseBracedInitializer();
        return new Initializer.Expression(parseAssignmentExpression());
    }

    private Initializer.Braced parseBracedInitializer() {
        Token brace = cur.expect("{");
        var items = new ArrayList<Initializer.Item>();
        while (!cur.at("}")) {
            var designators = new ArrayList<Initializer.Designator>();
            while (cur.at("[") || cur.at(".")) {
                if (cur.at("[")) {
                    Token bracket = cur.next();
                    Expr index = parseConditionalExpression();
                    cur.expect("]");
                    designators.add(new Initializer.ArrayDesignator(bracket, index));
                } else {
                    Token dot = cur.next();
                    designators.add(new Initializer.MemberDesignator(dot, cur.expectIdentifier()));
                }
            }
            if (!designators.isEmpty()) cur.expect("=");
            items.add(new Initializer.Item(designators, parseInitializer()));
            if (!cur.accept(",")) break;
        }
        cur.expect("}");
        return new Initializer.Braced(brace, items);
    }

    // ---- attributes (6.7.12) -------------------------------------------------

    /** attribute-specifier-sequenceopt: zero or more {@code [[ attribute-list ]]}. */
    private List<Attribute> parseAttributeSpecifierSequence() {
        if (!atAttributeSpecifier()) return List.of();
        var attrs = new ArrayList<Attribute>();
        while (atAttributeSpecifier()) {
            cur.next();
            cur.next();
            while (!cur.at("]")) {
                if (cur.accept(",")) continue; // attribute-list allows empty entries
                attrs.add(parseAttribute());
            }
            cur.expect("]");
            cur.expect("]");
        }
        return attrs;
    }

    // attribute: attribute-token attribute-argument-clauseopt. Attribute
    // names are lexically identifiers, but keywords are accepted too since
    // vendor prefixes commonly use them (gnu::const).
    private Attribute parseAttribute() {
        Token name = parseAttributeName();
        Token prefix = null;
        if (cur.accept("::")) {
            prefix = name;
            name = parseAttributeName();
        }
        List<Token> args = null;
        if (cur.at("(")) {
            args = parseBalancedTokenSequence();
        }
        return new Attribute(name, prefix, args);
    }

    private Token parseAttributeName() {
        Token t = cur.peek();
        if (t.type != TokenType.IDENTIFIER && t.type != TokenType.KEYWORD) {
            throw cur.error("expected attribute name");
        }
        return cur.next();
    }

    // ( balanced-token-sequenceopt ): returns the tokens strictly inside
    // the outer parentheses, brackets and braces nested.
    private List<Token> parseBalancedTokenSequence() {
        cur.expect("(");
        var tokens = new ArrayList<Token>();
        int depth = 1;
        while (depth > 0) {
            if (cur.atEof()) throw cur.error("unterminated attribute argument clause");
            Token t = cur.next();
            if (t.type == TokenType.PUNCTUATOR) {
                switch (t.text) {
                    case "(", "[", "{" -> depth++;
                    case ")", "]", "}" -> depth--;
                    default -> { }
                }
            }
            if (depth > 0) tokens.add(t);
        }
        return tokens;
    }

    // ---- A.3.1 expressions (6.5) ----------------------------------------------

    /** Parses a complete expression that must consume all input; for tests and tools. */
    public Expr parseStandaloneExpression() {
        Expr e = parseExpression();
        if (!cur.atEof()) throw cur.error("unexpected token after expression");
        return e;
    }

    /** expression (6.5.18): assignment-expressions separated by commas. */
    public Expr parseExpression() {
        Expr left = parseAssignmentExpression();
        while (cur.at(",")) {
            Token comma = cur.next();
            left = new Expr.Comma(comma, left, parseAssignmentExpression());
        }
        return left;
    }

    // assignment-expression (6.5.17.1). The grammar wants a unary-expression
    // on the left; like other compilers we accept any conditional-expression
    // here and leave "not assignable" to sema.
    private Expr parseAssignmentExpression() {
        Expr left = parseConditionalExpression();
        Token t = cur.peek();
        if (t.type == TokenType.PUNCTUATOR && ASSIGNMENT_OPERATORS.contains(t.text)) {
            cur.next();
            return new Expr.Assign(t, left, parseAssignmentExpression());
        }
        return left;
    }

    // conditional-expression (6.5.16), also constant-expression (6.6.1).
    private Expr parseConditionalExpression() {
        Expr cond = parseBinaryExpression(1);
        if (!cur.at("?")) return cond;
        Token q = cur.next();
        Expr thenExpr = parseExpression();
        cur.expect(":");
        Expr elseExpr = parseConditionalExpression();
        return new Expr.Conditional(q, cond, thenExpr, elseExpr);
    }

    // 6.5.6 - 6.5.15 in one precedence-climbing loop. All these operators
    // are left-associative, so the right operand is parsed at minPrec + 1.
    private Expr parseBinaryExpression(int minPrec) {
        Expr left = parseCastExpression();
        while (true) {
            Token t = cur.peek();
            Integer prec = t.type == TokenType.PUNCTUATOR ? BINARY_PRECEDENCE.get(t.text) : null;
            if (prec == null || prec < minPrec) return left;
            cur.next();
            Expr right = parseBinaryExpression(prec + 1);
            left = new Expr.Binary(t, left, right);
        }
    }

    // cast-expression (6.5.5): `( type-name ) cast-expression`, unless the
    // parenthesized type is followed by '{' - then it is a compound literal
    // (6.5.3.6), which is a postfix-expression.
    private Expr parseCastExpression() {
        if (cur.at("(") && startsCompoundLiteralOrTypeName(cur.peek(1))) {
            Token paren = cur.next();
            var storage = parseStorageClassSpecifiers();
            Type type = parseTypeName();
            cur.expect(")");
            if (cur.at("{")) {
                return parsePostfixSuffixes(new Expr.CompoundLiteral(paren, storage, type, parseBracedInitializer()));
            }
            if (!storage.isEmpty()) throw cur.error("expected '{' after compound literal type");
            return new Expr.Cast(paren, type, parseCastExpression());
        }
        return parseUnaryExpression();
    }

    private boolean startsCompoundLiteralOrTypeName(Token t) {
        return startsTypeName(t) || (t.type == TokenType.KEYWORD && STORAGE_CLASSES.contains(t.text));
    }

    private List<Token> parseStorageClassSpecifiers() {
        var list = new ArrayList<Token>();
        while (cur.peek().type == TokenType.KEYWORD && STORAGE_CLASSES.contains(cur.peek().text)) {
            list.add(cur.next());
        }
        return list;
    }

    // unary-expression (6.5.4.1).
    private Expr parseUnaryExpression() {
        Token t = cur.peek();
        if (t.type == TokenType.PUNCTUATOR) {
            switch (t.text) {
                case "++", "--" -> {
                    cur.next();
                    return new Expr.Unary(t, parseUnaryExpression());
                }
                case "&", "*", "+", "-", "~", "!" -> {
                    cur.next();
                    return new Expr.Unary(t, parseCastExpression());
                }
                default -> { }
            }
        } else if (t.type == TokenType.KEYWORD) {
            switch (t.text) {
                case "sizeof", "_Countof" -> {
                    cur.next();
                    // `sizeof ( type-name )` - unless the type is followed by
                    // '{', which makes it `sizeof compound-literal`.
                    if (cur.at("(") && startsCompoundLiteralOrTypeName(cur.peek(1))) {
                        Token paren = cur.next();
                        var storage = parseStorageClassSpecifiers();
                        Type type = parseTypeName();
                        cur.expect(")");
                        if (cur.at("{")) {
                            Expr literal = parsePostfixSuffixes(
                                    new Expr.CompoundLiteral(paren, storage, type, parseBracedInitializer()));
                            return new Expr.Unary(t, literal);
                        }
                        if (!storage.isEmpty()) throw cur.error("expected '{' after compound literal type");
                        return new Expr.TypeOperator(t, type);
                    }
                    return new Expr.Unary(t, parseUnaryExpression());
                }
                case "alignof" -> {
                    cur.next();
                    cur.expect("(");
                    Type type = parseTypeName();
                    cur.expect(")");
                    return new Expr.TypeOperator(t, type);
                }
                case "static_assert" -> {
                    return parseStaticAssertion();
                }
                default -> { }
            }
        }
        return parsePostfixSuffixes(parsePrimaryExpression());
    }

    // static-assertion (6.5.4.6), without the ';' a declaration adds.
    private Expr.StaticAssertion parseStaticAssertion() {
        Token kw = cur.expect("static_assert");
        cur.expect("(");
        Expr cond = parseConditionalExpression();
        Expr.StringLiteral message = null;
        if (cur.accept(",")) {
            if (cur.peek().type != TokenType.STRING_LITERAL) throw cur.error("expected string literal");
            message = parseStringLiteral();
        }
        cur.expect(")");
        return new Expr.StaticAssertion(kw, cond, message);
    }

    // The suffix part of postfix-expression (6.5.3.1) applied to an operand.
    private Expr parsePostfixSuffixes(Expr e) {
        while (true) {
            Token t = cur.peek();
            if (t.type != TokenType.PUNCTUATOR) return e;
            switch (t.text) {
                case "[" -> {
                    cur.next();
                    Expr index = parseExpression();
                    cur.expect("]");
                    e = new Expr.Index(t, e, index);
                }
                case "(" -> {
                    cur.next();
                    var args = new ArrayList<Expr>();
                    if (!cur.at(")")) {
                        do {
                            args.add(parseAssignmentExpression());
                        } while (cur.accept(","));
                    }
                    cur.expect(")");
                    e = new Expr.Call(t, e, args);
                }
                case ".", "->" -> {
                    cur.next();
                    e = new Expr.Member(t, e, cur.expectIdentifier());
                }
                case "++", "--" -> {
                    cur.next();
                    e = new Expr.Postfix(t, e);
                }
                default -> {
                    return e;
                }
            }
        }
    }

    // primary-expression (6.5.2), plus the compound-literal alternative of
    // postfix-expression since both begin with '('.
    private Expr parsePrimaryExpression() {
        Token t = cur.peek();
        switch (t.type) {
            case IDENTIFIER -> {
                cur.next();
                return new Expr.Identifier(t);
            }
            case INTEGER_CONSTANT, FLOATING_CONSTANT, CHARACTER_LITERAL -> {
                cur.next();
                return new Expr.Literal(t);
            }
            case STRING_LITERAL -> {
                return parseStringLiteral();
            }
            case KEYWORD -> {
                switch (t.text) {
                    case "true", "false", "nullptr" -> {
                        cur.next();
                        return new Expr.Literal(t);
                    }
                    case "_Generic" -> {
                        return parseGenericSelection();
                    }
                    default -> throw cur.error("expected expression");
                }
            }
            case PUNCTUATOR -> {
                if (cur.at("(")) {
                    cur.next();
                    if (startsCompoundLiteralOrTypeName(cur.peek())) {
                        var storage = parseStorageClassSpecifiers();
                        Type type = parseTypeName();
                        cur.expect(")");
                        if (!cur.at("{")) throw cur.error("expected '{' after compound literal type");
                        return new Expr.CompoundLiteral(t, storage, type, parseBracedInitializer());
                    }
                    Expr inner = parseExpression();
                    cur.expect(")");
                    return inner;
                }
                throw cur.error("expected expression");
            }
            default -> throw cur.error("expected expression");
        }
    }

    // Adjacent string literals are one string-literal token sequence that
    // phase 6 concatenates; kept as parts so encoding prefixes survive.
    private Expr.StringLiteral parseStringLiteral() {
        var parts = new ArrayList<Token>();
        while (cur.peek().type == TokenType.STRING_LITERAL) {
            parts.add(cur.next());
        }
        if (parts.isEmpty()) throw cur.error("expected string literal");
        return new Expr.StringLiteral(parts);
    }

    // generic-selection (6.5.2.1). The controlling operand may be a type-name (C2y).
    private Expr parseGenericSelection() {
        Token kw = cur.expect("_Generic");
        cur.expect("(");
        Expr controllingExpr = null;
        Type controllingType = null;
        if (startsTypeName(cur.peek())) {
            controllingType = parseTypeName();
        } else {
            controllingExpr = parseAssignmentExpression();
        }
        cur.expect(",");
        var associations = new ArrayList<Expr.Generic.Association>();
        do {
            Type type = cur.accept("default") ? null : parseTypeName();
            cur.expect(":");
            associations.add(new Expr.Generic.Association(type, parseAssignmentExpression()));
        } while (cur.accept(","));
        cur.expect(")");
        return new Expr.Generic(kw, controllingExpr, controllingType, associations);
    }
}

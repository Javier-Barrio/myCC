package org.jbm.mycc.cc.parse;

import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import org.jbm.mycc.cc.sema.types.Std;
import lombok.NonNull;
import org.jbm.mycc.cc.parse.ast.Attribute;
import org.jbm.mycc.cc.parse.ast.BlockItem;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.parse.ast.Expr;
import org.jbm.mycc.cc.parse.ast.Initializer;
import org.jbm.mycc.cc.parse.ast.Specifiers;
import org.jbm.mycc.cc.parse.ast.Stmt;
import org.jbm.mycc.cc.parse.ast.Type;
import org.jbm.mycc.cc.parse.ast.Type.Quals;
import org.jbm.mycc.cc.cpp.CppToken;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Recursive-descent parser for the C2y phrase structure grammar (N3886
 * Annex A.3). Consumes the phase-7 token list produced by
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

    // Script mode: a statement may appear where an external declaration
    // may; the file-scope statements become the body of a synthetic
    // `void .file(void)` appended to the unit, and the last statement
    // needs no ';' before the end of input. What the shell compiles.
    private final boolean script;

    public Parser(@NonNull TokenSet tokens) {
        this(tokens.tokens);
    }

    public Parser(@NonNull List<CppToken> tokens) {
        this(tokens, false, Std.C23);
    }

    // The standard: whether `()` declares no prototype and an old-style
    // definition is accepted (C17), or `()` is `(void)` (C23).
    private final Std std;

    // The function whose body is being parsed, for __func__.
    private String currentFunction;

    // The identifier list of each old-style declarator, by its '(' token,
    // for the function definition that must follow.
    private final Map<Token, List<Token>> identifierLists = new IdentityHashMap<>();

    private Parser(List<CppToken> tokens, boolean script, Std std) {
        this.cur = new TokenCursor(tokens);
        this.script = script;
        this.std = std;
    }

    /** Parses a whole translation-unit (6.9.1). */
    public static List<Decl> parse(@NonNull TokenSet tokens) {
        return parse(tokens, Std.C23);
    }

    public static List<Decl> parse(@NonNull TokenSet tokens, @NonNull Std std) {
        return new Parser(tokens.tokens, false, std).parseTranslationUnit();
    }

    /** The name of the function that holds a script's file-scope statements. */
    public static final String FILE_FUNCTION = ".file";

    /** Parses a script: a translation unit that may also have statements at file scope. */
    public static List<Decl> parseScript(@NonNull TokenSet tokens) {
        return parseScript(tokens, Std.C23);
    }

    public static List<Decl> parseScript(@NonNull TokenSet tokens, @NonNull Std std) {
        return new Parser(tokens.tokens, true, std).parseTranslationUnit();
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
        return t.type == TokenType.IDENTIFIER && (scopes.isTypeName(t.text) || t.text.equals("__attribute__"));
    }

    /** Can this token begin declaration-specifiers (6.7.1)? */
    private boolean startsDeclarationSpecifiers(Token t) {
        return startsTypeName(t)
                || (t.type == TokenType.KEYWORD
                && (STORAGE_CLASSES.contains(t.text) || FUNCTION_SPECIFIERS.contains(t.text)));
    }

    /**
     * Is the input at a declaration rather than a statement/expression? An
     * identifier that names a type starts a declaration unless it is
     * immediately followed by ':' - then it is a label (6.8.2).
     */
    private boolean atDeclaration() {
        Token t = cur.peek();
        if (t.type == TokenType.IDENTIFIER) {
            return atGnuAttribute() || (scopes.isTypeName(t.text) && !cur.at(1, ":"));
        }
        return t.type == TokenType.KEYWORD
                && (startsDeclarationSpecifiers(t) || t.text.equals("static_assert"));
    }

    private boolean atAttributeSpecifier() {
        return (cur.at("[") && cur.at(1, "[")) || atGnuAttribute();
    }

    // `__attribute__((...))`, the GNU spelling: accepted wherever an
    // attribute specifier is, and before a declarator, then dropped.
    private boolean atGnuAttribute() {
        Token t = cur.peek();
        return t.type == TokenType.IDENTIFIER && t.text.equals("__attribute__") && cur.at(1, "(");
    }

    // ---- A.3.4 external definitions (6.9) -----------------------------------

    public List<Decl> parseTranslationUnit() {
        var decls = new ArrayList<Decl>();
        var statements = new ArrayList<BlockItem>();
        Token first = null;
        while (!cur.atEof()) {
            if (script && cur.accept(";")) {
                continue;
            }
            if (script && atFileScopeStatement()) {
                if (first == null) {
                    first = cur.peek();
                }
                statements.add(parseStatement());
            } else {
                decls.add(parseExternalDeclaration());
            }
        }
        if (first != null) {
            decls.add(fileFunction(first, statements));
        }
        return decls;
    }

    // In a script, whatever cannot begin an external declaration is a
    // statement: an expression, a keyword like `if` or `for`, a block,
    // or a label.
    private boolean atFileScopeStatement() {
        if (atAttributeSpecifier() || cur.at("static_assert")) {
            return false;
        }
        if (atLabel()) {
            return true;
        }
        return !atDeclaration();
    }

    // `void .file(void) { statements }`, located at the first statement.
    private static Decl.FunctionDefinition fileFunction(Token first, List<BlockItem> statements) {
        Token name = new Token(TokenType.IDENTIFIER, FILE_FUNCTION, first.line, first.column);
        name.file = first.file;
        Type voidType = new Type.Basic(first, Type.Kind.VOID, false, Quals.NONE);
        Type.Function type = new Type.Function(first, voidType, List.of(), false, true, Quals.NONE);
        Specifiers specs = new Specifiers(first, List.of(), List.of(), Optional.empty(), Optional.of(voidType));
        Stmt.Compound body = new Stmt.Compound(first, statements);
        return new Decl.FunctionDefinition(List.of(), specs, name, type, body);
    }

    // external-declaration: function-definition | declaration. Both start
    // with attributes, declaration-specifiers and a declarator; a '{' after
    // the first declarator makes it a function-definition (6.9.2).
    private Decl parseExternalDeclaration() {
        var attrs = parseAttributeSpecifierSequence();
        if (cur.at("static_assert")) {
            var assertion = parseStaticAssertion();
            cur.expect(";");
            return assertion;
        }
        if (!attrs.isEmpty() && cur.accept(";")) {
            return new Decl.AttributeDeclaration(attrs);
        }
        var specs = parseDeclarationSpecifiers(true);
        if (cur.accept(";")) {
            return new Decl.Declaration(attrs, specs, List.of());
        }
        var first = parseDeclarator(DeclaratorKind.NAMED);
        List<Token> identifiers = identifierListOf(first, specs);
        if (cur.at("{") || !identifiers.isEmpty()) {
            return parseFunctionDefinition(attrs, specs, first, identifiers);
        }
        var declarators = parseInitDeclaratorList(specs, first);
        cur.expect(";");
        return new Decl.Declaration(attrs, specs, declarators);
    }

    // The identifier list of an old-style declarator, else nothing.
    private List<Token> identifierListOf(Declarator declarator, Specifiers specs) {
        if (applyDeclarator(declarator, specs).orElse(null) instanceof Type.Function fn) {
            return identifierLists.getOrDefault(fn.paren(), List.of());
        }
        return List.of();
    }

    private Decl.FunctionDefinition parseFunctionDefinition(List<Attribute> attrs, Specifiers specs,
                                                            Declarator declarator, List<Token> identifiers) {
        if (!(applyDeclarator(declarator, specs).orElse(null) instanceof Type.Function fn)) {
            throw cur.error("expected ';' after declarator (only a function can have a body)");
        }
        Token name = declarator.name().orElseThrow();
        scopes.declareOrdinary(name.text);
        currentFunction = name.text;
        // 6.2.1p4: parameters have block scope in the function body.
        scopes.push();
        if (!identifiers.isEmpty()) {
            fn = oldStyleParameters(fn, identifiers);
        }
        for (var p : fn.parameters()) {
            p.name().ifPresent(n -> scopes.declareOrdinary(n.text));
        }
        var body = parseCompoundStatement(false);
        scopes.pop();
        currentFunction = null;
        return new Decl.FunctionDefinition(attrs, specs, name, fn, body);
    }

    // ---- A.3.2 declarations (6.7) ------------------------------------------

    /** declaration (6.7.1), attributes not yet consumed. */
    private Decl parseDeclaration() {
        return parseDeclaration(parseAttributeSpecifierSequence());
    }

    private Decl parseDeclaration(List<Attribute> attrs) {
        if (!attrs.isEmpty() && cur.accept(";")) {
            return new Decl.AttributeDeclaration(attrs);
        }
        if (cur.at("static_assert")) {
            var assertion = parseStaticAssertion();
            cur.expect(";");
            return assertion;
        }
        var specs = parseDeclarationSpecifiers(true);
        List<Decl.InitDeclarator> declarators = List.of();
        if (!cur.at(";")) {
            declarators = parseInitDeclaratorList(specs, parseDeclarator(DeclaratorKind.NAMED));
        }
        cur.expect(";");
        return new Decl.Declaration(attrs, specs, declarators);
    }

    // init-declarator-list, given the already-parsed first declarator.
    private List<Decl.InitDeclarator> parseInitDeclaratorList(Specifiers specs, Declarator first) {
        var list = new ArrayList<Decl.InitDeclarator>();
        list.add(parseInitDeclaratorRest(specs, first));
        while (cur.accept(",")) {
            list.add(parseInitDeclaratorRest(specs, parseDeclarator(DeclaratorKind.NAMED)));
        }
        return list;
    }

    // Completes an init-declarator: builds the type, brings the name into
    // scope (its scope starts right after the declarator, 6.2.1p7, so
    // `int x = x;` sees the new x), then parses the initializer if any.
    private Decl.InitDeclarator parseInitDeclaratorRest(Specifiers specs, Declarator declarator) {
        Optional<Type> type = applyDeclarator(declarator, specs);
        Token name = declarator.name().orElseThrow();
        if (specs.isTypedef()) {
            scopes.declareTypedef(name.text, type.orElseThrow(
                    () -> cur.error("'auto' cannot be used in a typedef")));
        } else {
            scopes.declareOrdinary(name.text);
        }
        Optional<Initializer> init = Optional.empty();
        if (cur.accept("=")) {
            init = Optional.of(parseInitializer());
        } else if (type.isEmpty()) {
            throw cur.error("'auto' declaration of '" + name.text + "' requires an initializer");
        }
        return new Decl.InitDeclarator(name, type, declarator.attributes(), init);
    }

    // An absent specifier type is `auto` type inference; only a plain
    // identifier declarator is meaningful then.
    private static Optional<Type> applyDeclarator(Declarator declarator, Specifiers specs) {
        return specs.type().map(declarator.build());
    }

    /**
     * declaration-specifiers (6.7.1) or, with allowStorage false, a
     * specifier-qualifier-list (6.7.3.2). Specifiers may come in any order,
     * so they are collected into a bag and folded to a Type at the end.
     */
    private Specifiers parseDeclarationSpecifiers(boolean allowStorage) {
        Token start = cur.peek();
        var storage = new ArrayList<Token>();
        var functionSpecs = new ArrayList<Token>();
        Optional<Specifiers.Alignas> alignas = Optional.empty();
        Quals quals = Quals.NONE;

        // Type-specifier bookkeeping. `named` holds a specifier that is a
        // complete type by itself (struct, enum, typedef-name, typeof,
        // _Atomic(T), _BitInt(N)); the rest are the combinable keywords.
        Optional<Type> named = Optional.empty();
        Optional<Token> base = Optional.empty();        // void char int float double bool _DecimalN
        Optional<Token> signedness = Optional.empty();  // signed | unsigned
        boolean seenShort = false;
        boolean seenComplex = false;
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
                named = Optional.of(new Type.TypedefName(t, scopes.typedefType(t.text).orElseThrow(), Quals.NONE));
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
                named = Optional.of(parseTypeName().withQuals(Quals.NONE.plus("_Atomic")));
                cur.expect(")");
                seenTypeSpecifier = true;
            } else if (TYPE_QUALIFIERS.contains(t.text)) {
                quals = quals.plus(cur.next().text);
            } else if (t.text.equals("alignas")) {
                alignas = Optional.of(parseAlignmentSpecifier());
            } else if (TYPE_SPECIFIERS.contains(t.text)) {
                seenTypeSpecifier = true;
                switch (t.text) {
                    case "signed", "unsigned" -> {
                        if (signedness.isPresent()) throw cur.error("duplicate signedness specifier");
                        signedness = Optional.of(cur.next());
                    }
                    case "short" -> {
                        if (seenShort) throw cur.error("duplicate 'short'");
                        seenShort = true;
                        cur.next();
                    }
                    case "long" -> {
                        if (++longCount > 2) throw cur.error("too many 'long' specifiers");
                        cur.next();
                    }
                    case "_Complex" -> {
                        if (seenComplex) throw cur.error("duplicate '_Complex'");
                        seenComplex = true;
                        cur.next();
                    }
                    case "struct", "union" -> named = Optional.of(parseStructOrUnionSpecifier());
                    case "enum" -> named = Optional.of(parseEnumSpecifier());
                    case "typeof", "typeof_unqual" -> named = Optional.of(parseTypeofSpecifier());
                    case "_BitInt" -> {
                        cur.next();
                        cur.expect("(");
                        Expr width = parseConditionalExpression();
                        cur.expect(")");
                        named = Optional.of(new Type.BitInt(t, false, width, Quals.NONE));
                    }
                    default -> {
                        if (base.isPresent()) {
                            throw cur.error("cannot combine '" + t.text + "' with '" + base.get().text + "'");
                        }
                        base = Optional.of(cur.next());
                    }
                }
            } else {
                break;
            }
        }

        Optional<Type> type = foldTypeSpecifiers(start, named, base, signedness, seenShort, seenComplex, longCount);
        if (type.isEmpty() && storage.stream().noneMatch(s -> s.text.equals("auto"))) {
            if (!seenTypeSpecifier && cur.peek() == start) {
                throw cur.error("expected declaration specifiers");
            }
            throw new ParseException("expected a type specifier", start);
        }
        final Quals fQuals = quals;
        type = type.map(t -> t.withQuals(t.quals().plus(fQuals)));
        return new Specifiers(start, storage, functionSpecs, alignas, type);
    }

    // Folds the collected type-specifier keywords into one type per the
    // multiset table of 6.7.3.1p2. Empty when no type specifier at all was
    // given (legal only with `auto`).
    private Optional<Type> foldTypeSpecifiers(Token start, Optional<Type> named, Optional<Token> base,
                                              Optional<Token> signedness, boolean seenShort,
                                              boolean seenComplex, int longCount) {
        boolean modifiers = signedness.isPresent() || seenShort || longCount > 0 || seenComplex;
        boolean unsigned = signedness.map(s -> s.text.equals("unsigned")).orElse(false);

        if (named.isPresent()) {
            if (named.get() instanceof Type.BitInt bi && signedness.isPresent() && !seenShort
                    && longCount == 0 && !seenComplex) {
                return Optional.of(new Type.BitInt(bi.token(), unsigned, bi.width(), Quals.NONE));
            }
            if (modifiers || base.isPresent()) throw new ParseException("invalid type specifier combination", start);
            return named;
        }
        if (base.isEmpty() && !modifiers) return Optional.empty();

        String b = base.map(t -> t.text).orElse("int");
        boolean complex = seenComplex;
        Type.Kind kind;
        switch (b) {
            case "char" -> {
                if (seenShort || longCount > 0 || complex) throw bad(start, b);
                kind = signedness.isEmpty() ? Type.Kind.CHAR : unsigned ? Type.Kind.UCHAR : Type.Kind.SCHAR;
            }
            case "int" -> {
                if (complex) throw bad(start, b);
                if (seenShort) {
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
                if (signedness.isPresent() || seenShort || longCount > 0) throw bad(start, b);
                kind = Type.Kind.FLOAT;
            }
            case "double" -> {
                if (signedness.isPresent() || seenShort || longCount > 1) throw bad(start, b);
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
        return Optional.of(new Type.Basic(start, kind, complex, Quals.NONE));
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
            result = new Specifiers.Alignas(kw, Optional.of(parseTypeName()), Optional.empty());
        } else {
            result = new Specifiers.Alignas(kw, Optional.empty(), Optional.of(parseConditionalExpression()));
        }
        cur.expect(")");
        return result;
    }

    // struct-or-union-specifier (6.7.3.2).
    private Type parseStructOrUnionSpecifier() {
        Token kw = cur.next();
        parseAttributeSpecifierSequence();
        Optional<Token> tag = cur.atIdentifier() ? Optional.of(cur.next()) : Optional.empty();
        Optional<List<Type.MemberDecl>> members = Optional.empty();
        if (cur.accept("{")) {
            var list = new ArrayList<Type.MemberDecl>();
            while (!cur.accept("}")) {
                parseMemberDeclaration(list);
            }
            members = Optional.of(list);
            parseAttributeSpecifierSequence();
        } else if (tag.isEmpty()) {
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
        Type base = specs.type().orElseThrow(); // no storage classes, so no `auto`
        if (cur.accept(";")) {
            out.add(new Type.Member(attrs, base, Optional.empty(), Optional.empty()));
            return;
        }
        do {
            Optional<Token> name = Optional.empty();
            Type type = base;
            if (!cur.at(":")) {
                var declarator = parseDeclarator(DeclaratorKind.NAMED);
                name = declarator.name();
                type = declarator.build().apply(type);
            }
            Optional<Expr> width = cur.accept(":") ? Optional.of(parseConditionalExpression()) : Optional.empty();
            out.add(new Type.Member(attrs, type, name, width));
        } while (cur.accept(","));
        cur.expect(";");
    }

    // enum-specifier (6.7.3.3), including the C23 enum-type-specifier.
    private Type parseEnumSpecifier() {
        Token kw = cur.expect("enum");
        parseAttributeSpecifierSequence();
        Optional<Token> tag = cur.atIdentifier() ? Optional.of(cur.next()) : Optional.empty();
        Optional<Type> underlying = Optional.empty();
        if (cur.accept(":")) {
            underlying = parseDeclarationSpecifiers(false).type();
        }
        Optional<List<Type.Enumerator>> enumerators = Optional.empty();
        if (cur.accept("{")) {
            var list = new ArrayList<Type.Enumerator>();
            while (!cur.at("}")) {
                Token name = cur.expectIdentifier();
                var attrs = parseAttributeSpecifierSequence();
                Optional<Expr> value = cur.accept("=") ? Optional.of(parseConditionalExpression()) : Optional.empty();
                // Enumeration constants are ordinary identifiers in the
                // enclosing scope (6.2.1), so they hide typedef names.
                scopes.declareOrdinary(name.text);
                list.add(new Type.Enumerator(name, attrs, value));
                if (!cur.accept(",")) break;
            }
            cur.expect("}");
            enumerators = Optional.of(list);
        } else if (tag.isEmpty()) {
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
            result = new Type.Typeof(kw, Optional.empty(), Optional.of(parseTypeName()), Quals.NONE);
        } else {
            result = new Type.Typeof(kw, Optional.of(parseExpression()), Optional.empty(), Quals.NONE);
        }
        cur.expect(")");
        return result;
    }

    /** type-name (6.7.8): specifier-qualifier-list abstract-declaratoropt. */
    private Type parseTypeName() {
        var specs = parseDeclarationSpecifiers(false);
        Type base = specs.type().orElseThrow(); // no storage classes, so no `auto`
        return parseDeclarator(DeclaratorKind.ABSTRACT).build().apply(base);
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
    private record Declarator(Optional<Token> name, UnaryOperator<Type> build, List<Attribute> attributes) {
    }

    private Declarator parseDeclarator(DeclaratorKind kind) {
        while (atGnuAttribute()) {
            parseAttributeSpecifierSequence();
        }
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
        Optional<Token> name = Optional.empty();
        UnaryOperator<Type> build = t -> t;
        List<Attribute> attrs = List.of();

        if (cur.atIdentifier() && kind != DeclaratorKind.ABSTRACT) {
            name = Optional.of(cur.next());
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
                if (!params.identifiers().isEmpty()) {
                    identifierLists.put(paren, params.identifiers());
                }
                UnaryOperator<Type> prev = build;
                build = t -> prev.apply(new Type.Function(paren, t, params.parameters(), params.variadic(),
                        params.prototype(), Quals.NONE));
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
        if (t.type == TokenType.IDENTIFIER && t.text.equals("__attribute__")) {
            return true;
        }
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
        Optional<Expr> size = Optional.empty();
        if (cur.at("*") && cur.at(1, "]")) {
            cur.next();
            star = true;
        } else if (!cur.at("]")) {
            size = Optional.of(parseAssignmentExpression());
        } else if (isStatic) {
            throw cur.error("'static' array parameter requires a size");
        }
        cur.expect("]");
        final boolean fStatic = isStatic, fStar = star;
        final Optional<Expr> fSize = size;
        return t -> new Type.Array(bracket, t, fSize, fStar, fStatic, quals);
    }

    private Quals parseTypeQualifierList() {
        Quals quals = Quals.NONE;
        while (cur.peek().type == TokenType.KEYWORD && TYPE_QUALIFIERS.contains(cur.peek().text)) {
            quals = quals.plus(cur.next().text);
        }
        return quals;
    }

    private record ParameterTypeList(List<Type.Parameter> parameters, boolean variadic, boolean prototype,
                                     List<Token> identifiers) {
    }

    // parameter-type-list (6.7.7.1), '(' already consumed. Parameter names
    // live in a function prototype scope that ends at the ')' (6.2.1p4).
    // An old-style definition (C17 6.9.1): the declarations between the
    // identifier list and the body give the parameters their types, in
    // the list's order; one not declared is an int.
    private Type.Function oldStyleParameters(Type.Function fn, List<Token> identifiers) {
        var declared = new LinkedHashMap<String, Type>();
        while (!cur.at("{")) {
            if (cur.atEof()) {
                throw cur.error("expected the parameter declarations and '{' of an old-style definition");
            }
            if (!(parseDeclaration() instanceof Decl.Declaration d) || d.specifiers().has("typedef")) {
                throw cur.error("expected a parameter declaration");
            }
            for (var id : d.declarators()) {
                String parameter = id.name().text;
                if (identifiers.stream().noneMatch(t -> t.text.equals(parameter))) {
                    throw cur.error("'" + parameter + "' is not a parameter of the definition");
                }
                if (declared.containsKey(parameter)) {
                    throw cur.error("parameter '" + parameter + "' declared twice");
                }
                if (id.initializer().isPresent()) {
                    throw cur.error("a parameter cannot have an initializer");
                }
                declared.put(parameter, id.type().orElseThrow(() -> cur.error("a parameter needs a type")));
            }
        }
        var params = new ArrayList<Type.Parameter>();
        for (Token id : identifiers) {
            Type type = declared.getOrDefault(id.text, new Type.Basic(id, Type.Kind.INT, false, Quals.NONE));
            params.add(new Type.Parameter(List.of(), List.of(), type, Optional.of(id)));
        }
        return new Type.Function(fn.paren(), fn.returnType(), params, false, false, fn.quals());
    }

    private ParameterTypeList parseParameterTypeList() {
        var params = new ArrayList<Type.Parameter>();
        boolean variadic = false;
        if (cur.accept(")")) {
            // `()`: a prototype with no parameters in C23, none at all in C17.
            return new ParameterTypeList(params, false, std == Std.C23, List.of());
        }
        if (std == Std.C17 && atIdentifierList()) {
            return new ParameterTypeList(params, false, false, parseIdentifierList());
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
        if (params.size() == 1 && !variadic && params.get(0).name().isEmpty()
                && params.get(0).type() instanceof Type.Basic b
                && b.kind() == Type.Kind.VOID && b.quals().isEmpty()) {
            params.clear();
        }
        return new ParameterTypeList(params, variadic, true, List.of());
    }

    // identifier-list (C17 6.7.6.3): identifiers that are not type names,
    // separated by commas, up to ')'.
    private boolean atIdentifierList() {
        Token t = cur.peek();
        return t.type == TokenType.IDENTIFIER && !scopes.isTypeName(t.text) && (cur.at(1, ",") || cur.at(1, ")"));
    }

    private List<Token> parseIdentifierList() {
        var names = new ArrayList<Token>();
        do {
            Token name = cur.expectIdentifier();
            if (names.stream().anyMatch(n -> n.text.equals(name.text))) {
                throw cur.error("'" + name.text + "' appears twice in the identifier list");
            }
            names.add(name);
        } while (cur.accept(","));
        cur.expect(")");
        return names;
    }

    private Type.Parameter parseParameterDeclaration() {
        var attrs = parseAttributeSpecifierSequence();
        var specs = parseDeclarationSpecifiers(true);
        Type base = specs.type().orElseThrow(() -> cur.error("parameter declaration requires a type"));
        var declarator = parseDeclarator(DeclaratorKind.PARAMETER);
        declarator.name().ifPresent(n -> scopes.declareOrdinary(n.text));
        return new Type.Parameter(attrs, specs.storageClasses(), declarator.build().apply(base), declarator.name());
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
                    Optional<Expr> last = Optional.empty();
                    if (cur.accept("...")) {
                        last = Optional.of(parseConditionalExpression());
                    }
                    cur.expect("]");
                    designators.add(new Initializer.ArrayDesignator(bracket, index, last));
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
            if (atGnuAttribute()) {
                skipGnuAttribute();
                continue;
            }
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

    private void skipGnuAttribute() {
        cur.next();
        cur.expect("(");
        int depth = 1;
        while (depth > 0) {
            if (cur.atEof()) {
                throw cur.error("unterminated __attribute__");
            }
            Token t = cur.next();
            if (t.text.equals("(")) {
                depth++;
            } else if (t.text.equals(")")) {
                depth--;
            }
        }
    }

    // attribute: attribute-token attribute-argument-clauseopt. Attribute
    // names are lexically identifiers, but keywords are accepted too since
    // vendor prefixes commonly use them (gnu::const).
    private Attribute parseAttribute() {
        Token name = parseAttributeName();
        Optional<Token> prefix = Optional.empty();
        if (cur.accept("::")) {
            prefix = Optional.of(name);
            name = parseAttributeName();
        }
        Optional<List<Token>> args = cur.at("(") ? Optional.of(parseBalancedTokenSequence()) : Optional.empty();
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

    // ---- A.3.3 statements (6.8) -----------------------------------------------

    /** Parses a single statement that must consume all input; for tests and tools. */
    public Stmt parseStandaloneStatement() {
        Stmt s = parseStatement();
        if (!cur.atEof()) throw cur.error("unexpected token after statement");
        return s;
    }

    /** statement: labeled-statement | unlabeled-statement. */
    public Stmt parseStatement() {
        var attrs = parseAttributeSpecifierSequence();
        if (atLabel()) {
            Stmt.Label label = parseLabel();
            return new Stmt.Labeled(label, Optional.of(parseStatement()));
        }
        return parseUnlabeledStatement(attrs);
    }

    private boolean atLabel() {
        return (cur.atIdentifier() && cur.at(1, ":")) || cur.at("case") || cur.at("default");
    }

    // label (6.8.2), attributes already consumed.
    private Stmt.Label parseLabel() {
        if (cur.at("default")) {
            Token kw = cur.next();
            cur.expect(":");
            return new Stmt.DefaultLabel(kw);
        }
        if (cur.at("case")) {
            Token kw = cur.next();
            Expr low = parseConditionalExpression();
            Optional<Expr> high = cur.accept("...") ? Optional.of(parseConditionalExpression()) : Optional.empty();
            cur.expect(":");
            return new Stmt.CaseLabel(kw, low, high);
        }
        Token name = cur.expectIdentifier();
        cur.expect(":");
        return new Stmt.NameLabel(name);
    }

    /** compound-statement (6.8.3); newScope is false for a function body whose scope holds the parameters. */
    private Stmt.Compound parseCompoundStatement(boolean newScope) {
        Token brace = cur.expect("{");
        if (newScope) scopes.push();
        var items = new ArrayList<BlockItem>();
        while (!cur.at("}")) {
            if (cur.atEof()) throw cur.error("expected '}'");
            items.add(parseBlockItem());
        }
        cur.next();
        if (newScope) scopes.pop();
        return new Stmt.Compound(brace, items);
    }

    // block-item: declaration | unlabeled-statement | label. All three may
    // start with attributes, so those are parsed first and the decision is
    // made on what follows.
    private BlockItem parseBlockItem() {
        var attrs = parseAttributeSpecifierSequence();
        if (!attrs.isEmpty() && cur.accept(";")) {
            return new Decl.AttributeDeclaration(attrs);
        }
        if (atLabel()) {
            return new Stmt.Labeled(parseLabel(), Optional.empty());
        }
        if (atDeclaration()) {
            return parseDeclaration(attrs);
        }
        return parseUnlabeledStatement(attrs);
    }

    // unlabeled-statement: expression-statement | primary-block | jump-statement.
    private Stmt parseUnlabeledStatement(List<Attribute> attrs) {
        Token t = cur.peek();
        if (cur.at("{")) return parseCompoundStatement(true);
        if (t.type == TokenType.KEYWORD) {
            switch (t.text) {
                case "if" -> {
                    cur.next();
                    cur.expect("(");
                    scopes.push();
                    Stmt.Header header = parseSelectionHeader();
                    cur.expect(")");
                    Stmt thenBranch = parseStatement();
                    Optional<Stmt> elseBranch = cur.accept("else") ? Optional.of(parseStatement()) : Optional.empty();
                    scopes.pop();
                    return new Stmt.If(t, header, thenBranch, elseBranch);
                }
                case "switch" -> {
                    cur.next();
                    cur.expect("(");
                    scopes.push();
                    Stmt.Header header = parseSelectionHeader();
                    cur.expect(")");
                    Stmt body = parseStatement();
                    scopes.pop();
                    return new Stmt.Switch(t, header, body);
                }
                case "while" -> {
                    cur.next();
                    cur.expect("(");
                    Expr cond = parseExpression();
                    cur.expect(")");
                    return new Stmt.While(t, cond, parseStatement());
                }
                case "do" -> {
                    cur.next();
                    Stmt body = parseStatement();
                    cur.expect("while");
                    cur.expect("(");
                    Expr cond = parseExpression();
                    cur.expect(")");
                    cur.expect(";");
                    return new Stmt.DoWhile(t, body, cond);
                }
                case "for" -> {
                    return parseForStatement();
                }
                case "goto" -> {
                    cur.next();
                    Token label = cur.expectIdentifier();
                    cur.expect(";");
                    return new Stmt.Goto(t, label);
                }
                case "continue", "break" -> {
                    cur.next();
                    Optional<Token> label = cur.atIdentifier() ? Optional.of(cur.next()) : Optional.empty();
                    cur.expect(";");
                    return t.text.equals("break") ? new Stmt.Break(t, label) : new Stmt.Continue(t, label);
                }
                case "return" -> {
                    cur.next();
                    Optional<Expr> value = cur.at(";") ? Optional.empty() : Optional.of(parseExpression());
                    cur.expect(";");
                    return new Stmt.Return(t, value);
                }
                default -> { }
            }
        }
        // expression-statement
        if (cur.accept(";")) {
            return new Stmt.ExprStmt(t, Optional.empty());
        }
        Expr expr = parseExpression();
        if (!(script && cur.atEof())) {
            cur.expect(";");
        }
        return new Stmt.ExprStmt(t, Optional.of(expr));
    }

    // selection-header (6.8.5.1):
    //   expression | declaration expression | simple-declaration
    // A declaration consumes its ';' and is followed by the controlling
    // expression; a simple-declaration (one declarator with initializer,
    // no ';') is itself the controlling value.
    private Stmt.Header parseSelectionHeader() {
        var attrs = parseAttributeSpecifierSequence();
        if (!atDeclaration()) {
            if (!attrs.isEmpty()) throw cur.error("attributes are not allowed on a selection expression");
            return new Stmt.Header(Optional.empty(), Optional.of(parseExpression()));
        }
        if (cur.at("static_assert")) {
            throw cur.error("static_assert is not allowed in a selection header");
        }
        var specs = parseDeclarationSpecifiers(true);
        var declarators = parseInitDeclaratorList(specs, parseDeclarator(DeclaratorKind.NAMED));
        var decl = new Decl.Declaration(attrs, specs, declarators);
        if (cur.accept(";")) {
            return new Stmt.Header(Optional.of(decl), Optional.of(parseExpression()));
        }
        if (cur.at(")")) {
            if (declarators.size() != 1 || declarators.get(0).initializer().isEmpty()) {
                throw cur.error("a simple-declaration must declare exactly one initialized object");
            }
            return new Stmt.Header(Optional.of(decl), Optional.empty());
        }
        throw cur.error("expected ';' or ')' in selection header");
    }

    // for ( expressionopt ; expressionopt ; expressionopt ) secondary-block
    // for ( declaration expressionopt ; expressionopt ) secondary-block
    private Stmt parseForStatement() {
        Token kw = cur.expect("for");
        cur.expect("(");
        scopes.push();
        Optional<Decl.Declaration> initDecl = Optional.empty();
        Optional<Expr> initExpr = Optional.empty();
        var attrs = parseAttributeSpecifierSequence();
        if (atDeclaration()) {
            Decl d = parseDeclaration(attrs);
            if (!(d instanceof Decl.Declaration decl)) throw cur.error("expected a declaration in 'for' clause");
            initDecl = Optional.of(decl);
        } else {
            if (!cur.at(";")) initExpr = Optional.of(parseExpression());
            cur.expect(";");
        }
        Optional<Expr> cond = cur.at(";") ? Optional.empty() : Optional.of(parseExpression());
        cur.expect(";");
        Optional<Expr> step = cur.at(")") ? Optional.empty() : Optional.of(parseExpression());
        cur.expect(")");
        Stmt body = parseStatement();
        scopes.pop();
        return new Stmt.For(kw, initDecl, initExpr, cond, step, body);
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
            // 0: not a binary operator (every operator has precedence >= 1).
            int prec = t.type == TokenType.PUNCTUATOR ? BINARY_PRECEDENCE.getOrDefault(t.text, 0) : 0;
            if (prec < minPrec) return left;
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
        Optional<Expr.StringLiteral> message = Optional.empty();
        if (cur.accept(",")) {
            if (cur.peek().type != TokenType.STRING_LITERAL) throw cur.error("expected string literal");
            message = Optional.of(parseStringLiteral());
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
                if (t.text.equals("__func__") && currentFunction != null) {
                    // The predeclared name of the enclosing function (6.4.3.2), as a string literal.
                    Token literal = new Token(TokenType.STRING_LITERAL, "\"" + currentFunction + "\"", t.line, t.column);
                    literal.file = t.file;
                    return new Expr.StringLiteral(List.of(literal));
                }
                if (t.text.equals("__builtin_va_arg") && cur.at("(")) {
                    return parseVaArg(t);
                }
                if (t.text.equals("__builtin_va_start") && cur.at("(")) {
                    return parseVaStart(t);
                }
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
                    if (cur.at("{")) {
                        // ({ ... }), GNU's statement expression.
                        Stmt.Compound body = parseCompoundStatement(true);
                        cur.expect(")");
                        return new Expr.StmtExpr(t, body);
                    }
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

    // __builtin_va_arg(ap, type-name): the builtin behind va_arg, a
    // primary expression of its own since it takes a type.
    private Expr parseVaArg(Token name) {
        cur.expect("(");
        Expr ap = parseAssignmentExpression();
        cur.expect(",");
        Type type = parseTypeName();
        cur.expect(")");
        return new Expr.VaArg(name, ap, type);
    }

    // __builtin_va_start(ap, last): the builtin behind va_start.
    private Expr parseVaStart(Token name) {
        cur.expect("(");
        Expr ap = parseAssignmentExpression();
        cur.expect(",");
        Expr last = parseAssignmentExpression();
        cur.expect(")");
        return new Expr.VaStart(name, ap, last);
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
        Optional<Expr> controllingExpr = Optional.empty();
        Optional<Type> controllingType = Optional.empty();
        if (startsTypeName(cur.peek())) {
            controllingType = Optional.of(parseTypeName());
        } else {
            controllingExpr = Optional.of(parseAssignmentExpression());
        }
        cur.expect(",");
        var associations = new ArrayList<Expr.Generic.Association>();
        do {
            Optional<Type> type = cur.accept("default") ? Optional.empty() : Optional.of(parseTypeName());
            cur.expect(":");
            associations.add(new Expr.Generic.Association(type, parseAssignmentExpression()));
        } while (cur.accept(","));
        cur.expect(")");
        return new Expr.Generic(kw, controllingExpr, controllingType, associations);
    }
}

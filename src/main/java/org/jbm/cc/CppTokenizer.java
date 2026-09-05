package org.jbm.cc;

import org.jbm.cc.cpp.CppToken;

import java.util.*;

/**
 * Tokenizer for raw C++ source, prior to any preprocessing (no line
 * splicing, no macro expansion, no directive handling). The C preprocessor
 * itself lives in {@link org.jbm.cc.cpp}.
 */
public class CppTokenizer {

    public static class TokenSet {

        public TokenSet dup() {
            return new TokenSet(tokens);
        }

        public TokenSet concat(CppToken other) {
            tokens.add(other);
            return this;
        }

        public TokenSet concat(TokenSet other) {
            var result = new TokenSet(tokens);
            result.tokens.addAll(other.tokens);
            return result;
        }

        public static TokenSet empty() {
            return new TokenSet(Collections.emptyList());
        }

        public static TokenSet fromTokens(List<Token> tokens) {
            return new TokenSet(tokens.stream().map(CppToken::new).toList());
        }

        public static TokenSet from(CppToken token) {
            var list = new ArrayList<CppToken>();
            list.add(token.clone());
            return new TokenSet(list);
        }

        public TokenSet (List<CppToken> tokens) {
            this.tokens = new ArrayList<>(
                tokens.stream().map(CppToken::clone).toList()
            );
        }

        public TokenSet (CppToken first, TokenSet rest) {
            this.tokens = new ArrayList<>();
            this.tokens.add(first);
            this.tokens.addAll(rest.tokens.stream().map(CppToken::clone).toList());
        }

        public final List<CppToken> tokens;

        // Macro name -> its defining OBJECT_MACRO/CALL_MACRO token, built up
        // while pre-tokenizing so the scanner can resolve usages without
        // re-scanning for `#define`s.
        public Map<String, Token> macros = Map.of();
    }

    public enum TokenType {
        IDENTIFIER,
        KEYWORD,
        OBJECT_MACRO,
        CALL_MACRO,
        INTEGER_LITERAL,
        FLOATING_LITERAL,
        CHARACTER_LITERAL,
        STRING_LITERAL,
        PUNCTUATOR,
        STRINGIZE,
        PASTE,
        UNKNOWN,
        EOF
    }

    public static final class Token {
        public final TokenType type;
        public final String text;
        public final int line;
        public final int column;

        // Populated only for OBJECT_MACRO/CALL_MACRO tokens: the
        // replacement-list tokens found on the rest of the
        // `#define NAME ...` / `#define NAME(params) ...` line.
        public List<Token> expansion = List.of();

        // Populated only for CALL_MACRO tokens: the formal parameter names
        // (and "..." for a variadic trailer) declared in `(params)`.
        public List<Token> params = List.of();

        // Populated only for CALL_MACRO tokens that are invocations (i.e.
        // `NAME(...)` occurrences in ordinary code, not the `#define`
        // itself): one token list per comma-separated actual argument
        // found in the call's `(...)`.
        public List<List<Token>> arguments = List.of();

        public Token(TokenType type, String text, int line, int column) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.column = column;
        }

        @Override
        public String toString() {
            return type + "(" + text + ") @" + line + ":" + column;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Token token = (Token) o;
            return line == token.line && column == token.column && type == token.type && Objects.equals(text, token.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, text, line, column);
        }
    }

    public static final class LexException extends RuntimeException {
        public LexException(String message, int line, int column) {
            super(message + " at " + line + ":" + column);
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor",
            "bool", "break", "case", "catch", "char", "char8_t", "char16_t", "char32_t",
            "class", "compl", "concept", "const", "consteval", "constexpr", "constinit",
            "const_cast", "continue", "co_await", "co_return", "co_yield", "decltype",
            "default", "delete", "do", "double", "dynamic_cast", "else", "enum", "explicit",
            "export", "extern", "false", "float", "for", "friend", "goto", "if", "inline",
            "int", "long", "mutable", "namespace", "new", "noexcept", "not", "not_eq",
            "nullptr", "operator", "or", "or_eq", "private", "protected", "public",
            "register", "reinterpret_cast", "requires", "return", "short", "signed",
            "sizeof", "static", "static_assert", "static_cast", "struct", "switch",
            "template", "this", "thread_local", "throw", "true", "try", "typedef",
            "typeid", "typename", "union", "unsigned", "using", "virtual", "void",
            "volatile", "wchar_t", "while", "xor", "xor_eq"
    );

    public static Set<String> keywords() {
        return KEYWORDS;
    }

    // Ordered longest-first so matching is greedy.
    private static final String[] PUNCTUATORS = {
            "<<=", ">>=", "...", "->*", "<=>",
            "::", "->", ".*", "==", "!=", "<=", ">=", "&&", "||", "++", "--",
            "+=", "-=", "*=", "/=", "%=", "^=", "&=", "|=", "<<", ">>", "##",
            "{", "}", "[", "]", "(", ")", ";", ":", "?", ".", "~", "!",
            "+", "-", "*", "/", "%", "^", "&", "|", "=", ",", "<", ">", "#"
    };

    static {
        Arrays.sort(PUNCTUATORS, (a, b) -> b.length() - a.length());
    }

    private static final String[] STRING_PREFIXES = {"u8R", "uR", "UR", "LR", "u8", "u", "U", "L", "R"};

    // Tracks progress through a `# define NAME` prefix so the identifier
    // immediately after `define` can be classified as an OBJECT_MACRO or
    // CALL_MACRO name (the latter iff `(` follows it with no whitespace),
    // and through a `# undef NAME` prefix so that name can be removed from
    // the macro table.
    private enum DirectiveState { NONE, HASH, DEFINE, UNDEF }

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;
    private boolean atLineStart = true;
    private DirectiveState directiveState = DirectiveState.NONE;
    private final Map<String, Token> macroTable = new LinkedHashMap<>();

    public CppTokenizer(String source) {
        this.src = source;
    }

    // Used to lex a lookahead slice (the rest of a #define line) without
    // disturbing this tokenizer's own position; the slice is never at the
    // start of a physical line.
    private CppTokenizer(String source, int startLine, int startCol) {
        this.src = source;
        this.line = startLine;
        this.col = startCol;
        this.atLineStart = false;
    }

    public static List<Token> tokenize(String source) {
        return new CppTokenizer(source).scan();
    }

    public static TokenSet tokenSet(String source) {
        CppTokenizer tokenizer = new CppTokenizer(source);
        TokenSet set = TokenSet.fromTokens(tokenizer.scan());
        set.macros = tokenizer.macroTable();
        return set;
    }

    // Macro name -> its defining OBJECT_MACRO/CALL_MACRO token, as seen so
    // far by this tokenizer. A later #define for the same name overwrites
    // the earlier entry, matching last-definition-wins semantics.
    public Map<String, Token> macroTable() {
        return Collections.unmodifiableMap(macroTable);
    }

    public List<Token> scan() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        while ((t = next()).type != TokenType.EOF) {
            tokens.add(t);
        }
        tokens.add(t);
        return tokens;
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char peek(int offset) {
        int p = pos + offset;
        return p < src.length() ? src.charAt(p) : '\0';
    }

    private char advance() {
        char c = src.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
            atLineStart = true;
            directiveState = DirectiveState.NONE;
        } else {
            col++;
        }
        return c;
    }

    private boolean match(String literal) {
        if (!src.startsWith(literal, pos)) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            advance();
        }
        return true;
    }

    private Token next() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) {
            return new Token(TokenType.EOF, "", line, col);
        }

        int startLine = line;
        int startCol = col;
        char c = peek();
        boolean isLineStart = atLineStart;
        atLineStart = false;

        if (c == '#' && isLineStart && peek(1) != '#') {
            advance();
            directiveState = DirectiveState.HASH;
            return new Token(TokenType.PUNCTUATOR, "#", startLine, startCol);
        }

        String prefix = matchLiteralPrefix();
        if (prefix != null) {
            char quote = peek(prefix.length());
            if (quote == '"') {
                int tokenStart = pos;
                for (int i = 0; i < prefix.length(); i++) advance();
                if (prefix.endsWith("R")) {
                    return scanRawString(tokenStart, startLine, startCol);
                }
                advance(); // consume opening quote
                return scanString(tokenStart, startLine, startCol);
            } else if (quote == '\'' && !prefix.contains("R") && !prefix.equals("u8")) {
                int tokenStart = pos;
                for (int i = 0; i < prefix.length(); i++) advance();
                advance(); // consume opening quote
                return scanChar(tokenStart, startLine, startCol);
            }
        }

        if (isIdentifierStart(c)) {
            return classifyDirectiveIdentifier(scanIdentifier(startLine, startCol));
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return scanNumber(startLine, startCol);
        }
        if (c == '"') {
            int tokenStart = pos;
            advance();
            return scanString(tokenStart, startLine, startCol);
        }
        if (c == '\'') {
            int tokenStart = pos;
            advance();
            return scanChar(tokenStart, startLine, startCol);
        }

        for (String p : PUNCTUATORS) {
            if (match(p)) {
                // Reached only when '#'/'##' aren't the line-start directive
                // marker, i.e. when they're the macro-body stringize/paste
                // operators.
                TokenType type = switch (p) {
                    case "##" -> TokenType.PASTE;
                    case "#" -> TokenType.STRINGIZE;
                    default -> TokenType.PUNCTUATOR;
                };
                return new Token(type, p, startLine, startCol);
            }
        }

        advance();
        return new Token(TokenType.UNKNOWN, String.valueOf(c), startLine, startCol);
    }

    private String matchLiteralPrefix() {
        for (String p : STRING_PREFIXES) {
            if (src.startsWith(p, pos)) {
                return p;
            }
        }
        return null;
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                int startLine = line, startCol = col;
                advance();
                advance();
                boolean closed = false;
                while (pos < src.length()) {
                    if (peek() == '*' && peek(1) == '/') {
                        advance();
                        advance();
                        closed = true;
                        break;
                    }
                    advance();
                }
                if (!closed) {
                    throw new LexException("Unterminated block comment", startLine, startCol);
                }
            } else {
                break;
            }
        }
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private Token scanIdentifier(int startLine, int startCol) {
        int start = pos;
        while (pos < src.length() && isIdentifierPart(peek())) {
            advance();
        }
        String text = src.substring(start, pos);
        TokenType type = KEYWORDS.contains(text) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
        return new Token(type, text, startLine, startCol);
    }

    private Token classifyDirectiveIdentifier(Token idToken) {
        switch (directiveState) {
            case HASH:
                if ("define".equals(idToken.text)) {
                    directiveState = DirectiveState.DEFINE;
                } else if ("undef".equals(idToken.text)) {
                    directiveState = DirectiveState.UNDEF;
                } else {
                    directiveState = DirectiveState.NONE;
                }
                return idToken;
            case UNDEF:
                directiveState = DirectiveState.NONE;
                macroTable.remove(idToken.text);
                return idToken;
            case DEFINE:
                directiveState = DirectiveState.NONE;
                if (peek() == '(') {
                    Token callMacro = new Token(TokenType.CALL_MACRO, idToken.text, idToken.line, idToken.column);
                    int closeParen = findMatchingParen(pos);
                    if (closeParen >= 0) {
                        callMacro.params = scanParamListTokens(pos + 1, closeParen);
                        callMacro.expansion = scanExpansionTokens(closeParen + 1);
                    } else {
                        callMacro.expansion = scanExpansionTokens(pos);
                    }
                    macroTable.put(callMacro.text, callMacro);
                    return callMacro;
                }
                Token objectMacro = new Token(TokenType.OBJECT_MACRO, idToken.text, idToken.line, idToken.column);
                objectMacro.expansion = scanExpansionTokens(pos);
                macroTable.put(objectMacro.text, objectMacro);
                return objectMacro;
            default:
                return classifyCallSite(idToken);
        }
    }

    // Detects `NAME(...)` in ordinary code where NAME is a previously
    // #define'd function-like macro, and captures the actual argument
    // token lists onto the occurrence's own token (mirroring how the
    // `#define` site carries its formal `params`). Unlike a `#define`'s
    // immediately-adjacent '(' (which distinguishes function-like from
    // object-like macros), whitespace/comments before the '(' are allowed
    // here, and the argument list may span multiple lines.
    private Token classifyCallSite(Token idToken) {
        Token definition = macroTable.get(idToken.text);
        if (definition == null) {
            return idToken;
        }
        if (definition.type == TokenType.OBJECT_MACRO) {
            // Resolve object-macro occurrences at scan time too, so each
            // use carries the definition in force at its position in the
            // source (matters across #undef / redefinition).
            Token occurrence = new Token(TokenType.OBJECT_MACRO, idToken.text, idToken.line, idToken.column);
            occurrence.expansion = definition.expansion;
            return occurrence;
        }
        if (definition.type != TokenType.CALL_MACRO) {
            return idToken;
        }
        skipWhitespaceAndComments();
        if (peek() != '(') {
            return idToken;
        }
        int openParen = pos;
        int closeParen = findMatchingParenAcrossLines(openParen);
        if (closeParen < 0) {
            return idToken;
        }
        Token call = new Token(TokenType.CALL_MACRO, idToken.text, idToken.line, idToken.column);
        call.arguments = scanArgumentListTokens(openParen + 1, closeParen);
        return call;
    }

    // Returns the index of the ')' matching the '(' at `from`, or -1 if the
    // parameter list isn't closed on this line.
    private int findMatchingParen(int from) {
        int depth = 0;
        int i = from;
        while (i < src.length() && src.charAt(i) != '\n') {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    // Like findMatchingParen, but not bounded to the current line: a macro
    // invocation's argument list (unlike a #define's parameter list) may
    // span multiple lines.
    private int findMatchingParenAcrossLines(int from) {
        int depth = 0;
        int i = from;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    // Lexes the argument-list text between `from` (just after '(') and `to`
    // (the matching ')') into one token list per comma, treating commas
    // nested inside a further '(' ... ')' as part of the enclosing argument
    // rather than a separator.
    private List<List<Token>> scanArgumentListTokens(int from, int to) {
        String inner = src.substring(from, to);
        if (inner.isBlank()) {
            return List.of();
        }

        List<Token> raw = new CppTokenizer(inner, line, col + (from - pos)).scan();
        List<List<Token>> arguments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token t : raw) {
            if (t.type == TokenType.EOF) {
                continue;
            }
            if (t.type == TokenType.PUNCTUATOR && t.text.equals("(")) {
                depth++;
            } else if (t.type == TokenType.PUNCTUATOR && t.text.equals(")")) {
                depth--;
            }
            if (depth == 0 && t.type == TokenType.PUNCTUATOR && t.text.equals(",")) {
                arguments.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        arguments.add(current);
        return arguments;
    }

    // Lexes the parameter list text between `from` (just after '(') and
    // `to` (the matching ')'), keeping only the formal parameter names and
    // a variadic "...", i.e. dropping the separating commas.
    private List<Token> scanParamListTokens(int from, int to) {
        String inner = src.substring(from, to);
        List<Token> raw = new CppTokenizer(inner, line, col + (from - pos)).scan();
        List<Token> params = new ArrayList<>();
        for (Token t : raw) {
            if (t.type == TokenType.IDENTIFIER || (t.type == TokenType.PUNCTUATOR && t.text.equals("..."))) {
                params.add(t);
            }
        }
        return params;
    }

    // Lexes the remainder of the current line starting at `from` (not
    // consuming it from `this`) to capture a macro's replacement-list
    // tokens.
    private List<Token> scanExpansionTokens(int from) {
        int end = from;
        while (end < src.length() && src.charAt(end) != '\n') {
            end++;
        }
        String rest = src.substring(from, end);
        List<Token> raw = new CppTokenizer(rest, line, col + (from - pos)).scan();
        List<Token> expansion = new ArrayList<>();
        for (Token t : raw) {
            if (t.type != TokenType.EOF) {
                expansion.add(t);
            }
        }
        return expansion;
    }

    private Token scanNumber(int startLine, int startCol) {
        int start = pos;
        boolean isFloat = false;

        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance();
            advance();
            while (isHexDigit(peek()) || peek() == '\'') advance();
            if (peek() == '.') {
                isFloat = true;
                advance();
                while (isHexDigit(peek()) || peek() == '\'') advance();
            }
            if (peek() == 'p' || peek() == 'P') {
                isFloat = true;
                advance();
                if (peek() == '+' || peek() == '-') advance();
                while (Character.isDigit(peek())) advance();
            }
        } else if (peek() == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            advance();
            advance();
            while (peek() == '0' || peek() == '1' || peek() == '\'') advance();
        } else {
            while (Character.isDigit(peek()) || peek() == '\'') advance();
            if (peek() == '.') {
                isFloat = true;
                advance();
                while (Character.isDigit(peek()) || peek() == '\'') advance();
            }
            if (peek() == 'e' || peek() == 'E') {
                isFloat = true;
                advance();
                if (peek() == '+' || peek() == '-') advance();
                while (Character.isDigit(peek())) advance();
            }
        }

        while (isSuffixLetter(peek())) {
            if (peek() == 'f' || peek() == 'F') isFloat = true;
            advance();
        }

        String text = src.substring(start, pos);
        return new Token(isFloat ? TokenType.FLOATING_LITERAL : TokenType.INTEGER_LITERAL, text, startLine, startCol);
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isSuffixLetter(char c) {
        return c == 'u' || c == 'U' || c == 'l' || c == 'L' || c == 'f' || c == 'F';
    }

    private Token scanString(int start, int startLine, int startCol) {
        while (true) {
            if (pos >= src.length() || peek() == '\n') {
                throw new LexException("Unterminated string literal", startLine, startCol);
            }
            char c = advance();
            if (c == '\\' && pos < src.length()) {
                advance();
            } else if (c == '"') {
                break;
            }
        }
        String text = src.substring(start, pos);
        return new Token(TokenType.STRING_LITERAL, text, startLine, startCol);
    }

    private Token scanChar(int start, int startLine, int startCol) {
        while (true) {
            if (pos >= src.length() || peek() == '\n') {
                throw new LexException("Unterminated character literal", startLine, startCol);
            }
            char c = advance();
            if (c == '\\' && pos < src.length()) {
                advance();
            } else if (c == '\'') {
                break;
            }
        }
        String text = src.substring(start, pos);
        return new Token(TokenType.CHARACTER_LITERAL, text, startLine, startCol);
    }

    private Token scanRawString(int start, int startLine, int startCol) {
        advance(); // consume opening quote
        int delimStart = pos;
        while (pos < src.length() && peek() != '(') {
            advance();
        }
        if (pos >= src.length()) {
            throw new LexException("Unterminated raw string literal", startLine, startCol);
        }
        String delim = src.substring(delimStart, pos);
        String terminator = ")" + delim + "\"";
        advance(); // consume '('
        int closeIdx = src.indexOf(terminator, pos);
        if (closeIdx < 0) {
            throw new LexException("Unterminated raw string literal", startLine, startCol);
        }
        while (pos < closeIdx + terminator.length()) {
            advance();
        }
        String text = src.substring(start, pos);
        return new Token(TokenType.STRING_LITERAL, text, startLine, startCol);
    }
}

package org.jbm.mycc.cc.cpp;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Tokenizer for C source: translation phases 2 and 3 (line splicing,
 * then decomposition into preprocessing tokens), plus the recognition of
 * {@code #define} / {@code #undef} lines that the expander needs. Macro
 * expansion itself is {@link Scanner}'s job.
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
        public MacroTable macros = new MacroTable();
    }

    public enum TokenType {
        IDENTIFIER,
        KEYWORD,
        OBJECT_MACRO,
        CALL_MACRO,
        // Lexing (translation phases 3-6) produces pp-numbers; they only
        // become INTEGER_CONSTANT/FLOATING_CONSTANT tokens in phase 7
        // (TokenConversion), after macro expansion.
        PP_NUMBER,
        INTEGER_CONSTANT,
        FLOATING_CONSTANT,
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

        // The file the token was read from; "" for tokens the expander or
        // the tests make up.
        public String file = "";

        // Whether white space (or a comment) separated this token from the
        // previous one in the source. The expander keeps a per-occurrence
        // copy on CppToken, which is what the stringize operator reads.
        public boolean spaceBefore;
        // The macro table's version when the token was scanned: the
        // definitions in force for it.
        public int seq;

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

        // The line in the file, counting the physical lines a backslash-
        // newline joined; `line` counts logical lines, which is what the
        // directive stripping and the -E output go by.
        public int physicalLine;

        public Token(@NonNull TokenType type, @NonNull String text, int line, int column) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.column = column;
            this.physicalLine = line;
        }

        /** {@code file:line:column}, or {@code line:column} for a token without a file. */
        public String location() {
            if (file.isEmpty()) {
                return physicalLine + ":" + column;
            }
            return file + ":" + physicalLine + ":" + column;
        }

        @Override
        public String toString() {
            return type + "(" + text + ") @" + line + ":" + column;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Token token = (Token) o;
            boolean samePlace = line == token.line && column == token.column && file.equals(token.file);
            return samePlace && type == token.type && Objects.equals(text, token.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, text, line, column, file);
        }
    }

    public static final class LexException extends RuntimeException {
        public LexException(String message, String file, int line, int column) {
            super(message + " at " + location(file, line, column));
        }

        public LexException(String message, Token at) {
            super(message + " at " + at.location());
        }

        private static String location(String file, int line, int column) {
            if (file.isEmpty()) {
                return line + ":" + column;
            }
            return file + ":" + line + ":" + column;
        }
    }

    // The keywords of C2y (N3886 6.4.2). Note the old `_Bool`, `_Alignas`,
    // `_Static_assert` and `_Thread_local` spellings are no longer keywords.
    private static final Set<String> KEYWORDS = Set.of(
            "alignas", "alignof", "auto", "bool", "break", "case", "char", "const",
            "constexpr", "continue", "default", "do", "double", "else", "enum", "extern",
            "false", "float", "for", "goto", "if", "inline", "int", "long", "nullptr",
            "register", "restrict", "return", "short", "signed", "sizeof", "static",
            "static_assert", "struct", "switch", "thread_local", "true", "typedef",
            "typeof", "typeof_unqual", "union", "unsigned", "void", "volatile", "while",
            "_Atomic", "_BitInt", "_Complex", "_Countof", "_Decimal128", "_Decimal32",
            "_Decimal64", "_Generic", "_Noreturn"
    );

    public static Set<String> keywords() {
        return KEYWORDS;
    }

    // The punctuators of C2y (6.4.7), including the six digraphs. Ordered
    // longest-first so matching is greedy.
    private static final String[] PUNCTUATORS = {
            "%:%:",
            "<<=", ">>=", "...",
            "::", "->", "==", "!=", "<=", ">=", "&&", "||", "++", "--",
            "+=", "-=", "*=", "/=", "%=", "^=", "&=", "|=", "<<", ">>", "##",
            "<:", ":>", "<%", "%>", "%:",
            "{", "}", "[", "]", "(", ")", ";", ":", "?", ".", "~", "!",
            "+", "-", "*", "/", "%", "^", "&", "|", "=", ",", "<", ">", "#"
    };

    static {
        Arrays.sort(PUNCTUATORS, (a, b) -> b.length() - a.length());
    }

    // A digraph behaves exactly like its primary spelling (6.4.7p3), so it is
    // normalized here and the parser only ever sees the primary form.
    private static final Map<String, String> DIGRAPHS = Map.of(
            "<:", "[", ":>", "]", "<%", "{", "%>", "}", "%:", "#", "%:%:", "##");

    private static final String[] STRING_PREFIXES = {"u8R", "uR", "UR", "LR", "u8", "u", "U", "L", "R"};

    // Tracks progress through a `# define NAME` prefix so the identifier
    // immediately after `define` can be classified as an OBJECT_MACRO or
    // CALL_MACRO name (the latter iff `(` follows it with no whitespace),
    // and through a `# undef NAME` prefix so that name can be removed from
    // the macro table.
    private enum DirectiveState { NONE, HASH, DEFINE, UNDEF }

    // The source after translation phase 2 (5.1.1.2): every backslash
    // immediately followed by a new-line is deleted, splicing physical
    // lines into one logical line. Line numbers count logical lines, so a
    // `#define` continued over several physical lines is one line - which
    // is also how the expander tells where a directive ends.
    private final String src;
    private String file;   // changed only by #line
    private int pos;
    private int line = 1;
    private int col = 1;
    private boolean atLineStart = true;
    private DirectiveState directiveState = DirectiveState.NONE;

    // Definitions saved by #pragma push_macro, per name; null for "was not defined".
    private final Map<String, List<Token>> pushedMacros = new HashMap<>();
    private final MacroTable macroTable;

    // The conditional directives (6.10.1): each `#if`, `#ifdef` or
    // `#ifndef` opens a group that its `#endif` closes. A group is active
    // when its enclosing group is and the branch we are in was the first
    // one whose condition held; tokens are emitted, macros defined and
    // conditions evaluated only in active groups. Inactive groups are
    // skipped by line without lexing them, looking only for the
    // conditional directives that nest or close them.
    private static final Set<String> CONDITIONALS = Set.of(
            "if", "ifdef", "ifndef", "elif", "elifdef", "elifndef", "else", "endif");

    private static final class Group {
        final Token at;
        final boolean parentActive;
        boolean taken;
        boolean elseSeen;
        boolean active;

        Group(Token at, boolean parentActive) {
            this.at = at;
            this.parentActive = parentActive;
        }
    }

    private final ArrayDeque<Group> groups = new ArrayDeque<>();

    // `#include` (6.10.3): the header is lexed by a nested tokenizer that
    // shares the macro table, and its tokens are queued to be handed out
    // before this file's next token. Without a provider there are no
    // headers to include.
    private static final int MAX_INCLUDE_DEPTH = 200;
    private final @Nullable HeaderProvider headers;
    private final int depth;
    private final ArrayDeque<Token> pending = new ArrayDeque<>();

    public CppTokenizer(@NonNull String source) {
        this(source, null, "<source>");
    }

    public CppTokenizer(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file) {
        this(spliced(source), file, headers, new MacroTable(), 0);
    }

    private CppTokenizer(Spliced source, String file, @Nullable HeaderProvider headers, MacroTable macroTable, int depth) {
        this(source.text(), file, headers, macroTable, depth);
        continuations(source.continuations());
    }

    private CppTokenizer(String splicedSource, String file, @Nullable HeaderProvider headers,
                         MacroTable macroTable, int depth) {
        this.src = splicedSource;
        this.file = file;
        this.headers = headers;
        this.macroTable = macroTable;
        this.depth = depth;
    }

    // Used to lex a lookahead slice (the rest of a directive line) without
    // disturbing this tokenizer's own position; the slice is never at the
    // start of a physical line. A `#define`'s replacement list is lexed
    // against an empty table, so its macro names stay unresolved until
    // expansion; an `#if` condition shares this tokenizer's table.
    private CppTokenizer(String source, String file, int startLine, int startCol, MacroTable macroTable) {
        this(source, file, null, macroTable, 0);
        this.line = startLine;
        this.col = startCol;
        this.atLineStart = false;
    }

    // The source after phase 2, and where each deleted backslash-newline
    // was, as indices into the spliced text, so the physical line count
    // survives: a token after a continuation reports the line it is on.
    private record Spliced(String text, int[] continuations) {
    }

    private static Spliced spliced(String source) {
        var sb = new StringBuilder(source.length());
        var at = new ArrayList<Integer>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                i += 2;
                at.add(sb.length());
                continue;
            }
            sb.append(c);
            i++;
        }
        return new Spliced(sb.toString(), at.stream().mapToInt(Integer::intValue).toArray());
    }

    private static String splice(String source) {
        return spliced(source).text();
    }

    // The continuations of this text, the next one ahead of `pos`, how
    // many are behind it, and for a tokenizer over one directive line
    // the physical lines its parent had already passed.
    private int[] continuations = new int[0];
    private int nextContinuation;
    private int extraLines;
    private int lineShift;

    private void continuations(int[] positions) {
        this.continuations = positions;
        this.nextContinuation = 0;
    }

    // A tokenizer over the text of a directive line starting at `from`.
    private CppTokenizer lineTokenizer(String text, int from, MacroTable table) {
        var nested = new CppTokenizer(text, file, line, col + (from - pos), table);
        nested.lineShift = extraLines + lineShift;
        return nested;
    }

    public static List<Token> tokenize(@NonNull String source) {
        return new CppTokenizer(source).scan();
    }

    public static TokenSet tokenSet(@NonNull String source) {
        return tokenSet(source, null, "<source>");
    }

    public static TokenSet tokenSet(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file) {
        return tokenSet(source, headers, file, "");
    }

    /**
     * With {@code predefined}, lines of {@code #define} the target and
     * the compiler provide, in force before the source's first line.
     */
    public static TokenSet tokenSet(@NonNull String source, @Nullable HeaderProvider headers, @NonNull String file,
                                    @NonNull String predefined) {
        CppTokenizer tokenizer = new CppTokenizer(source, headers, file);
        if (!predefined.isBlank()) {
            new CppTokenizer(spliced(predefined), "<predefined>", headers, tokenizer.macroTable, 0).scan();
        }
        TokenSet set = TokenSet.fromTokens(tokenizer.scan());
        set.macros = tokenizer.macroTable;
        return set;
    }

    // Macro name -> its defining OBJECT_MACRO/CALL_MACRO token, as seen so
    // far by this tokenizer. A later #define for the same name overwrites
    // the earlier entry, matching last-definition-wins semantics.
    public MacroTable macroTable() {
        return macroTable;
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
        // Past a deleted backslash-newline: the next character is on the
        // following physical line, though still on this logical one.
        while (nextContinuation < continuations.length && continuations[nextContinuation] <= pos) {
            extraLines++;
            nextContinuation++;
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

    private LexException error(String message, int line, int column) {
        return new LexException(message, file, line + extraLines + lineShift, column);
    }

    private boolean active() {
        if (groups.isEmpty()) {
            return true;
        }
        return groups.peek().active;
    }

    private Token next() {
        while (true) {
            if (!pending.isEmpty()) {
                return pending.poll();
            }
            if (!active()) {
                skipGroup();
            }
            int before = pos;
            skipWhitespaceAndComments();
            boolean separated = pos > before;
            Token t = scanToken();
            if (t == null) {
                continue;
            }
            if (t.type == TokenType.EOF && !groups.isEmpty()) {
                Token open = groups.peek().at;
                throw error("unterminated #if", open.line, open.column);
            }
            t.spaceBefore = separated;
            t.file = file;
            t.seq = macroTable.version();
            t.physicalLine = t.line + extraLines + lineShift;
            return t;
        }
    }

    // Null for a directive line this tokenizer executed itself, which
    // leaves no token behind.
    private @Nullable Token scanToken() {
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
            Token hash = new Token(TokenType.PUNCTUATOR, "#", startLine, startCol);
            String directive = directiveNameAhead(pos);
            if (CONDITIONALS.contains(directive)) {
                conditional(hash);
                return null;
            }
            if (directive.equals("include")) {
                include(hash);
                return null;
            }
            if (directive.equals("line")) {
                lineDirective(hash);
                return null;
            }
            if (directive.equals("pragma") && pragma(hash)) {
                return null;
            }
            if (directive.equals("error")) {
                int start = pos;
                skipToLogicalLineEnd();
                String text = src.substring(start, pos).replaceFirst("^\\s*error\\s*", "").strip();
                throw error("#error " + text, hash.line, hash.column);
            }
            directiveState = DirectiveState.HASH;
            return hash;
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
            } else if (quote == '\'' && !prefix.contains("R")) {
                // u8, u, U and L all prefix character constants (6.4.5.4).
                int tokenStart = pos;
                for (int i = 0; i < prefix.length(); i++) advance();
                advance(); // consume opening quote
                return scanChar(tokenStart, startLine, startCol);
            }
        }

        if (isIdentifierStart(c)) {
            Token id = scanIdentifier(startLine, startCol);
            if (id.text.equals("__LINE__") && directiveState == DirectiveState.NONE) {
                return new Token(TokenType.PP_NUMBER, Integer.toString(startLine), startLine, startCol);
            }
            if (id.text.equals("__FILE__") && directiveState == DirectiveState.NONE) {
                return new Token(TokenType.STRING_LITERAL, "\"" + file + "\"", startLine, startCol);
            }
            return classifyDirectiveIdentifier(id);
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return scanPpNumber(startLine, startCol);
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
                String spelling = DIGRAPHS.getOrDefault(p, p);
                // Reached only when '#'/'##' aren't the line-start directive
                // marker, i.e. when they're the macro-body stringize/paste
                // operators.
                TokenType type = switch (spelling) {
                    case "##" -> TokenType.PASTE;
                    case "#" -> TokenType.STRINGIZE;
                    default -> TokenType.PUNCTUATOR;
                };
                return new Token(type, spelling, startLine, startCol);
            }
        }

        advance();
        return new Token(TokenType.UNKNOWN, String.valueOf(c), startLine, startCol);
    }

    private @Nullable String matchLiteralPrefix() {
        for (String p : STRING_PREFIXES) {
            if (src.startsWith(p, pos)) {
                return p;
            }
        }
        return null;
    }

    // Where a directive's line ends: at the new-line, except inside a
    // block comment, which may run on for lines, or a literal. Phase 2
    // has already spliced backslash-newlines away.
    private int logicalLineEnd(int from) {
        int i = from;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                if (close < 0) {
                    return src.length();
                }
                i = close + 2;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                int newline = src.indexOf('\n', i);
                return newline < 0 ? src.length() : newline;
            } else if (c == '"' || c == '\'') {
                i = literalEnd(i);
            } else {
                i++;
            }
        }
        return i;
    }

    // Past the literal opened at `from`, or at the new-line if it never closes.
    private int literalEnd(int from) {
        char quote = src.charAt(from);
        int i = from + 1;
        while (i < src.length() && src.charAt(i) != '\n') {
            if (src.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (src.charAt(i) == quote) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    // Consumes the rest of the directive's logical line, counting the
    // lines a comment inside it spans.
    private void skipToLogicalLineEnd() {
        int end = logicalLineEnd(pos);
        while (pos < end) {
            advance();
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                skipToLogicalLineEnd();
            } else if (c == '/' && peek(1) == '*') {
                skipBlockComment();
            } else {
                break;
            }
        }
    }

    private void skipBlockComment() {
        int startLine = line;
        int startCol = col;
        advance();
        advance();
        while (pos < src.length()) {
            if (peek() == '*' && peek(1) == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }
        throw error("Unterminated block comment", startLine, startCol);
    }

    // ---- #include ---------------------------------------------------------------------------------

    // Executes `#include "name"` or `#include <name>` whose `#` was just
    // consumed, leaving the position at the end of its line.
    private void include(Token hash) {
        while (isBlank(peek())) {
            advance();
        }
        scanIdentifier(line, col);
        while (isBlank(peek())) {
            advance();
        }
        char open = peek();
        char close = open == '"' ? '"' : '>';
        boolean quoted = open == '"';
        if (open != '"' && open != '<') {
            throw error("expected \"file\" or <file> after #include", hash.line, hash.column);
        }
        advance();
        int start = pos;
        while (pos < src.length() && peek() != close && peek() != '\n') {
            advance();
        }
        if (peek() != close) {
            throw error("expected \"file\" or <file> after #include", hash.line, hash.column);
        }
        String name = src.substring(start, pos);
        advance();
        skipToLogicalLineEnd();
        String spelled = open + name + close;
        if (headers == null) {
            throw error("no headers are available to #include " + spelled, hash.line, hash.column);
        }
        if (depth >= MAX_INCLUDE_DEPTH) {
            throw error("#include nested too deeply at " + spelled, hash.line, hash.column);
        }
        Optional<Header> header = headers.find(name, quoted, file);
        if (header.isEmpty()) {
            throw error("header not found: " + spelled, hash.line, hash.column);
        }
        CppTokenizer nested = new CppTokenizer(spliced(header.get().text()), header.get().name(), headers, macroTable, depth + 1);
        boolean first = true;
        for (Token t : nested.scan()) {
            if (t.type == TokenType.EOF) {
                continue;
            }
            if (first) {
                t.spaceBefore = true;
                first = false;
            }
            pending.add(t);
        }
    }

    // `#line N ["name"]`: the next line is line N. The number may come
    // from a macro; the name, when given, is the file from then on.
    private void lineDirective(Token hash) {
        while (isBlank(peek())) {
            advance();
        }
        scanIdentifier(line, col);
        List<Token> tokens = expandedRestOfLine(pos);
        if (tokens.isEmpty() || tokens.get(0).type != TokenType.PP_NUMBER) {
            throw error("#line needs a line number", hash.line, hash.column);
        }
        int number;
        try {
            number = Integer.parseInt(tokens.get(0).text);
        } catch (NumberFormatException e) {
            throw error("#line needs a line number", hash.line, hash.column);
        }
        if (tokens.size() > 1 && tokens.get(1).type == TokenType.STRING_LITERAL) {
            String quoted = tokens.get(1).text;
            file = quoted.substring(quoted.indexOf('"') + 1, quoted.length() - 1);
        }
        skipToLogicalLineEnd();
        line = number - 1;   // the new-line about to be consumed counts up to N
    }

    // `#pragma push_macro("name")` saves the macro's definition, or its
    // absence, on a stack of its own; `pop_macro` restores the last one
    // saved. True when the line was one of these and is consumed; any
    // other pragma is left for the token stream as before.
    private boolean pragma(Token hash) {
        int start = pos;
        while (isBlank(peek())) {
            advance();
        }
        scanIdentifier(line, col);
        List<Token> tokens = scanRestOfLine(pos, new MacroTable());
        boolean shaped = tokens.size() == 4
                && tokens.get(0).type == TokenType.IDENTIFIER
                && tokens.get(1).text.equals("(")
                && tokens.get(2).type == TokenType.STRING_LITERAL
                && tokens.get(3).text.equals(")");
        String action = shaped ? tokens.get(0).text : "";
        if (!action.equals("push_macro") && !action.equals("pop_macro")) {
            pos = start;
            return false;
        }
        String quoted = tokens.get(2).text;
        String name = quoted.substring(quoted.indexOf('"') + 1, quoted.length() - 1);
        List<Token> stack = pushedMacros.computeIfAbsent(name, k -> new ArrayList<>());
        if (action.equals("push_macro")) {
            stack.add(macroTable.get(name));
        } else if (!stack.isEmpty()) {
            Token saved = stack.remove(stack.size() - 1);
            if (saved == null) {
                macroTable.remove(name);
            } else {
                macroTable.put(name, saved);
            }
        }
        skipToLogicalLineEnd();
        return true;
    }

    // The rest of the line, macro expanded, as the directive's arguments.
    private List<Token> expandedRestOfLine(int from) {
        TokenSet set = TokenSet.fromTokens(scanConditionTokens(from));
        set.macros = macroTable;
        TokenSet expanded = new Scanner().expand(set);
        List<Token> tokens = new ArrayList<>();
        for (CppToken t : expanded.tokens) {
            if (t.token.type != TokenType.EOF) {
                tokens.add(t.token);
            }
        }
        return tokens;
    }

    // ---- conditional directives -------------------------------------------------------------------

    // The identifier that follows blanks at `from`, or "" if none; does
    // not move.
    private String directiveNameAhead(int from) {
        int p = from;
        while (p < src.length() && isBlank(src.charAt(p))) {
            p++;
        }
        int start = p;
        while (p < src.length() && isIdentifierPart(src.charAt(p))) {
            p++;
        }
        return src.substring(start, p);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == 11;
    }

    // Consumes the rest of a line, including its new-line; a block
    // comment is skipped whole, even across lines.
    private void skipLine() {
        while (pos < src.length()) {
            char c = peek();
            if (c == '\n') {
                advance();
                return;
            }
            if (c == '/' && peek(1) == '*') {
                skipBlockComment();
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && peek() != '\n') {
                    advance();
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                // A literal: what looks like a comment inside it is not one.
                int end = literalEnd(pos);
                while (pos < end) {
                    advance();
                }
                continue;
            }
            advance();
        }
    }

    // From the end of the directive line that made the group inactive,
    // past every line up to the next conditional directive, which is left
    // for scanToken to execute.
    private void skipGroup() {
        skipLine();
        while (pos < src.length()) {
            while (pos < src.length() && isBlank(peek())) {
                advance();
            }
            if (peek() == '#' && peek(1) != '#' && CONDITIONALS.contains(directiveNameAhead(pos + 1))) {
                atLineStart = true;
                return;
            }
            skipLine();
        }
    }

    // Executes a conditional directive whose `#` was just consumed,
    // leaving the position at the end of its line.
    private void conditional(Token hash) {
        while (isBlank(peek())) {
            advance();
        }
        Token name = scanIdentifier(line, col);
        switch (name.text) {
            case "if", "ifdef", "ifndef" -> {
                boolean parentActive = active();
                Group group = new Group(hash, parentActive);
                if (parentActive) {
                    group.taken = condition(name);
                }
                group.active = parentActive && group.taken;
                groups.push(group);
            }
            case "elif", "elifdef", "elifndef" -> {
                Group group = top(name);
                if (group.elseSeen) {
                    throw error("#elif after #else", name.line, name.column);
                }
                boolean evaluate = group.parentActive && !group.taken;
                boolean holds = false;
                if (evaluate) {
                    holds = condition(name);
                }
                group.taken = group.taken || holds;
                group.active = evaluate && holds;
            }
            case "else" -> {
                Group group = top(name);
                if (group.elseSeen) {
                    throw error("#else after #else", name.line, name.column);
                }
                group.elseSeen = true;
                group.active = group.parentActive && !group.taken;
                group.taken = true;
            }
            case "endif" -> {
                top(name);
                groups.pop();
            }
            default -> throw new IllegalStateException(name.text);
        }
        skipToLogicalLineEnd();
    }

    private Group top(Token name) {
        if (groups.isEmpty()) {
            String directive = name.text.startsWith("elif") ? "#elif" : "#" + name.text;
            throw error(directive + " without #if", name.line, name.column);
        }
        return groups.peek();
    }

    // The condition of an `if`-family directive at `pos`: a macro name for
    // the `def` forms, otherwise a constant expression.
    private boolean condition(Token name) {
        List<Token> rest = scanConditionTokens(pos);
        if (!name.text.endsWith("def")) {
            return evaluate(rest, name);
        }
        boolean oneName = rest.size() == 1 && isName(rest.get(0));
        if (!oneName) {
            throw error("expected a macro name after #" + name.text, name.line, name.column);
        }
        boolean defined = macroTable.containsKey(rest.get(0).text);
        if (name.text.endsWith("ndef")) {
            return !defined;
        }
        return defined;
    }

    private static boolean isName(Token t) {
        return switch (t.type) {
            case IDENTIFIER, KEYWORD, OBJECT_MACRO, CALL_MACRO -> true;
            default -> false;
        };
    }

    // `defined X` and `defined(X)` become 1 or 0, the rest is macro
    // expanded, and the result is evaluated as a constant expression.
    private boolean evaluate(List<Token> rest, Token name) {
        List<Token> rewritten = rewriteDefined(rest);
        TokenSet set = TokenSet.fromTokens(rewritten);
        set.macros = macroTable;
        TokenSet expanded = new Scanner().expand(set);
        List<Token> tokens = new ArrayList<>();
        for (CppToken t : expanded.tokens) {
            if (t.token.type != TokenType.EOF) {
                tokens.add(t.token);
            }
        }
        return PpExpr.evaluate(tokens, name).isTrue();
    }

    private List<Token> rewriteDefined(List<Token> rest) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < rest.size()) {
            Token t = rest.get(i);
            boolean isDefined = t.type == TokenType.IDENTIFIER && t.text.equals("defined");
            if (!isDefined) {
                out.add(t);
                i++;
                continue;
            }
            int j = i + 1;
            boolean paren = j < rest.size() && isPunctuator(rest.get(j), "(");
            if (paren) {
                j++;
            }
            if (j >= rest.size() || !isName(rest.get(j))) {
                throw error("expected an identifier after 'defined'", t.line, t.column);
            }
            boolean defined = macroTable.containsKey(rest.get(j).text);
            j++;
            if (paren) {
                if (j >= rest.size() || !isPunctuator(rest.get(j), ")")) {
                    throw error("expected ')' after 'defined(" + rest.get(j - 1).text + "'", t.line, t.column);
                }
                j++;
            }
            Token value = new Token(TokenType.PP_NUMBER, defined ? "1" : "0", t.line, t.column);
            value.spaceBefore = t.spaceBefore;
            value.file = t.file;
            out.add(value);
            i = j;
        }
        return out;
    }

    private static boolean isPunctuator(Token t, String text) {
        return t.type == TokenType.PUNCTUATOR && t.text.equals(text);
    }

    // `$` is accepted in identifiers as GCC and Clang do: the shell names
    // its results `$1`, `$2`, ... and nothing portable can collide.
    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
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
                // The rest of the line is scanned again as tokens the
                // scanner strips by line; a comment that runs past the
                // line would leave what follows it, so that case is consumed here.
                int lineEnd = logicalLineEnd(pos);
                int newline = src.indexOf('\n', pos);
                boolean spansLines = newline >= 0 && newline < lineEnd;
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
                    if (spansLines) {
                        skipToLogicalLineEnd();
                    }
                    return callMacro;
                }
                Token objectMacro = new Token(TokenType.OBJECT_MACRO, idToken.text, idToken.line, idToken.column);
                objectMacro.expansion = scanExpansionTokens(pos);
                macroTable.put(objectMacro.text, objectMacro);
                if (spansLines) {
                    skipToLogicalLineEnd();
                }
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
        return matchingParen(from, logicalLineEnd(from));
    }

    // The ')' matching the '(' at `from` before `end`, or -1; parentheses
    // in literals and comments do not count.
    private int matchingParen(int from, int end) {
        int depth = 0;
        int i = from;
        while (i < end) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                i = literalEnd(i);
                continue;
            }
            if (c == '/' && i + 1 < end && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                i = close < 0 ? end : close + 2;
                continue;
            }
            if (c == '/' && i + 1 < end && src.charAt(i + 1) == '/') {
                int newline = src.indexOf('\n', i);
                i = newline < 0 ? end : newline;
                continue;
            }
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
        return matchingParen(from, src.length());
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

        List<Token> raw = lineTokenizer(inner, from, new MacroTable()).scan();
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
        List<Token> raw = lineTokenizer(inner, from, new MacroTable()).scan();
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
        return scanRestOfLine(from, new MacroTable());
    }

    // The rest of an `#if` line, with macro occurrences resolved against
    // this tokenizer's table as in ordinary code.
    private List<Token> scanConditionTokens(int from) {
        return scanRestOfLine(from, macroTable);
    }

    private List<Token> scanRestOfLine(int from, MacroTable table) {
        int end = logicalLineEnd(from);
        String rest = src.substring(from, end);
        List<Token> raw = lineTokenizer(rest, from, table).scan();
        List<Token> expansion = new ArrayList<>();
        for (Token t : raw) {
            if (t.type != TokenType.EOF) {
                expansion.add(t);
            }
        }
        return expansion;
    }

    // pp-number (C2y 6.4.9): a deliberately greedy superset of the integer
    // and floating constant grammars. After the leading digit (or '.'
    // digit) it swallows identifier characters, '.', digit separators, and
    // a sign when it directly follows e/E/p/P - so `0xE+2` is one
    // pp-number that phase 7 later rejects, exactly as the standard's
    // example calls for. Classification into INTEGER_CONSTANT or
    // FLOATING_CONSTANT is TokenConversion's job.
    private Token scanPpNumber(int startLine, int startCol) {
        int start = pos;
        if (peek() == '.') {
            advance();
        }
        advance(); // first digit
        while (pos < src.length()) {
            char c = peek();
            if (c == 'e' || c == 'E' || c == 'p' || c == 'P') {
                advance();
                if (peek() == '+' || peek() == '-') advance();
            } else if (isIdentifierPart(c) || c == '.') {
                advance();
            } else if (c == '\'' && isIdentifierPart(peek(1))) {
                advance(); // digit separator
                advance();
            } else {
                break;
            }
        }
        return new Token(TokenType.PP_NUMBER, src.substring(start, pos), startLine, startCol);
    }

    private Token scanString(int start, int startLine, int startCol) {
        while (true) {
            if (pos >= src.length() || peek() == '\n') {
                throw error("Unterminated string literal", startLine, startCol);
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
                throw error("Unterminated character literal", startLine, startCol);
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
            throw error("Unterminated raw string literal", startLine, startCol);
        }
        String delim = src.substring(delimStart, pos);
        String terminator = ")" + delim + "\"";
        advance(); // consume '('
        int closeIdx = src.indexOf(terminator, pos);
        if (closeIdx < 0) {
            throw error("Unterminated raw string literal", startLine, startCol);
        }
        while (pos < closeIdx + terminator.length()) {
            advance();
        }
        String text = src.substring(start, pos);
        return new Token(TokenType.STRING_LITERAL, text, startLine, startCol);
    }
}

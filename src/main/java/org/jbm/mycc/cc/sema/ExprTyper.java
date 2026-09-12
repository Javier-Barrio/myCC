package org.jbm.mycc.cc.sema;

import java.util.Optional;
import org.jbm.mycc.cc.sema.tast.TStmt;
import org.jbm.mycc.cc.parse.ast.Stmt;
import lombok.NonNull;
import org.jbm.mycc.cc.parse.ast.Expr;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.sema.tast.StringData;
import org.jbm.mycc.cc.sema.tast.TExpr;
import org.jbm.mycc.cc.sema.tast.TExpr.Lvalue;
import org.jbm.mycc.cc.sema.tast.TExpr.Rvalue;
import org.jbm.mycc.cc.sema.tast.TInit;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Layout;
import org.jbm.mycc.cc.sema.types.Quals;
import org.jbm.mycc.cc.sema.types.Types;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Types expressions (C2y 6.5): builds a {@link TExpr} bottom-up from an
 * AST expression, through helpers whose signatures are the rules of 6.3.
 * {@link #rvalue} is lvalue conversion and decay by narrowing on the
 * category; {@link #convert} inserts one {@link TExpr.Conversion} only
 * when the type changes; {@link Types#usualArithmetic} is pure type
 * arithmetic.
 */
final class ExprTyper {

    private final Types types;
    private final Bindings bindings;
    private final TypeBuilder builder;
    private final Literals literals;
    private final ConstEval constEval;

    // Depth of sizeof/typeof operands being typed: no objects are created
    // for what is not evaluated (6.5.4.4p2).
    private int unevaluated;

    /** The string literals typed so far, in order; each one is its own object (6.4.5p7). */
    final List<StringData> strings = new ArrayList<>();
    private final java.util.Map<Symbol, StringData> stringObjects = new java.util.IdentityHashMap<>();
    private int nextId;

    // Where anonymous automatic objects (materialized temporaries,
    // compound literals) are registered: the current function's locals,
    // set by the typer; and where anonymous static ones go.
    private @Nullable List<Symbol> locals;
    private @Nullable Initializers initializers;
    private @Nullable java.util.function.BiConsumer<Symbol, TInit> staticSink;

    ExprTyper(@NonNull Types types, @NonNull Bindings bindings, @NonNull TypeBuilder builder,
              @NonNull ConstEval constEval) {
        this.types = types;
        this.bindings = bindings;
        this.builder = builder;
        this.literals = new Literals(types);
        this.constEval = constEval;
        this.nextId = bindings.symbolCount;
    }

    // Types the block of a statement expression; set by the typer.
    private @Nullable java.util.function.Function<Stmt.Compound, TStmt.Block> blocks;

    void setBlocks(@NonNull java.util.function.Function<Stmt.Compound, TStmt.Block> blocks) {
        this.blocks = blocks;
    }

    void setLocals(@Nullable List<Symbol> locals) {
        this.locals = locals;
    }

    void setInitializers(@NonNull Initializers initializers, @NonNull java.util.function.BiConsumer<Symbol, TInit> staticSink) {
        this.initializers = initializers;
        this.staticSink = staticSink;
    }

    int nextSymbolId() {
        return nextId++;
    }

    /** Types an operand that is not evaluated: the type is all that is wanted. */
    TExpr typeUnevaluated(@NonNull Expr e) {
        unevaluated++;
        try {
            return type(e);
        } finally {
            unevaluated--;
        }
    }

    TExpr type(@NonNull Expr e) {
        if (e instanceof Expr.Identifier id) return identifier(id);
        if (e instanceof Expr.Literal l) return literals.constant(l.token());
        if (e instanceof Expr.StringLiteral s) return string(s);
        if (e instanceof Expr.Binary b) return binary(b);
        if (e instanceof Expr.Index i) return index(i);
        if (e instanceof Expr.Member m) return member(m);
        if (e instanceof Expr.Unary u) return unary(u);
        if (e instanceof Expr.Call c) return call(c);
        if (e instanceof Expr.Cast c) return cast(c);
        if (e instanceof Expr.CompoundLiteral c) return compoundLiteral(c);
        if (e instanceof Expr.Generic g) return generic(g);
        if (e instanceof Expr.TypeOperator t) return sizeOf(t.op(), builder.build(t.type()));
        if (e instanceof Expr.StaticAssertion s) throw unsupported("static_assert as an expression", s.keyword());
        if (e instanceof Expr.Assign a) return assign(a);
        if (e instanceof Expr.Postfix p) return postfix(p);
        if (e instanceof Expr.Conditional c) return conditional(c);
        if (e instanceof Expr.Comma c) return comma(c);
        if (e instanceof Expr.StmtExpr s) return stmtExpr(s);
        throw unsupported(e.getClass().getSimpleName(), tokenOf(e));
    }

    // ---- primary expressions ------------------------------------------------------------

    private TExpr identifier(Expr.Identifier e) {
        Symbol s = bindings.symbolOf(e);
        if (!s.hasType()) throw new SemaException("'" + s.name + "' is used in its own auto initializer", e.name());
        if (s instanceof Symbol.Function) return new TExpr.FuncRef(s, s.type(), e.name());
        if (s instanceof Symbol.Enumerator en) return new TExpr.IntConst(en.value(), en.type(), e.name());
        return new TExpr.VarRef(s, s.type(), e.name());
    }

    // A string literal denotes an anonymous array object with static
    // storage (6.4.5p7): one symbol per literal, named by its spelling,
    // referenced as an lvalue of array type.
    private TExpr string(Expr.StringLiteral e) {
        Literals.StringValue value = literals.string(e.parts());
        Token first = e.parts().get(0);
        String spelling = e.parts().stream().map(t -> t.text).collect(java.util.stream.Collectors.joining(" "));
        var name = new Token(TokenType.STRING_LITERAL, spelling, first.line, first.column);
        Symbol symbol = Symbol.anonymousStatic(nextId++, name);
        symbol.setType(types.array(value.elementType(), value.units().length));
        var data = new StringData(symbol, value.units());
        stringObjects.put(symbol, data);
        if (unevaluated == 0) strings.add(data);
        return new TExpr.VarRef(symbol, symbol.type(), first);
    }

    /** The string literal an expression is, if it is one (possibly parenthesized), before any decay. */
    java.util.Optional<StringData> stringLiteral(TExpr x) {
        if (x instanceof TExpr.VarRef v) return java.util.Optional.ofNullable(stringObjects.get(v.symbol()));
        return java.util.Optional.empty();
    }

    /** Drops a string literal's object: its bytes were copied into an array it initialized. */
    void dropString(StringData data) {
        strings.remove(data);
    }

    // ---- binary operators ------------------------------------------------------------------

    private TExpr binary(Expr.Binary e) {
        return binaryOp(e.op(), e.op().text, rvalue(type(e.left())), rvalue(type(e.right())));
    }

    // The binary operator `opText` over converted operands; `op` is the
    // token diagnostics point at (the `+=` of a compound assignment too).
    private Rvalue binaryOp(Token op, String opText, Rvalue l, Rvalue r) {
        return (Rvalue) switch (opText) {
            case "+" -> l.type().isPointer() || r.type().isPointer() ? pointerAdd(op, l, r, false)
                    : arithmetic(op, l, r, false, TExpr.Add::new);
            case "-" -> l.type().isPointer() || r.type().isPointer() ? pointerSub(op, l, r)
                    : arithmetic(op, l, r, false, TExpr.Sub::new);
            case "*" -> arithmetic(op, l, r, false, TExpr.Mul::new);
            case "/" -> arithmetic(op, l, r, false, TExpr.Div::new);
            case "%" -> arithmetic(op, l, r, true, TExpr.Rem::new);
            case "&" -> arithmetic(op, l, r, true, TExpr.BitAnd::new);
            case "|" -> arithmetic(op, l, r, true, TExpr.BitOr::new);
            case "^" -> arithmetic(op, l, r, true, TExpr.BitXor::new);
            case "<<" -> shift(op, l, r, TExpr.Shl::new);
            case ">>" -> shift(op, l, r, TExpr.Shr::new);
            case "==" -> comparison(op, l, r, TExpr.Eq::new);
            case "!=" -> comparison(op, l, r, TExpr.Ne::new);
            case "<" -> comparison(op, l, r, TExpr.Lt::new);
            case "<=" -> comparison(op, l, r, TExpr.Le::new);
            case ">" -> comparison(op, l, r, TExpr.Gt::new);
            case ">=" -> comparison(op, l, r, TExpr.Ge::new);
            case "&&" -> logical(op, l, r, TExpr.And::new);
            case "||" -> logical(op, l, r, TExpr.Or::new);
            default -> throw unsupported("operator " + opText, op);
        };
    }

    // ---- casts (6.5.5) -----------------------------------------------------------------------------

    // (T) e: T is void or a scalar type and e a scalar; the result is an
    // rvalue of the unqualified T. Every scalar pair converts except
    // floating with pointer; an integer zero cast to void * stays a null
    // pointer constant.
    private TExpr cast(Expr.Cast e) {
        Token at = e.paren();
        CType to = types.unqualified(builder.build(e.type()));
        Rvalue x = rvalue(type(e.operand()));
        if (to.isVoid()) return toVoid(x);
        if (to.isRecord() && types.unqualified(x.type()) == to) {
            // A struct or union cast to its own type is the value itself,
            // which gcc allows with a pedantic warning.
            return x;
        }
        if (!to.isScalar()) throw new SemaException("cast to non-scalar type '" + to.spelling() + "'", at);
        CType from = x.type();
        if (!from.isScalar()) throw new SemaException("cast of non-scalar type '" + from.spelling() + "'", at);
        boolean floatingWithPointer = from.isFloating() && !to.isArithmetic() || to.isFloating() && !from.isArithmetic();
        if (floatingWithPointer) {
            throw new SemaException("cannot cast '" + from.spelling() + "' to '" + to.spelling() + "'", at);
        }
        if (to.isNullptr() && !isNullish(x)) {
            throw new SemaException("only a null pointer constant converts to nullptr_t", at);
        }
        if (from.isNullptr() && to.isInteger() && !to.isBool()) {
            throw new SemaException("cannot cast 'nullptr_t' to '" + to.spelling() + "'", at);
        }
        Rvalue converted = to.isNullptr() ? (from.isNullptr() ? x : new TExpr.NullToPtr(x, to, at)) : convert(x, to);
        return retoken(converted, x, at);
    }

    // A cast's conversion node is anchored at the cast, not at its operand.
    private Rvalue retoken(Rvalue converted, Rvalue operand, Token at) {
        if (converted == operand) return converted;
        if (converted instanceof TExpr.IntToInt c) return new TExpr.IntToInt(c.operand(), c.type(), at);
        if (converted instanceof TExpr.IntToFloat c) return new TExpr.IntToFloat(c.operand(), c.type(), at);
        if (converted instanceof TExpr.FloatToInt c) return new TExpr.FloatToInt(c.operand(), c.type(), at);
        if (converted instanceof TExpr.FloatToFloat c) return new TExpr.FloatToFloat(c.operand(), c.type(), at);
        if (converted instanceof TExpr.ToBool c) return new TExpr.ToBool(c.operand(), c.type(), at);
        if (converted instanceof TExpr.PtrToPtr c) return new TExpr.PtrToPtr(c.operand(), c.type(), at);
        if (converted instanceof TExpr.IntToPtr c) return new TExpr.IntToPtr(c.operand(), c.type(), at);
        if (converted instanceof TExpr.PtrToInt c) return new TExpr.PtrToInt(c.operand(), c.type(), at);
        if (converted instanceof TExpr.NullToPtr c) return new TExpr.NullToPtr(c.operand(), c.type(), at);
        return converted;
    }

    // ---- compound literals (6.5.3.6) ----------------------------------------------------------------

    // (T){...}: an lvalue designating an anonymous object of type T,
    // completed from the initializer if T is an incomplete array. Static
    // storage at file scope or with `static`, else automatic in the
    // enclosing block. No object is created for an unevaluated operand.
    private TExpr compoundLiteral(Expr.CompoundLiteral e) {
        Token at = e.paren();
        if (initializers == null) throw new IllegalStateException("no initializers");
        boolean isStatic = locals == null;
        for (Token sc : e.storageClasses()) {
            if (sc.text.equals("static")) isStatic = true;
            else throw unsupported("'" + sc.text + "' on a compound literal", sc);
        }
        CType t = builder.build(e.type());
        if (t.isFunction() || t.isVoid()) throw new SemaException("compound literal of type '" + t.spelling() + "'", at);
        if (!t.isComplete() && !t.isArray()) throw new SemaException("compound literal of incomplete type '" + t.spelling() + "'", at);
        var name = new Token(TokenType.IDENTIFIER, "<literal" + nextId + ">", at.line, at.column);
        Symbol symbol = isStatic ? Symbol.anonymousStatic(nextId++, name) : Symbol.anonymousAutomatic(nextId++, name, 1);
        var r = initializers.normalize(e.initializer(), t, at);
        symbol.setType(r.type());
        TInit init = r.init();
        if (isStatic) {
            var items = new ArrayList<TInit.Item>(init.items().size());
            for (var item : init.items()) {
                items.add(new TInit.Item(item.offset(), constEval.require(item.value(), item.value().token(), "initializer element"), item.bits()));
            }
            init = new TInit(items);
        }
        if (unevaluated == 0) {
            if (isStatic) staticSink.accept(symbol, init);
            else locals.add(symbol);
        }
        return new TExpr.CompoundLit(symbol, init, r.type(), at);
    }

    // ---- generic selection (6.5.2.1) ----------------------------------------------------------------

    // The controlling type is the operand's after lvalue conversion and
    // decay (or the type-name given); the association whose type is
    // compatible with it is chosen, else the default. Only the chosen
    // expression is typed, since no other is evaluated.
    private TExpr generic(Expr.Generic e) {
        Token at = e.keyword();
        CType controlling = e.controllingType().isPresent() ? builder.build(e.controllingType().get())
                : rvalue(typeUnevaluated(e.controllingExpr().orElseThrow())).type();
        Expr.Generic.Association chosen = null;
        Expr.Generic.Association defaultAssociation = null;
        var seen = new ArrayList<CType>();
        for (var a : e.associations()) {
            if (a.type().isEmpty()) {
                if (defaultAssociation != null) throw new SemaException("duplicate default in _Generic", at);
                defaultAssociation = a;
                continue;
            }
            CType t = builder.build(a.type().get());
            if (!t.isComplete() || t.isFunction()) {
                throw new SemaException("_Generic association type '" + t.spelling() + "' is not a complete object type", at);
            }
            for (CType s : seen) {
                if (types.compatible(s, t)) {
                    throw new SemaException("_Generic associations '" + s.spelling() + "' and '" + t.spelling()
                            + "' are compatible", at);
                }
            }
            seen.add(t);
            if (chosen == null && types.compatible(t, controlling)) chosen = a;
        }
        if (chosen == null) chosen = defaultAssociation;
        if (chosen == null) {
            throw new SemaException("no _Generic association matches '" + controlling.spelling() + "'", at);
        }
        return type(chosen.expr());
    }

    // ---- calls (6.5.3.3) --------------------------------------------------------------------------

    private TExpr call(Expr.Call e) {
        Token at = e.paren();
        Rvalue callee = rvalue(type(e.callee()));
        if (!(callee.type() instanceof CType.Pointer p && p.target() instanceof CType.Function f)) {
            throw new SemaException("called object is not a function or function pointer ('"
                    + callee.type().spelling() + "')", at);
        }
        int given = e.arguments().size(), expected = f.parameters().size();
        if (given < expected || given > expected && !f.isVariadic()) {
            throw new SemaException("too " + (given < expected ? "few" : "many") + " arguments to function: expected "
                    + expected + (f.isVariadic() ? " or more" : "") + ", got " + given, at);
        }
        var args = new ArrayList<Rvalue>(given);
        for (int i = 0; i < given; i++) {
            Rvalue arg = rvalue(type(e.arguments().get(i)));
            Token argAt = arg.token();
            if (arg.type().isVoid()) throw new SemaException("argument " + (i + 1) + " has type void", argAt);
            if (i < expected) {
                args.add(assignConvert(arg, f.parameters().get(i), argAt, "passing argument " + (i + 1) + " of type"));
            } else {
                // Past the prototype: default argument promotions (6.5.3.3p7).
                args.add(convert(arg, types.defaultArgumentPromote(arg.type())));
            }
        }
        // f(x), (*f)(x) and (&f)(x) all decay the designator of f (6.5.3.3):
        // the function is known statically, so the call names it.
        if (callee instanceof TExpr.FunctionDecay fd && fd.operand() instanceof TExpr.FuncRef fr) {
            return new TExpr.DirectCall(fr.symbol(), args, f.returnType(), at);
        }
        return new TExpr.IndirectCall(callee, args, f.returnType(), at);
    }

    // ---- assignment (6.5.17) -------------------------------------------------------------------

    private TExpr assign(Expr.Assign e) {
        Token op = e.op();
        Lvalue target = modifiable(type(e.target()), op);
        CType resultType = types.unqualified(target.type());
        Rvalue value = rvalue(type(e.value()));
        if (op.text.equals("=")) {
            return new TExpr.Assign(target, assignConvert(value, resultType, op, "assigning to"), resultType, op);
        }
        // a op= b: a = a op b with a evaluated once (6.5.17.3p3). The
        // TargetValue stands for that one evaluation, and the operator's
        // own typing supplies the computation type and its conversions.
        String opText = op.text.substring(0, op.text.length() - 1);
        if ((opText.equals("+") || opText.equals("-")) && value.type().isPointer()) {
            throw new SemaException("invalid operands to " + op.text + " ('" + target.type().spelling() + "' and '"
                    + value.type().spelling() + "')", op);
        }
        Rvalue current = new TExpr.TargetValue(target, resultType, op);
        Rvalue computed = binaryOp(op, opText, current, value);
        return new TExpr.CompoundAssign(target, assignConvert(computed, resultType, op, "assigning to"), resultType, op);
    }

    // i++ / i-- with the value used: the old value is yielded, the new one
    // is i + 1 typed like any addition (6.5.3.4p2).
    private TExpr postfix(Expr.Postfix e) {
        Token op = e.op();
        Lvalue target = modifiable(type(e.operand()), op);
        CType resultType = types.unqualified(target.type());
        if (!resultType.isArithmetic() && !resultType.isPointer() || resultType.isBool()) {
            throw new SemaException("cannot " + (op.text.equals("++") ? "increment" : "decrement") + " a value of type '"
                    + resultType.spelling() + "'", op);
        }
        Rvalue current = new TExpr.TargetValue(target, resultType, op);
        Rvalue one = new TExpr.IntConst(1, types.int_(), op);
        Rvalue computed = binaryOp(op, op.text.equals("++") ? "+" : "-", current, one);
        return new TExpr.PostfixAssign(target, assignConvert(computed, resultType, op, "assigning to"), resultType, op);
    }

    /** The narrowing that fails with "not an lvalue". */
    Lvalue lvalue(TExpr x, Token at) {
        if (x instanceof Lvalue lv) return lv;
        throw new SemaException("expression is not an lvalue", at);
    }

    /**
     * A modifiable lvalue (6.3.3.1p1): not an array, not incomplete, not
     * const-qualified.
     */
    Lvalue modifiable(TExpr x, Token at) {
        Lvalue lv = lvalue(x, at);
        if (isTemporaryMember(lv)) throw new SemaException("cannot modify a member of a temporary object", at);
        CType t = lv.type();
        if (t.isArray()) throw new SemaException("cannot assign to an array", at);
        if (!t.isComplete()) throw new SemaException("cannot assign to an incomplete type '" + t.spelling() + "'", at);
        if (t.quals().isConst()) throw new SemaException("cannot assign to const-qualified type '" + t.spelling() + "'", at);
        return lv;
    }

    /**
     * Conversion "as if by assignment" (6.5.17.2p1), the constraints shared
     * by {@code =}, argument passing, {@code return} and initialization:
     * arithmetic to arithmetic, any scalar to {@code bool}, pointers to
     * compatible types where the target adds qualifiers, object pointers
     * to and from {@code void *}, and null pointer constants.
     */
    Rvalue assignConvert(Rvalue x, CType to, Token at, String context) {
        CType from = x.type();
        if (from == to) return x;
        if (to.isBool() && from.isScalar()) return convert(x, to);
        if (to.isArithmetic() && from.isArithmetic()) return convert(x, to);
        if (to instanceof CType.Pointer tp) {
            if (isNullish(x)) return convert(x, to);
            if (from instanceof CType.Pointer fp) {
                // Discarded qualifiers and a function pointer through void *
                // are constraint violations that gcc only warns about, and
                // real code relies on both; accepted here without a word.
                CType tt = tp.target(), ft = fp.target();
                boolean compatible = types.compatible(types.unqualified(tt), types.unqualified(ft));
                boolean viaVoid = tt.isVoid() || ft.isVoid();
                if (compatible || viaVoid) return convert(x, to);
            }
        }
        if (to.isNullptr() && isNullish(x)) return x.type().isNullptr() ? x : new TExpr.NullToPtr(x, to, at);
        throw new SemaException("incompatible types when " + context + " '" + to.spelling() + "' from '"
                + from.spelling() + "'", at);
    }

    private interface BinaryNode {
        TExpr make(Rvalue left, Rvalue right, CType type, Token token);
    }

    // 6.5.6 - 6.5.7, 6.5.11 - 6.5.13 for arithmetic operands: usual
    // arithmetic conversions on both, the result in the common type;
    // %, &, ^ and | need integer operands.
    private TExpr arithmetic(Token op, Rvalue l, Rvalue r, boolean integerOnly, BinaryNode node) {
        boolean ok = integerOnly ? l.type().isInteger() && r.type().isInteger()
                : l.type().isArithmetic() && r.type().isArithmetic();
        if (!ok) throw invalidOperands(op, l, r);
        CType common = types.usualArithmetic(promotedType(l), promotedType(r));
        return node.make(convert(l, common), convert(r, common), common, op);
    }

    // 6.5.8: integer operands, each promoted on its own; the result has
    // the promoted left operand's type.
    private TExpr shift(Token op, Rvalue l, Rvalue r, BinaryNode node) {
        if (!l.type().isInteger() || !r.type().isInteger()) throw invalidOperands(op, l, r);
        Rvalue value = promote(l);
        return node.make(value, promote(r), value.type(), op);
    }

    // 6.5.9 - 6.5.10: arithmetic operands under the usual arithmetic
    // conversions, or pointers brought to one common pointer type.
    private TExpr comparison(Token op, Rvalue l, Rvalue r, BinaryNode node) {
        if (l.type().isArithmetic() && r.type().isArithmetic()) {
            CType common = types.usualArithmetic(promotedType(l), promotedType(r));
            return node.make(convert(l, common), convert(r, common), types.int_(), op);
        }
        boolean equality = op.text.equals("==") || op.text.equals("!=");
        CType common = commonPointerType(l, r, equality);
        if (common == null) throw invalidOperands(op, l, r);
        return node.make(convert(l, common), convert(r, common), types.int_(), op);
    }

    /**
     * The type two pointer-ish operands are brought to for comparison
     * (6.5.10p2, 6.5.11p2) or as the arms of {@code ?:} (6.5.16p3, p6):
     * pointers to compatible types compose (with the union of the target
     * qualifiers), {@code void *} absorbs any object pointer, a null
     * pointer constant or {@code nullptr_t} takes the other operand's
     * type. Relational operators only allow the first case. Null when
     * the operands do not combine.
     */
    private @Nullable CType commonPointerType(Rvalue l, Rvalue r, boolean allowVoidAndNull) {
        CType lt = l.type(), rt = r.type();
        if (lt instanceof CType.Pointer lp && rt instanceof CType.Pointer rp) {
            CType lTarget = lp.target(), rTarget = rp.target();
            Quals quals = lTarget.quals().plus(rTarget.quals());
            if (types.compatible(types.unqualified(lTarget), types.unqualified(rTarget))) {
                return types.pointer(types.qualified(types.composite(types.unqualified(lTarget),
                        types.unqualified(rTarget)), quals));
            }
            if (allowVoidAndNull && (lTarget.isVoid() || rTarget.isVoid())
                    && !lTarget.isFunction() && !rTarget.isFunction()) {
                return types.pointer(types.qualified(types.void_(), quals));
            }
            return null;
        }
        if (!allowVoidAndNull) return null;
        if (lt.isPointer() && isNullish(r)) return lt;
        if (rt.isPointer() && isNullish(l)) return rt;
        if (lt.isNullptr() && rt.isNullptr()) return lt;
        return null;
    }

    /** A null pointer constant (6.3.2.3p3) or a value of type {@code nullptr_t}. */
    boolean isNullish(Rvalue x) {
        return x.type().isNullptr() || isNullPointerConstant(x);
    }

    // An integer constant expression with value 0, or one cast to void * (6.3.2.3p3).
    boolean isNullPointerConstant(Rvalue x) {
        if (x instanceof TExpr.NullToPtr n) return n.type() == types.pointer(types.void_()) && isNullPointerConstant(n.operand());
        if (!x.type().isInteger()) return false;
        return constEval.fold(x).filter(c -> c instanceof TExpr.IntConst i && i.value() == 0).isPresent();
    }

    // 6.5.14 - 6.5.15: scalar operands, each tested against zero.
    private TExpr logical(Token op, Rvalue l, Rvalue r, BinaryNode node) {
        if (!l.type().isScalar() || !r.type().isScalar()) throw invalidOperands(op, l, r);
        return node.make(toBool(l), toBool(r), types.int_(), op);
    }

    // ---- pointer arithmetic (6.5.7) and subscripting (6.5.3.1) -------------------------------

    // pointer + integer, in either order; the pointee must be a complete object type.
    private TExpr pointerAdd(Token op, Rvalue l, Rvalue r, boolean negate) {
        Rvalue pointer = l.type().isPointer() ? l : r;
        Rvalue index = pointer == l ? r : l;
        if (!index.type().isInteger()) throw invalidOperands(op, l, r);
        requireCompleteObjectPointer(op, pointer);
        Rvalue offset = convert(index, types.ptrdiffT());
        if (negate) offset = new TExpr.Neg(offset, offset.type(), op);
        return new TExpr.PtrAdd(pointer, offset, pointer.type(), op);
    }

    // pointer - integer, or pointer - pointer to compatible object types.
    private TExpr pointerSub(Token op, Rvalue l, Rvalue r) {
        if (!l.type().isPointer()) throw invalidOperands(op, l, r);
        if (r.type().isInteger()) return pointerAdd(op, l, r, true);
        if (!(r.type() instanceof CType.Pointer)) throw invalidOperands(op, l, r);
        requireCompleteObjectPointer(op, l);
        requireCompleteObjectPointer(op, r);
        CType lTarget = types.unqualified(((CType.Pointer) l.type()).target());
        CType rTarget = types.unqualified(((CType.Pointer) r.type()).target());
        if (!types.compatible(lTarget, rTarget)) throw invalidOperands(op, l, r);
        return new TExpr.PtrDiff(l, r, types.ptrdiffT(), op);
    }

    // a[i] is *(a + i) (6.5.3.1p2), in either order.
    private TExpr index(Expr.Index e) {
        Rvalue a = rvalue(type(e.array()));
        Rvalue i = rvalue(type(e.index()));
        if (!a.type().isPointer() && !i.type().isPointer()) {
            throw new SemaException("subscripted value is not an array or pointer ('" + a.type().spelling() + "')",
                    e.bracket());
        }
        Rvalue sum = (Rvalue) pointerAdd(e.bracket(), a, i, false);
        return deref(e.bracket(), sum);
    }

    // ---- member access (6.5.3.4) --------------------------------------------------------------

    // s.m needs a struct or union lvalue (an rvalue is materialized first);
    // p->m is (*p).m. The result has the member's type with the base's
    // qualifiers added (6.5.3.4p4).
    private TExpr member(Expr.Member e) {
        Token at = e.op();
        TExpr object = type(e.object());
        Lvalue base;
        if (e.op().text.equals("->")) {
            Rvalue p = rvalue(object);
            if (!(p.type() instanceof CType.Pointer ptr) || !ptr.target().isRecord()) {
                throw new SemaException("member reference type '" + p.type().spelling()
                        + "' is not a pointer to a structure or union", at);
            }
            base = new TExpr.Deref(p, ptr.target(), at);
        } else if (object instanceof Lvalue lv) {
            base = lv;
        } else if (object instanceof Rvalue rv && rv.type().isRecord()) {
            base = materialize(rv, at);
        } else {
            throw new SemaException("member reference base type '" + object.type().spelling()
                    + "' is not a structure or union", at);
        }
        if (!(base.type() instanceof CType.Record record)) {
            throw new SemaException("member reference base type '" + base.type().spelling()
                    + "' is not a structure or union", at);
        }
        Layout layout = record.tag().layout().orElseThrow(
                () -> new SemaException("member access into incomplete type '" + record.spelling() + "'", at));
        Layout.Member member = layout.member(e.name().text).orElseThrow(
                () -> new SemaException("no member named '" + e.name().text + "' in '" + record.spelling() + "'", e.name()));
        CType type = types.plusQuals(member.type(), record.quals());
        return new TExpr.Member(base, member, type, e.name());
    }

    // A struct/union rvalue gets an anonymous automatic object to live in
    // (6.2.4p8), listed among the function's locals so lowering can
    // allocate it. Not created for unevaluated operands.
    private Lvalue materialize(Rvalue value, Token at) {
        var name = new Token(TokenType.IDENTIFIER, "<temp" + nextId + ">", at.line, at.column);
        Symbol symbol = Symbol.anonymousAutomatic(nextId++, name, 1);
        symbol.setType(types.unqualified(value.type()));
        if (unevaluated == 0) {
            if (locals == null) throw new SemaException("a temporary object is not allowed here", at);
            locals.add(symbol);
        }
        return new TExpr.Materialize(value, symbol, symbol.type(), at);
    }

    private void requireCompleteObjectPointer(Token op, Rvalue p) {
        CType target = ((CType.Pointer) p.type()).target();
        if (target.isFunction() || !target.isComplete()) {
            throw new SemaException("arithmetic on a pointer to " + (target.isFunction() ? "a function"
                    : "an incomplete type") + " ('" + p.type().spelling() + "')", op);
        }
    }

    // *p: an lvalue of the pointee type, or a function designator for a
    // pointer to function (6.5.4.2p4).
    private TExpr deref(Token op, Rvalue p) {
        if (!(p.type() instanceof CType.Pointer pointer)) {
            throw new SemaException("indirection requires a pointer operand ('" + p.type().spelling() + "')", op);
        }
        CType target = pointer.target();
        if (target.isFunction()) return new TExpr.FuncDeref(p, target, op);
        if (target.isVoid()) throw new SemaException("cannot dereference a pointer to void", op);
        return new TExpr.Deref(p, target, op);
    }

    // &x: neither & nor * is evaluated in &*p, whose result is p (6.5.4.2p3);
    // &f on a function is its decay.
    private TExpr addressOf(Token op, TExpr x) {
        if (isBitField(x)) throw new SemaException("cannot take the address of a bit-field", op);
        if (isTemporaryMember(x)) throw new SemaException("cannot take the address of a member of a temporary object", op);
        if (x instanceof TExpr.Deref d) return d.pointer();
        if (x instanceof TExpr.FunctionDesignator fd) return rvalue(fd);
        if (x instanceof TExpr.Lvalue lv) return new TExpr.AddrOf(lv, types.pointer(lv.type()), op);
        throw new SemaException("cannot take the address of an rvalue", op);
    }

    private static SemaException invalidOperands(Token op, Rvalue l, Rvalue r) {
        return new SemaException("invalid operands to binary " + op.text + " ('" + l.type().spelling()
                + "' and '" + r.type().spelling() + "')", op);
    }

    // ---- unary operators (6.5.4) --------------------------------------------------------------

    private TExpr unary(Expr.Unary e) {
        Token op = e.op();
        switch (op.text) {
            case "+", "-", "~", "!" -> {
                Rvalue x = rvalue(type(e.operand()));
                return switch (op.text) {
                    case "+" -> requireArithmetic(op, x) ? promote(x) : null;
                    case "-" -> requireArithmetic(op, x) ? new TExpr.Neg(promote(x), promotedType(x), op) : null;
                    case "~" -> {
                        if (!x.type().isInteger()) throw invalidOperand(op, x);
                        yield new TExpr.BitNot(promote(x), promotedType(x), op);
                    }
                    default -> {
                        if (!x.type().isScalar()) throw invalidOperand(op, x);
                        yield new TExpr.Not(toBool(x), types.int_(), op);
                    }
                };
            }
            case "sizeof", "_Countof" -> {
                // The operand is not evaluated and, being under sizeof, an
                // array does not decay (6.3.3.1p3, 6.5.4.4p2).
                TExpr operand = typeUnevaluated(e.operand());
                if (isBitField(operand)) throw new SemaException("sizeof applied to a bit-field", op);
                return sizeOf(op, operand.type());
            }
            case "*" -> {
                return deref(op, rvalue(type(e.operand())));
            }
            case "&" -> {
                return addressOf(op, type(e.operand()));
            }
            default -> throw unsupported("unary operator " + op.text, op);
        }
    }

    private static boolean requireArithmetic(Token op, Rvalue x) {
        if (!x.type().isArithmetic()) throw invalidOperand(op, x);
        return true;
    }

    private static SemaException invalidOperand(Token op, Rvalue x) {
        return new SemaException("invalid operand to unary " + op.text + " ('" + x.type().spelling() + "')", op);
    }

    // ---- sizeof, alignof, _Countof (6.5.4.4 - 6.5.4.5) ------------------------------------------

    // Each yields a size_t constant: the object size, the alignment, or
    // the element count of an array; none applies to a function or an
    // incomplete type.
    private TExpr sizeOf(Token op, CType t) {
        if (t.isFunction()) throw new SemaException(op.text + " of a function type", op);
        if (op.text.equals("_Countof")) {
            if (!(t instanceof CType.Array a)) throw new SemaException("_Countof requires an array type", op);
            if (!a.isComplete()) throw new SemaException("_Countof of an incomplete array type", op);
            return new TExpr.IntConst(a.size().getAsLong(), types.sizeT(), op);
        }
        if (!t.isComplete()) throw new SemaException(op.text + " of an incomplete type '" + t.spelling() + "'", op);
        long value = op.text.equals("alignof") ? types.align(t) : types.size(t);
        return new TExpr.IntConst(value, types.sizeT(), op);
    }

    // ---- conditional and comma (6.5.16, 6.5.18) ------------------------------------------------

    private TExpr conditional(Expr.Conditional e) {
        Rvalue c = rvalue(type(e.condition()));
        if (!c.type().isScalar()) {
            throw new SemaException("condition of ?: must be scalar ('" + c.type().spelling() + "')", e.question());
        }
        Rvalue t = rvalue(type(e.thenExpr()));
        Rvalue f = rvalue(type(e.elseExpr()));
        CType result;
        if (t.type().isArithmetic() && f.type().isArithmetic()) {
            result = types.usualArithmetic(promotedType(t), promotedType(f));
        } else if (t.type().isVoid() && f.type().isVoid()) {
            result = types.void_();
        } else if (t.type().isVoid() || f.type().isVoid()) {
            // One void arm is GNU's extension: the other value is discarded.
            return new TExpr.Cond(toBool(c), toVoid(t), toVoid(f), types.void_(), e.question());
        } else if (t.type().isRecord() && t.type() == f.type()) {
            // Both arms have the same structure or union type (6.5.16p3).
            result = t.type();
        } else if (commonPointerType(t, f, true) != null) {
            result = commonPointerType(t, f, true);
        } else {
            throw unsupported("?: with operands '" + t.type().spelling() + "' and '" + f.type().spelling() + "'",
                    e.question());
        }
        return new TExpr.Cond(toBool(c), convert(t, result), convert(f, result), result, e.question());
    }

    // ({ ... }), GNU's statement expression: the block, then the value of
    // its last expression statement, or void when there is none.
    private TExpr stmtExpr(Expr.StmtExpr e) {
        if (locals == null || blocks == null) {
            throw new SemaException("a statement expression is only allowed inside a function", e.paren());
        }
        TStmt.Block block = blocks.apply(e.body());
        List<TStmt> items = block.items();
        Optional<Rvalue> value = Optional.empty();
        if (!items.isEmpty() && items.get(items.size() - 1) instanceof TStmt.ExprStmt last) {
            value = Optional.of(last.expr());
            items = items.subList(0, items.size() - 1);
        }
        CType type = value.isPresent() ? value.get().type() : types.void_();
        return new TExpr.StmtExpr(new TStmt.Block(List.copyOf(items), block.token()), value, type, e.paren());
    }

    private TExpr comma(Expr.Comma e) {
        Rvalue left = toVoid(rvalue(type(e.left())));
        Rvalue right = rvalue(type(e.right()));
        return new TExpr.Comma(left, right, right.type(), e.comma());
    }

    // ---- the rules of 6.3 -------------------------------------------------------------------

    /**
     * Lvalue conversion, array decay and function decay (6.3.3.1p2-4): an
     * lvalue becomes the value it holds with qualifiers dropped, an array
     * a pointer to its first element, a function designator a pointer to
     * the function. An rvalue is returned unchanged.
     */
    Rvalue rvalue(TExpr x) {
        if (x instanceof Rvalue r) return r;
        if (x instanceof Lvalue lv) {
            if (lv.type() instanceof CType.Array a) {
                return new TExpr.ArrayDecay(lv, types.pointer(a.element()), x.token());
            }
            return new TExpr.LvalueToRvalue(lv, types.unqualified(lv.type()), x.token());
        }
        // FunctionDecay(FuncDeref(p)) is p: (*f)(x) types like f(x) (6.5.3.3).
        if (x instanceof TExpr.FuncDeref fd) return fd.pointer();
        var fd = (TExpr.FunctionDesignator) x;
        return new TExpr.FunctionDecay(fd, types.pointer(fd.type()), x.token());
    }

    /** Integer promotions (6.3.2.1) applied to a value. */
    Rvalue promote(Rvalue x) {
        return convert(x, promotedType(x));
    }

    /**
     * The type {@code x} promotes to. A value read from a bit-field
     * promotes by its width, not its declared type (6.3.2.1p2): an
     * {@code unsigned int : 3} becomes {@code int}.
     */
    CType promotedType(Rvalue x) {
        Layout.BitField bits = bitFieldOf(x);
        if (bits != null && x.type() instanceof CType.Int i && i.rank().compareTo(CType.Int.Rank.INT) <= 0) {
            boolean fits = types.isSigned(i) || bits.width() < types.width(types.int_());
            return fits ? types.int_() : types.uint();
        }
        return types.promote(x.type());
    }

    private static Layout.@Nullable BitField bitFieldOf(Rvalue x) {
        if (x instanceof TExpr.LvalueToRvalue rv && rv.operand() instanceof TExpr.Member m) {
            return m.member().bits().orElse(null);
        }
        return null;
    }

    private static boolean isBitField(TExpr x) {
        return x instanceof TExpr.Member m && m.member().bits().isPresent();
    }

    // s.m on a struct rvalue is not an lvalue (6.5.3.4p3): the member of
    // the temporary the value was stored in may be read, but a store into
    // it or its address would outlive nothing (6.2.4p8). The tree gives
    // Member an Lvalue base regardless, so the rvalue-ness is checked here.
    private static boolean isTemporaryMember(TExpr x) {
        while (x instanceof TExpr.Member m) x = m.base();
        return x instanceof TExpr.Materialize;
    }

    Rvalue toBool(Rvalue x) {
        return convert(x, types.bool_());
    }

    Rvalue toVoid(Rvalue x) {
        return convert(x, types.void_());
    }

    /** {@code x} converted to {@code to}: itself when the type already matches, else one conversion node. */
    Rvalue convert(Rvalue x, CType to) {
        CType from = x.type();
        if (from == to) return x;
        if (to.isVoid()) return new TExpr.ToVoid(x, to, x.token());
        // Conversion to bool is a comparison against zero (6.3.2.2p1), not a
        // truncation, so it precedes the integer case.
        if (to.isBool() && from.isScalar()) return new TExpr.ToBool(x, to, x.token());
        if (to.isPointer()) {
            if (isNullish(x)) return new TExpr.NullToPtr(x, to, x.token());
            if (from.isPointer()) return new TExpr.PtrToPtr(x, to, x.token());
            if (from.isInteger()) return new TExpr.IntToPtr(x, to, x.token());
        }
        if (from.isPointer() && to.isInteger()) return new TExpr.PtrToInt(x, to, x.token());
        if (from.isInteger() && to.isInteger()) return new TExpr.IntToInt(x, to, x.token());
        if (from.isInteger() && to.isFloating()) return new TExpr.IntToFloat(x, to, x.token());
        if (from.isFloating() && to.isInteger()) return new TExpr.FloatToInt(x, to, x.token());
        if (from.isFloating() && to.isFloating()) return new TExpr.FloatToFloat(x, to, x.token());
        throw unsupported("conversion from '" + from.spelling() + "' to '" + to.spelling() + "'", x.token());
    }

    // ---- helpers -------------------------------------------------------------------------------

    static Token tokenOf(Expr e) {
        if (e instanceof Expr.Identifier i) return i.name();
        if (e instanceof Expr.Literal l) return l.token();
        if (e instanceof Expr.StringLiteral s) return s.parts().get(0);
        if (e instanceof Expr.Generic g) return g.keyword();
        if (e instanceof Expr.Index i) return i.bracket();
        if (e instanceof Expr.Call c) return c.paren();
        if (e instanceof Expr.Member m) return m.op();
        if (e instanceof Expr.Postfix p) return p.op();
        if (e instanceof Expr.CompoundLiteral c) return c.paren();
        if (e instanceof Expr.Unary u) return u.op();
        if (e instanceof Expr.TypeOperator t) return t.op();
        if (e instanceof Expr.StaticAssertion s) return s.keyword();
        if (e instanceof Expr.Cast c) return c.paren();
        if (e instanceof Expr.Binary b) return b.op();
        if (e instanceof Expr.Conditional c) return c.question();
        if (e instanceof Expr.Assign a) return a.op();
        if (e instanceof Expr.Comma c) return c.comma();
        throw new IllegalStateException(e.toString());
    }

    private static SemaException unsupported(String what, Token at) {
        return new SemaException(what + " not supported yet", at);
    }
}

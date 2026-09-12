package org.jbm.mycc.cc.sema;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.parse.ast.Expr;
import org.jbm.mycc.cc.parse.ast.Initializer;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.sema.tast.StringData;
import org.jbm.mycc.cc.sema.tast.TExpr;
import org.jbm.mycc.cc.sema.tast.TExpr.Rvalue;
import org.jbm.mycc.cc.sema.tast.TInit;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Layout;
import org.jbm.mycc.cc.sema.types.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Initialization (C2y 6.7.11): turns an initializer for an object of a
 * given type into a {@link TInit}, a flat list of (offset, value) items.
 * A braced list is walked with a cursor over the object's subobjects
 * (array elements, record slots); designators move the cursor, a brace
 * around a subobject's initializer starts a nested walk, and an
 * expression where a subobject of aggregate type is expected initializes
 * that subobject's own subobjects from the same list (brace elision). A
 * string literal initializes an array of matching character type
 * element by element. The output is proportional to the source, not to
 * the object: subobjects with no item stay zero.
 */
final class Initializers {

    private final Types types;
    private final ExprTyper exprs;
    private final ConstEval constEval;

    /** The result: the object's type, completed from the initializer if it was an incomplete array, and the items. */
    record Result(CType type, TInit init) {
    }

    Initializers(@NonNull Types types, @NonNull ExprTyper exprs, @NonNull ConstEval constEval) {
        this.types = types;
        this.exprs = exprs;
        this.constEval = constEval;
    }

    Result normalize(@NonNull Initializer init, @NonNull CType type, @NonNull Token at) {
        var out = new ArrayList<TInit.Item>();
        CType completed = initObject(type, init, 0, at, out);
        return new Result(completed, new TInit(out));
    }

    // ---- one object, one initializer ----------------------------------------------------------

    // Initializes an object of type t at baseOffset from a whole
    // initializer; returns t, or the completed array type.
    private CType initObject(CType t, Initializer init, long baseOffset, Token at, List<TInit.Item> out) {
        return initObject(t, init, baseOffset, at, out, Optional.empty());
    }

    private CType initObject(CType t, Initializer init, long baseOffset, Token at, List<TInit.Item> out,
                             Optional<Layout.BitField> bits) {
        if (init instanceof Initializer.Expression e) {
            TExpr typed = exprs.type(e.expr());
            if (t.isArray()) {
                StringData str = exprs.stringLiteral(typed).orElse(null);
                if (str != null && isCharacterArrayFor(t, str)) return initString((CType.Array) t, str, baseOffset, at, out);
                throw new SemaException("array initializer must be a brace-enclosed list" + (str != null
                        ? " or a string literal of matching character type" : ""), at);
            }
            Rvalue value = exprs.assignConvert(exprs.rvalue(typed), types.unqualified(t), at, "initializing");
            out.add(new TInit.Item(baseOffset, value, bits));
            return t;
        }
        var braced = (Initializer.Braced) init;
        if (t.isScalar()) return initScalarBraced(t, braced, baseOffset, out, bits);
        if (!t.isArray() && !t.isRecord()) {
            throw new SemaException("cannot initialize a value of type '" + t.spelling() + "'", braced.brace());
        }
        if (!t.isComplete() && !(t instanceof CType.Array)) {
            throw new SemaException("initializing an incomplete type '" + t.spelling() + "'", braced.brace());
        }
        // { "text" } for a character array is the string form (6.7.11p15).
        if (t.isArray() && braced.items().size() == 1 && braced.items().get(0).designators().isEmpty()
                && braced.items().get(0).initializer() instanceof Initializer.Expression e) {
            StringData str = exprs.stringLiteral(exprs.typeUnevaluated(e.expr())).orElse(null);
            if (str != null && isCharacterArrayFor(t, str)) {
                exprs.type(e.expr());
                return initString((CType.Array) t, str, baseOffset, braced.brace(), out);
            }
        }
        var walk = new Walk(t, baseOffset, braced.items(), true, true, out);
        int consumed = walk.fill(0);
        if (consumed < braced.items().size()) throw excess(braced.items().get(consumed), braced.brace());
        return walk.completedType();
    }

    // A scalar in braces (6.7.11p11): one initializer, itself possibly braced.
    private CType initScalarBraced(CType t, Initializer.Braced braced, long baseOffset, List<TInit.Item> out,
                                   Optional<Layout.BitField> bits) {
        if (braced.items().isEmpty()) return t;
        if (braced.items().size() > 1) throw excess(braced.items().get(1), braced.brace());
        var item = braced.items().get(0);
        if (!item.designators().isEmpty()) throw new SemaException("designator in initializer for a scalar", braced.brace());
        return initObject(t, item.initializer(), baseOffset, braced.brace(), out, bits);
    }

    private static SemaException excess(Initializer.Item item, Token brace) {
        return new SemaException("excess elements in initializer", brace);
    }

    // ---- strings (6.7.11p15) ---------------------------------------------------------------------

    private boolean isCharacterArrayFor(CType t, StringData str) {
        CType element = types.unqualified(((CType.Array) t).element());
        CType strElement = ((CType.Array) str.symbol().type()).element();
        if (element instanceof CType.Int i && i.rank() == CType.Int.Rank.CHAR) {
            return strElement instanceof CType.Int si && si.rank() == CType.Int.Rank.CHAR;
        }
        return element == strElement;
    }

    // The code units become the elements; the terminating null is
    // dropped when the array has exactly no room for it (6.7.11p15).
    private CType initString(CType.Array t, StringData str, long baseOffset, Token at, List<TInit.Item> out) {
        int[] units = str.units();
        long size;
        if (t.isComplete()) {
            size = t.size().getAsLong();
            if (units.length - 1 > size) throw new SemaException("initializer string is too long for the array", at);
        } else {
            size = units.length;
        }
        CType element = t.element();
        long elementSize = types.size(element);
        int n = (int) Math.min(units.length, size);
        for (int i = 0; i < n; i++) {
            if (units[i] == 0 && i == units.length - 1) break;
            long v = units[i];
            if (types.isSigned(element)) v = (v << (64 - types.width(element))) >> (64 - types.width(element));
            out.add(new TInit.Item(baseOffset + i * elementSize, new TExpr.IntConst(v, types.unqualified(element), str.symbol().declaredAt)));
        }
        exprs.dropString(str);
        return t.isComplete() ? t : types.array(element, size);
    }

    // ---- a braced list over an aggregate ----------------------------------------------------------

    /**
     * One brace level over an aggregate, or the elided level of a
     * subaggregate. A real brace pair ({@code bracePair}) rejects excess
     * items and is what designators refer to (6.7.11p18); the descent
     * into an anonymous member also accepts designators, since its
     * members are designatable as if they were the enclosing record's,
     * but hands excess items back; an elided level does neither.
     */
    private final class Walk {
        private final CType type;
        private final long base;
        private final List<Initializer.Item> items;
        private final boolean bracePair;
        private final boolean acceptsDesignators;
        private final List<TInit.Item> out;

        private final @org.jetbrains.annotations.Nullable Layout layout;   // records
        private final @org.jetbrains.annotations.Nullable CType element;   // arrays
        private final long elementSize;
        private final long count;      // slots or elements; Long.MAX_VALUE for an incomplete array
        private long cursor;
        private long maxIndex = -1;    // for completing an incomplete array

        Walk(CType type, long base, List<Initializer.Item> items, boolean bracePair, boolean acceptsDesignators,
             List<TInit.Item> out) {
            this.type = type;
            this.base = base;
            this.items = items;
            this.bracePair = bracePair;
            this.acceptsDesignators = acceptsDesignators;
            this.out = out;
            if (type instanceof CType.Record r) {
                layout = r.tag().layout().orElseThrow();
                element = null;
                elementSize = 0;
                count = r.tag().isUnion() ? 1 : layout.slots().size();
            } else {
                var a = (CType.Array) type;
                layout = null;
                element = a.element();
                elementSize = types.size(element);
                count = a.isComplete() ? a.size().getAsLong() : Long.MAX_VALUE;
            }
        }

        CType completedType() {
            if (type instanceof CType.Array a && !a.isComplete()) {
                if (maxIndex < 0) throw new SemaException("array of unknown size has an empty initializer", tokenOfList());
                return types.array(a.element(), maxIndex + 1);
            }
            return type;
        }

        private Token tokenOfList() {
            return items.isEmpty() ? new Token(CppTokenizer.TokenType.PUNCTUATOR, "{", 0, 0)
                    : ExprTyper.tokenOf(firstExpr(items.get(0).initializer()));
        }

        /** Consumes items from {@code pos}; returns the index of the first it did not take. */
        int fill(int pos) {
            while (pos < items.size()) {
                Initializer.Item item = items.get(pos);
                if (!item.designators().isEmpty()) {
                    if (!acceptsDesignators) return pos;
                    if (!resolves(item.designators().get(0))) {
                        if (!bracePair) return pos;
                        throw unresolvable(item.designators().get(0));
                    }
                    pos = designated(item, pos);
                    continue;
                }
                if (cursor >= count) {
                    if (bracePair) throw excess(item, ExprTyper.tokenOf(firstExpr(item.initializer())));
                    return pos;
                }
                pos = subobject(cursor, item, pos, List.of());
                cursor++;
            }
            return pos;
        }

        private SemaException unresolvable(Initializer.Designator d) {
            if (d instanceof Initializer.MemberDesignator m) {
                return new SemaException(layout == null ? "member designator in initializer for an array"
                        : "no member named '" + m.name().text + "' in '" + type.spelling() + "'", m.name());
            }
            return new SemaException("array designator in initializer for a structure or union",
                    ((Initializer.ArrayDesignator) d).bracket());
        }

        // Whether the first designator names something at this level.
        private boolean resolves(Initializer.Designator d) {
            if (d instanceof Initializer.MemberDesignator m) return layout != null && layout.member(m.name().text).isPresent();
            return element != null;
        }

        // A designated item: move the cursor, initialize, continue after it.
        private int designated(Initializer.Item item, int pos) {
            Initializer.Designator first = item.designators().get(0);
            List<Initializer.Designator> rest = item.designators().subList(1, item.designators().size());
            if (first instanceof Initializer.MemberDesignator m) {
                String name = m.name().text;
                int slot = slotIndexOf(name);
                Layout.Member s = layout.slots().get(slot);
                if (s.isAnonymous()) {
                    // The name is inside an anonymous member: descend with
                    // the designators intact, at the anonymous member's level.
                    cursor = slot;
                    var inner = new Walk(s.type(), base + s.offset(), items, false, true, out);
                    int next = inner.fill(pos);
                    cursor = slot + 1;
                    return next;
                }
                cursor = slot;
            } else {
                var a = (Initializer.ArrayDesignator) first;
                if (element == null) throw new SemaException("array designator in initializer for a structure or union", a.bracket());
                long index = designatedIndex(a.index(), a.bracket());
                if (a.last().isPresent()) {
                    // GNU's [first ... last]: the item initializes each
                    // element of the range in turn, and the walk goes on
                    // after the last.
                    long last = designatedIndex(a.last().get(), a.bracket());
                    if (last < index) throw new SemaException("empty index range in initializer", a.bracket());
                    int next = pos + 1;
                    for (long i = index; i <= last; i++) {
                        cursor = i;
                        next = subobject(cursor, item, pos, rest);
                    }
                    cursor++;
                    return next;
                }
                cursor = index;
            }
            int next = subobject(cursor, item, pos, rest);
            cursor++;
            return next;
        }

        private long designatedIndex(Expr expr, Token bracket) {
            long index = constEval.requireInteger(exprs.rvalue(exprs.type(expr)), bracket, "array designator index");
            if (index < 0) throw new SemaException("array designator index is negative", bracket);
            if (index >= count) throw new SemaException("array designator index " + index + " exceeds the array bounds", bracket);
            return index;
        }

        private int slotIndexOf(String name) {
            for (int i = 0; i < layout.slots().size(); i++) {
                Layout.Member s = layout.slots().get(i);
                if (s.name().equals(name)) return i;
                if (s.isAnonymous() && ((CType.Record) s.type()).tag().layout().orElseThrow().member(name).isPresent()) return i;
            }
            throw new IllegalStateException(name);
        }

        /**
         * Initializes subobject {@code index} from {@code item}, whose
         * remaining designators (after the one that selected this
         * subobject) apply within it. Returns the next item position.
         */
        private int subobject(long index, Initializer.Item item, int pos, List<Initializer.Designator> rest) {
            CType subType;
            long subOffset;
            Optional<Layout.BitField> bits = Optional.empty();
            if (layout != null) {
                Layout.Member s = layout.slots().get((int) index);
                subType = s.type();
                subOffset = base + s.offset();
                bits = s.bits();
            } else {
                subType = element;
                subOffset = base + index * elementSize;
                if (index > maxIndex) maxIndex = index;
            }
            if (!rest.isEmpty()) {
                // More designators: they select within this subobject, which
                // must be an aggregate; the walk starts at the designated
                // place and goes on with the following items.
                if (!subType.isArray() && !subType.isRecord()) {
                    throw new SemaException("designator into a non-aggregate subobject", ExprTyper.tokenOf(firstExpr(item.initializer())));
                }
                var stripped = new ArrayList<>(items);
                stripped.set(pos, new Initializer.Item(rest, item.initializer()));
                var view = new Walk(subType, subOffset, stripped, false, false, out);
                if (!view.resolves(rest.get(0))) throw view.unresolvable(rest.get(0));
                // Initialization continues forward from the designated
                // subobject (6.7.11p20) until this subaggregate is full or
                // another designator appears, then returns to this level.
                int next = view.designated(stripped.get(pos), pos);
                return view.fill(next);
            }
            Initializer init = item.initializer();
            if (init instanceof Initializer.Braced || subType.isScalar()) {
                initObject(subType, init, subOffset, ExprTyper.tokenOf(firstExpr(init)), out, bits);
                return pos + 1;
            }
            // An expression for an aggregate subobject: its own value if it
            // has the subobject's type or is a string for a character array,
            // otherwise brace elision from this list (6.7.11p21).
            Expr expr = ((Initializer.Expression) init).expr();
            TExpr typed = exprs.typeUnevaluated(expr);
            StringData str = exprs.stringLiteral(typed).orElse(null);
            boolean whole = subType.isRecord() && types.unqualified(typed.type()) == subType
                    || str != null && subType.isArray() && isCharacterArrayFor(subType, str);
            if (whole) {
                initObject(subType, init, subOffset, ExprTyper.tokenOf(expr), out);
                return pos + 1;
            }
            if (!subType.isComplete()) throw new SemaException("initializing an incomplete type '" + subType.spelling() + "'", ExprTyper.tokenOf(expr));
            // The item that selected this subobject by designator is, for
            // the elided walk, an ordinary first element.
            List<Initializer.Item> view = items;
            if (!item.designators().isEmpty()) {
                var stripped = new ArrayList<>(items);
                stripped.set(pos, new Initializer.Item(List.of(), item.initializer()));
                view = stripped;
            }
            var inner = new Walk(subType, subOffset, view, false, false, out);
            int next = inner.fill(pos);
            if (next == pos) throw new SemaException("invalid initializer for '" + subType.spelling() + "'", ExprTyper.tokenOf(expr));
            return next;
        }
    }

    private static Expr firstExpr(Initializer init) {
        while (init instanceof Initializer.Braced b) {
            if (b.items().isEmpty()) return new Expr.Literal(b.brace());
            init = b.items().get(0).initializer();
        }
        return ((Initializer.Expression) init).expr();
    }
}

package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.lower.tac.Function;
import org.jbm.cc.lower.tac.Global;
import org.jbm.cc.lower.tac.Instr;
import org.jbm.cc.lower.tac.Linkage;
import org.jbm.cc.lower.tac.Module;
import org.jbm.cc.lower.tac.Operand;
import org.jbm.cc.lower.tac.TargetDesc;
import org.jbm.cc.lower.tac.Type;
import org.jbm.cc.lower.tac.Var;
import org.jbm.cc.sema.tast.StringData;
import org.jbm.cc.sema.tast.TExpr;
import org.jbm.cc.sema.tast.TFunction;
import org.jbm.cc.sema.tast.TInit;
import org.jbm.cc.sema.tast.TUnit;
import org.jbm.cc.sema.types.CType;
import org.jbm.cc.sema.types.Types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The typed tree to the TAC ({@code docs/lower-plan.md}). */
public final class Lower {

    final Types types;
    final Module module;
    final TypeMap typeMap;
    final Names names;

    private Lower(Types types, TUnit unit) {
        this.types = types;
        this.module = new Module(TargetDesc.of(types.target()));
        this.typeMap = new TypeMap(types, module);
        this.names = new Names(unit);
    }

    // Functions called or taken the address of; the ones the unit does not define are declared.
    private final Set<Symbol> referenced = new LinkedHashSet<>();
    private final Set<Symbol> defined = new HashSet<>();

    public static Module lower(@NonNull TUnit unit, @NonNull Types types) {
        var lower = new Lower(types, unit);
        for (StringData s : unit.strings()) lower.string(s);
        for (TUnit.Global g : unit.globals()) lower.global(g);
        for (TFunction f : unit.functions()) lower.defined.add(f.symbol());
        for (TFunction f : unit.functions()) lower.function(f);
        for (Symbol f : lower.referenced) {
            if (!lower.defined.contains(f)) {
                lower.module.funcDecls.add(new Module.FuncDecl(lower.names.of(f), lower.typeMap.func((CType.Function) f.type())));
            }
        }
        return lower.module;
    }

    void referenced(@NonNull Symbol function) {
        referenced.add(function);
    }

    private final Set<String> emittedStrings = new HashSet<>();

    // A string literal is a read-only internal global named by its
    // contents; two literals with the same units share one.
    private void string(StringData s) {
        String name = names.of(s.symbol());
        if (!emittedStrings.add(name)) return;
        CType.Array array = (CType.Array) s.symbol().type();
        Type type = typeMap.of(array);
        var items = new ArrayList<Global.Item>();
        Type.Int element = typeMap.integer(array.element());
        if (element.width() == 8) {
            var bytes = new byte[s.units().length];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) s.units()[i];
            items.add(new Global.BytesItem(0, bytes));
        } else {
            long size = element.width() / 8;
            for (int i = 0; i < s.units().length; i++) items.add(new Global.IntItem(i * size, element, s.units()[i]));
        }
        module.globals.add(new Global(name, Linkage.INTERNAL, type, types.align(array), true, items));
    }

    private void global(TUnit.Global g) {
        Symbol s = g.symbol();
        Type type = typeMap.of(s.type());
        if (!g.isDefinition()) {
            module.globalDecls.add(new Module.GlobalDecl(names.of(s), type));
            return;
        }
        Linkage linkage = s.linkage() == Symbol.Linkage.EXTERNAL ? Linkage.EXTERNAL : Linkage.INTERNAL;
        List<Global.Item> items = g.init().map(this::items).orElse(null);
        module.globals.add(new Global(names.of(s), linkage, type, types.align(s.type()), s.type().quals().isConst(), items));
    }

    // A static initializer's items are already constants; each becomes the TAC item of its kind.
    private List<Global.Item> items(TInit init) {
        var out = new ArrayList<Global.Item>(init.items().size());
        for (TInit.Item item : init.items()) {
            TExpr.Rvalue v = item.value();
            if (item.bits().isPresent() && v instanceof TExpr.IntConst c) {
                var bits = item.bits().get();
                out.add(new Global.BitItem(item.offset(), bits.bitOffset(), bits.width(), c.value()));
            } else if (v instanceof TExpr.IntConst c) out.add(new Global.IntItem(item.offset(), typeMap.integer(c.type()), c.value()));
            else if (v instanceof TExpr.FloatConst c) out.add(new Global.FloatItem(item.offset(), (Type.Float) typeMap.of(c.type()), c.value()));
            else if (v instanceof TExpr.NullptrConst) out.add(new Global.IntItem(item.offset(), typeMap.integer(types.sizeT()), 0));
            else if (v instanceof TExpr.AddrConst a) {
                if (a.base().isPresent()) out.add(new Global.AddrItem(item.offset(), names.of(a.base().get()), a.offset()));
                else out.add(new Global.IntItem(item.offset(), typeMap.integer(types.sizeT()), a.offset()));
            } else throw new IllegalStateException("a static initializer item that is not a constant: " + v);
        }
        return out;
    }

    private void function(TFunction f) {
        CType.Function ctype = (CType.Function) f.symbol().type();
        Type.Func sig = typeMap.func(ctype);
        var localNames = new Names.Local();
        Map<Symbol, Var> vars = new IdentityHashMap<>();
        var params = new ArrayList<Var>();
        for (Symbol p : f.parameters()) {
            var v = new Var(localNames.of(p), typeMap.of(p.type()), p.type().quals().isVolatile());
            vars.put(p, v);
            params.add(v);
        }
        Linkage linkage = f.symbol().linkage() == Symbol.Linkage.INTERNAL ? Linkage.INTERNAL : Linkage.EXTERNAL;
        var fn = new Function(names.of(f.symbol()), linkage, sig, params);
        var b = new Builder(fn);
        for (Symbol l : f.locals()) vars.put(l, b.local(localNames.of(l), typeMap.of(l.type()), l.type().quals().isVolatile()));
        b.open(b.block("entry"));
        var exprs = new ExprLower(this, b, vars);
        new StmtLower(b, exprs, vars).lower(f.body());
        endOfBody(b, f, ctype.returnType());
        b.finish();
        module.functions.add(fn);
    }

    // The end of the body (lower-plan.md): ret for void, ret 0 for main,
    // a trap for any other non-void function that falls off the end.
    private void endOfBody(Builder b, TFunction f, CType returnType) {
        if (!b.isOpen()) return;
        Token at = f.body().token();
        if (returnType.isVoid()) b.emit(new Instr.Ret(null, at));
        else if (f.symbol().name.equals("main")) b.emit(new Instr.Ret(new Operand.IntImm(0), at));
        else b.emit(new Instr.Trap("end of non-void function", at));
    }
}

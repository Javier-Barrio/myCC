package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.tac.Block;
import org.jbm.cc.tac.Function;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Linkage;
import org.jbm.cc.tac.Module;
import org.jbm.cc.tac.Operand;
import org.jbm.cc.tac.TargetDesc;
import org.jbm.cc.tac.Type;
import org.jbm.cc.tac.Var;
import org.jbm.cc.tast.TFunction;
import org.jbm.cc.tast.TUnit;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

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

    public static Module lower(@NonNull TUnit unit, @NonNull Types types) {
        var lower = new Lower(types, unit);
        for (TFunction f : unit.functions()) lower.function(f);
        return lower.module;
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
        endOfBody(b, f, ctype.returnType());
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

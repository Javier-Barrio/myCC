package org.jbm.mycc.repl.vm.builtins;

import org.jbm.mycc.repl.vm.Builtin;
import org.jbm.mycc.repl.vm.VM;

import java.util.List;

public class Printf implements Builtin {
    @Override
    public VM.Value call(VM vm, List<VM.Value> args) {
        System.out.printf(args.get(0).toString(), args.subList(1, args.size()));
        return null;
    }
}

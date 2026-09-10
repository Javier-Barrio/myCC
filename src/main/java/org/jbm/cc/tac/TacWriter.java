package org.jbm.cc.tac;

import lombok.NonNull;

import java.nio.charset.StandardCharsets;

/** The text form of a module, one instruction per line, as {@code tac-plan.md} shows it. */
public final class TacWriter implements TacVisitor<String> {

    private TacWriter() {
    }

    public static String print(@NonNull Module m) {
        var w = new TacWriter();
        var sb = new StringBuilder();
        sb.append("target ").append(m.target.name()).append('\n');
        for (var s : m.structs) sb.append(w.struct(s)).append('\n');
        for (var g : m.globals) sb.append(w.global(g)).append('\n');
        for (var d : m.globalDecls) sb.append("declare @").append(d.name()).append(" : ").append(d.type().spelling()).append('\n');
        for (var d : m.funcDecls) sb.append("declare @").append(d.name()).append(d.sig().spelling()).append('\n');
        for (var f : m.functions) sb.append(w.function(f));
        return sb.toString();
    }

    public static String print(@NonNull Function f) {
        return new TacWriter().function(f);
    }

    public static String print(@NonNull Instr i) {
        return i.accept(new TacWriter());
    }

    private String struct(StructDef s) {
        var sb = new StringBuilder("type %").append(s.name()).append(" = {");
        for (int i = 0; i < s.members().size(); i++) {
            var m = s.members().get(i);
            sb.append(i > 0 ? ", " : " ").append(m.type().spelling()).append(" @").append(m.offset());
        }
        return sb.append(" } size ").append(s.size()).append(" align ").append(s.align()).toString();
    }

    private String global(Global g) {
        var sb = new StringBuilder("global ");
        if (g.linkage() == Linkage.INTERNAL) sb.append("internal ");
        sb.append('@').append(g.name()).append(" : ").append(g.type().spelling()).append(" align ").append(g.align());
        if (g.readonly()) sb.append(" readonly");
        if (g.init() != null) {
            sb.append(" = {");
            for (int i = 0; i < g.init().size(); i++) sb.append(i > 0 ? ", " : " ").append(item(g.init().get(i)));
            sb.append(" }");
        }
        return sb.toString();
    }

    private String item(Global.Item item) {
        String at = item.offset() + " : ";
        if (item instanceof Global.IntItem i) return at + i.type().spelling() + " " + i.value();
        if (item instanceof Global.FloatItem f) return at + f.type().spelling() + " " + f.value();
        if (item instanceof Global.AddrItem a) return at + "addr @" + a.name() + (a.addend() != 0 ? " + " + a.addend() : "");
        if (item instanceof Global.BitItem b) return at + b.bit() + "/" + b.width() + " : " + b.value();
        var b = (Global.BytesItem) item;
        return at + "bytes " + quote(b.bytes());
    }

    private static String quote(byte[] bytes) {
        var sb = new StringBuilder("\"");
        for (byte b : bytes) {
            int c = b & 0xff;
            if (c == '"' || c == '\\') sb.append('\\').append((char) c);
            else if (c >= 0x20 && c < 0x7f) sb.append((char) c);
            else sb.append(String.format("\\%02x", c));
        }
        return sb.append('"').toString();
    }

    private String function(Function f) {
        var sb = new StringBuilder("define ");
        if (f.linkage == Linkage.INTERNAL) sb.append("internal ");
        sb.append('@').append(f.name).append('(');
        for (int i = 0; i < f.params.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(f.params.get(i).type.spelling()).append(' ').append(f.params.get(i));
        }
        if (f.sig.variadic()) sb.append(f.params.isEmpty() ? "..." : ", ...");
        sb.append(") -> ").append(f.sig.ret().spelling()).append(" {\n");
        for (var v : f.locals) {
            sb.append("  ");
            if (v.isVolatile) sb.append("volatile ");
            sb.append(v.type.spelling()).append(' ').append(v).append('\n');
        }
        for (var b : f.blocks) {
            sb.append(b).append(":\n");
            for (var i : b.instrs) sb.append("  ").append(i.accept(this)).append('\n');
        }
        return sb.append("}\n").toString();
    }

    private static String op(Operand o) {
        if (o instanceof Var v) return v.toString();
        if (o instanceof Operand.IntImm i) return Long.toString(i.value());
        return Double.toString(((Operand.FloatImm) o).value());
    }

    private static String args(java.util.List<Operand> args) {
        var sb = new StringBuilder("(");
        for (int i = 0; i < args.size(); i++) sb.append(i > 0 ? ", " : "").append(op(args.get(i)));
        return sb.append(')').toString();
    }

    private static String mod(Type mod) {
        if (mod == null) {
            return "";
        }
        if (mod instanceof Type.Float f) {
            return "." + f.width();
        }
        Type.Int i = (Type.Int) mod;
        return "." + (i.signed() ? "s" : "u") + i.width();
    }

    @Override
    public String visit(Instr.Mov i) {
        return "mov" + mod(i.mod()) + " " + i.dst() + ", " + op(i.src());
    }

    @Override
    public String visit(Instr.AddrOfVar i) {
        return i.dst() + " = addrof " + i.var();
    }

    @Override
    public String visit(Instr.AddrOfGlobal i) {
        return i.dst() + " = addrof @" + i.name();
    }

    @Override
    public String visit(Instr.Bin i) {
        return i.dst() + " = " + i.op().spelling() + mod(i.mod()) + " " + op(i.a()) + ", " + op(i.b());
    }

    @Override
    public String visit(Instr.Cmp i) {
        return i.dst() + " = " + i.op().spelling() + " " + op(i.a()) + ", " + op(i.b());
    }

    @Override
    public String visit(Instr.Cvt i) {
        return i.dst() + " = " + i.op().spelling() + "." + i.precision().width() + " " + i.src();
    }

    @Override
    public String visit(Instr.Load i) {
        return i.dst() + " = load." + i.ext().prefix + i.width() + " " + i.ptr() + (i.isVolatile() ? " volatile" : "");
    }

    @Override
    public String visit(Instr.Store i) {
        String width;
        if (i.type() instanceof Type.Int t) {
            width = Integer.toString(t.width());
        } else if (i.type() instanceof Type.Float f) {
            width = "f" + f.width();
        } else if (i.type() instanceof Type.Ptr) {
            width = "64";
        } else {
            width = i.type().spelling();
        }
        return "store." + width + " " + i.ptr() + ", " + op(i.value()) + (i.isVolatile() ? " volatile" : "");
    }

    @Override
    public String visit(Instr.Br i) {
        return "br " + i.target();
    }

    @Override
    public String visit(Instr.CondBr i) {
        return "condbr " + op(i.cond()) + ", " + i.then() + ", " + i.otherwise();
    }

    @Override
    public String visit(Instr.Switch i) {
        var sb = new StringBuilder("switch ").append(i.value()).append(", ").append(i.dflt()).append(", [");
        for (int k = 0; k < i.cases().size(); k++) {
            var c = i.cases().get(k);
            sb.append(k > 0 ? ", " : " ").append(c.value()).append(" -> ").append(c.target());
        }
        return sb.append(i.cases().isEmpty() ? "]" : " ]").toString();
    }

    @Override
    public String visit(Instr.Ret i) {
        return i.value() == null ? "ret" : "ret " + op(i.value());
    }

    @Override
    public String visit(Instr.Trap i) {
        return "trap " + quote(i.message().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String visit(Instr.Call i) {
        return (i.dst() != null ? i.dst() + " = " : "") + "call " + i.sig().spelling() + " @" + i.callee() + args(i.args())
                + (i.into() != null ? " into " + i.into() : "");
    }

    @Override
    public String visit(Instr.ICall i) {
        return (i.dst() != null ? i.dst() + " = " : "") + "icall " + i.sig().spelling() + " " + i.callee() + args(i.args())
                + (i.into() != null ? " into " + i.into() : "");
    }
}

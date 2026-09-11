package org.jbm.mycc.cc.codegen;

/** The assembly text: labels at the margin, instructions and directives indented, comments only when annotating. */
public final class Asm {

    private final StringBuilder sb = new StringBuilder();
    private final boolean annotate;

    public Asm(boolean annotate) {
        this.annotate = annotate;
    }

    public void label(String name) {
        sb.append(name).append(":\n");
    }

    public void directive(String text) {
        sb.append("  ").append(text).append('\n');
    }

    public void insn(String mnemonic, String... operands) {
        sb.append("  ").append(mnemonic);
        if (operands.length > 0) {
            sb.append(' ').append(String.join(", ", operands));
        }
        sb.append('\n');
    }

    /** A comment line, when annotating. */
    public void comment(String text) {
        if (annotate) {
            sb.append("  # ").append(text).append('\n');
        }
    }

    /** A comment at the margin, when annotating. */
    public void note(String text) {
        if (annotate) {
            sb.append("# ").append(text).append('\n');
        }
    }

    public String text() {
        return sb.toString();
    }
}

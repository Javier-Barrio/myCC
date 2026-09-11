package org.jbm.mycc.cc.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the assembly IR: a list of {@link Item}s in order. Comments
 * are recorded only when annotating; {@link AttPrinter} turns the list
 * into text.
 */
public final class Asm {

    private final List<Item> items = new ArrayList<>();
    private final boolean annotate;

    public Asm(boolean annotate) {
        this.annotate = annotate;
    }

    public void label(String name) {
        items.add(new Item.Label(name));
    }

    public void insn(String mnemonic, Operand... operands) {
        items.add(new Item.Insn(mnemonic, List.of(operands)));
    }

    public void section(String name) {
        items.add(new Item.Section(name));
    }

    public void global(String name) {
        items.add(new Item.Global(name));
    }

    public void align(int bytes) {
        items.add(new Item.Align(bytes));
    }

    public void bytes(byte[] bytes) {
        items.add(new Item.Bytes(bytes));
    }

    public void address(Operand.Sym symbol) {
        items.add(new Item.Address(symbol));
    }

    public void word(long value, String comment) {
        items.add(new Item.Word(value, comment));
    }

    public void zero(long bytes) {
        items.add(new Item.Zero(bytes));
    }

    public void comment(String text) {
        if (annotate) {
            items.add(new Item.Comment(text));
        }
    }

    public void note(String text) {
        if (annotate) {
            items.add(new Item.Note(text));
        }
    }

    /** The IR built so far. */
    public List<Item> items() {
        return List.copyOf(items);
    }

    /** The IR as AT&T text. */
    public String text() {
        return AttPrinter.print(items);
    }
}

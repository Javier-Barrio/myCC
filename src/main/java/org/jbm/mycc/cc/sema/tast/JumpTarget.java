package org.jbm.mycc.cc.sema.tast;

import lombok.NonNull;

/**
 * The identity of a place control can jump to: a labeled statement, a
 * loop (for {@code break} and {@code continue}), a switch (for
 * {@code break}), or a case/default label. Jumps hold a reference to
 * the same object the target statement holds, so the tree needs no
 * name lookup and no side table; {@code name} is only for the printer.
 */
public final class JumpTarget {

    public final String name;

    public JumpTarget(@NonNull String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}

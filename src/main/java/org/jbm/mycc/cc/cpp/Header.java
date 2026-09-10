package org.jbm.mycc.cc.cpp;

/** A header found by a {@link HeaderProvider}: the name diagnostics use for it, and its text. */
public record Header(String name, String text) {
}

package org.jbm.mycc.cc.backend.lower.tac;

/** Whether a name is visible to other modules ({@code external}) or not ({@code internal}, C's {@code static}). */
public enum Linkage {
    EXTERNAL, INTERNAL
}

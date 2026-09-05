package org.jbm.cc.ast;

/**
 * block-item (C2y 6.8.3): a declaration or a statement inside a compound
 * statement. A bare label is represented as a {@link Stmt.Labeled} with a
 * null body.
 */
public sealed interface BlockItem permits Decl, Stmt {
}

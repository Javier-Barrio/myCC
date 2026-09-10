/**
 * The typed tree: what the typing pass produces and lowering consumes.
 * Every node carries its semantic type and its value category is its
 * Java type ({@code Lvalue}, {@code Rvalue}, {@code FunctionDesignator}),
 * so the constructors enforce the constraints of C2y 6.5. Everything is
 * non-null unless annotated {@code @Nullable}.
 */
@NotNullByDefault
package org.jbm.mycc.cc.sema.tast;

import org.jetbrains.annotations.NotNullByDefault;

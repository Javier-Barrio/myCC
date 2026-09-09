/**
 * Lowering ({@code docs/lower-plan.md}): the typed tree to the TAC, one
 * direct pass whose working value is a TAC variable plus the C type it
 * holds. Everything is non-null unless annotated {@code @Nullable}.
 */
@NotNullByDefault
package org.jbm.cc.lower;

import org.jetbrains.annotations.NotNullByDefault;

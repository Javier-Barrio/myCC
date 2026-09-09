/**
 * The three-address code ({@code docs/tac-plan.md}): the compiler's
 * output and the input of the VM and of every code generator. A module
 * is compiled for one target and carries its layout as constants; a
 * function is typed variables, blocks and instructions over them; only
 * {@code load} and {@code store} touch memory. Everything is non-null
 * unless annotated {@code @Nullable}.
 */
@NotNullByDefault
package org.jbm.cc.tac;

import org.jetbrains.annotations.NotNullByDefault;

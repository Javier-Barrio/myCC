package org.jbm.cc.lower.tac;

/**
 * A file-scope name of a module: what a consumer binds. A definition
 * ({@link Function}, {@link Global}) brings its code or storage; a
 * declaration ({@link Module.FuncDecl}, {@link Module.GlobalDecl}) names
 * something defined elsewhere, for the consumer to supply.
 */
public sealed interface Symbol permits Function, Global, Module.FuncDecl, Module.GlobalDecl {

    String name();

    /** The object's type, or the function's signature. */
    Type type();

    /** Whether this module defines the name, as opposed to only declaring it. */
    boolean isDefined();
}

package org.jbm.cc.sema.types;

/** type-qualifier set (C2y 6.7.4.1) on a semantic type. */
public record Quals(boolean isConst, boolean isVolatile, boolean isRestrict, boolean isAtomic) {

    public static final Quals NONE = new Quals(false, false, false, false);
    public static final Quals CONST = new Quals(true, false, false, false);

    public boolean isEmpty() {
        return !isConst && !isVolatile && !isRestrict && !isAtomic;
    }

    public Quals plus(Quals o) {
        return new Quals(isConst || o.isConst, isVolatile || o.isVolatile,
                isRestrict || o.isRestrict, isAtomic || o.isAtomic);
    }

    /** {@code const volatile restrict _Atomic} with a trailing space, or "" when empty. */
    public String prefix() {
        var sb = new StringBuilder();
        if (isConst) sb.append("const ");
        if (isVolatile) sb.append("volatile ");
        if (isRestrict) sb.append("restrict ");
        if (isAtomic) sb.append("_Atomic ");
        return sb.toString();
    }
}

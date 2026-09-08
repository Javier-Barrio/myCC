package org.jbm.cc.types;

import java.util.Optional;

/**
 * What a record type needs from its tag: identity (two record types are
 * the same iff they have the same tag), a name for diagnostics, and the
 * layout once the definition has been seen. The semantic pass's tag
 * symbol implements this, so {@code types} does not depend on it.
 */
public interface Tag {

    Optional<String> name();

    /** {@code struct} or {@code union}. */
    String keyword();

    boolean isUnion();

    /** Present once the body has been typed; absent while the type is incomplete. */
    Optional<Layout> layout();
}

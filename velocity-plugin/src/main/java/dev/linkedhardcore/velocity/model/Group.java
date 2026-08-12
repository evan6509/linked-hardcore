package dev.linkedhardcore.velocity.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A shared-life group ("pair" for now; small groups allowed). Every member
 * shares a single life pool: when one member dies, all members die.
 *
 * <p>Kept deliberately simple and immutable so it can later be backed by a
 * dynamic source (e.g. an in-game pair command) without changing call sites.
 */
public final class Group {

    private final String id;
    private final Set<UUID> members;

    public Group(String id, Set<UUID> members) {
        this.id = Objects.requireNonNull(id, "groupId");
        this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    public String id() {
        return id;
    }

    /** Immutable member set. */
    public Set<UUID> members() {
        return members;
    }

    public boolean contains(UUID playerUuid) {
        return members.contains(playerUuid);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Group g && id.equals(g.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Group(" + id + ", members=" + members.size() + ")";
    }
}

package dev.linkedhardcore.velocity.death;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Authoritative proxy-side death totals; backend world wipes do not reset them. */
public final class DeathCounterState {
    private final Map<UUID, DeathRecord> records = new HashMap<>();

    public synchronized DeathRecord ensurePlayer(UUID playerUuid, String playerName) {
        DeathRecord previous = records.get(playerUuid);
        if (previous == null) {
            previous = new DeathRecord(playerUuid, playerName, 0);
            records.put(playerUuid, previous);
        } else if (!previous.playerName().equals(playerName)) {
            previous = new DeathRecord(playerUuid, playerName, previous.deaths());
            records.put(playerUuid, previous);
        }
        return previous;
    }

    public synchronized DeathRecord recordDeath(UUID playerUuid, String playerName) {
        DeathRecord previous = ensurePlayer(playerUuid, playerName);
        DeathRecord updated = new DeathRecord(playerUuid, playerName, previous.deaths() + 1);
        records.put(playerUuid, updated);
        return updated;
    }

    public synchronized void restore(DeathRecord record) {
        if (record != null && record.playerUuid() != null && record.playerName() != null && record.deaths() >= 0) {
            records.put(record.playerUuid(), record);
        }
    }

    public synchronized int count(UUID playerUuid) {
        DeathRecord record = records.get(playerUuid);
        return record == null ? 0 : record.deaths();
    }

    public synchronized List<DeathRecord> snapshot() {
        return new ArrayList<>(records.values());
    }

    public record DeathRecord(UUID playerUuid, String playerName, int deaths) {
    }
}

package com.yusaki.lammailbox.mailing.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MailingStatusRepository {
    long getLastRun(String mailingId);

    void setLastRun(String mailingId, long timestamp);

    int getRunCount(String mailingId);

    void incrementRunCount(String mailingId);

    Optional<Long> getLastRunForPlayer(String mailingId, UUID playerId);

    void setLastRunForPlayer(String mailingId, UUID playerId, long timestamp);

    boolean hasReceived(String mailingId, UUID playerId);

    void markReceived(String mailingId, UUID playerId, long timestamp);

    void flush();

    void purgeMissingMailings(Set<String> activeIds);

    default void shutdown() {
        flush();
    }
}

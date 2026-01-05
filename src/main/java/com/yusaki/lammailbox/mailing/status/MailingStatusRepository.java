package com.yusaki.lammailbox.mailing.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MailingStatusRepository {
    long getLastRun(String mailingId);

    void setLastRun(String mailingId, long timestamp);

    int getRunCount(String mailingId);

    void incrementRunCount(String mailingId);

    /**
     * Atomically increments the run count if it is below the specified maximum.
     *
     * @param mailingId the mailing identifier
     * @param maxRuns the maximum number of runs allowed
     * @return true if incremented, false if already at or above maxRuns
     */
    boolean incrementRunCountIfBelow(String mailingId, int maxRuns);

    Optional<Long> getLastRunForPlayer(String mailingId, UUID playerId);

    void setLastRunForPlayer(String mailingId, UUID playerId, long timestamp);

    boolean hasReceived(String mailingId, UUID playerId);

    void markReceived(String mailingId, UUID playerId, long timestamp);

    /**
     * Atomically marks a player as having received a mailing if not already marked.
     *
     * @param mailingId the mailing identifier
     * @param playerId the player UUID
     * @param timestamp the time of receipt
     * @return true if newly marked, false if player had already received this mailing
     */
    boolean markReceivedIfNew(String mailingId, UUID playerId, long timestamp);

    void flush();

    void purgeMissingMailings(Set<String> activeIds);

    default void shutdown() {
        flush();
    }
}

package com.yusaki.lammailbox.repository;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository abstraction over the mail persistence layer. Implementations
 * are free to back this with flat files, SQL stores, or any other medium.
 */
public interface MailRepository {
    Map<String, Object> loadMail(String mailId);

    void saveMail(String mailId, Map<String, Object> data);

    void deleteMail(String mailId);

    List<String> listMailIds();

    List<String> listMailIdsBySender(String sender);

    List<String> listActiveMailIdsFor(String playerName);

    Optional<Map<String, Object>> findMail(String mailId);

    void save();

    void saveMailItems(String mailId, List<ItemStack> items);

    List<ItemStack> loadMailItems(String mailId);

    Optional<MailRecord> findRecord(String mailId);

    int countActiveMailFor(String playerName);

    default void shutdown() {
        // optional hook for implementations that need explicit cleanup
    }
}

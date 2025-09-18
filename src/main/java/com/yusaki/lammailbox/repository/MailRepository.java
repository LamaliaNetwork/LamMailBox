package com.yusaki.lammailbox.repository;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository abstraction over the YAML mail database. This will let us detach
 * high-level workflows from low-level persistence concerns.
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

    FileConfiguration getBackingConfiguration();

}

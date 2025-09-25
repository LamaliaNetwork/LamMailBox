package com.yusaki.lammailbox.repository;

import com.yusaki.lammailbox.util.ItemSerialization;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class YamlMailRepository implements MailRepository {
    private final JavaPlugin plugin;
    private final File databaseFile;
    private final FileConfiguration database;

    public YamlMailRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = ensureDatabaseFile(plugin);
        this.database = YamlConfiguration.loadConfiguration(databaseFile);
    }

    private File ensureDatabaseFile(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "database.yml");
        if (!file.exists()) {
            plugin.saveResource("database.yml", false);
        }
        return file;
    }

    @Override
    public Map<String, Object> loadMail(String mailId) {
        ConfigurationSection section = getMailSection(mailId, false);
        return section != null ? section.getValues(true) : Collections.emptyMap();
    }

    @Override
    public void saveMail(String mailId, Map<String, Object> data) {
        String base = "mails." + mailId + ".";
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            database.set(base + entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void deleteMail(String mailId) {
        database.set("mails." + mailId, null);
    }

    @Override
    public List<String> listMailIds() {
        if (!database.contains("mails")) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Objects.requireNonNull(database.getConfigurationSection("mails"))
                .getKeys(false));
    }

    @Override
    public List<String> listMailIdsBySender(String sender) {
        return listMailIds().stream()
                .filter(id -> sender.equals(database.getString("mails." + id + ".sender")))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> listActiveMailIdsFor(String playerName) {
        if (!database.contains("mails")) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String mailId : listMailIds()) {
            String base = "mails." + mailId + ".";
            boolean isActive = database.getBoolean(base + "active", true);
            if (!isActive) {
                continue;
            }
            String receiver = database.getString(base + "receiver");
            List<String> claimed = database.getStringList(base + "claimed-players");

            if (receiver == null) {
                continue;
            }
            if (receiver.equals("all")) {
                if (!claimed.contains(playerName)) {
                    result.add(mailId);
                }
            } else if (receiver.contains(";")) {
                List<String> receivers = Arrays.asList(receiver.split(";"));
                if (receivers.contains(playerName)) {
                    result.add(mailId);
                }
            } else if (receiver.equals(playerName)) {
                result.add(mailId);
            }
        }
        return result;
    }

    @Override
    public Optional<Map<String, Object>> findMail(String mailId) {
        if (!database.contains("mails." + mailId)) {
            return Optional.empty();
        }
        return Optional.of(loadMail(mailId));
    }

    @Override
    public void save() {
        try {
            database.save(databaseFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save database: " + e.getMessage());
        }
    }

    @Override
    public void saveMailItems(String mailId, List<ItemStack> items) {
        List<String> serialized = ItemSerialization.serializeItems(items);
        database.set("mails." + mailId + ".items", serialized);
    }

    @Override
    public List<ItemStack> loadMailItems(String mailId) {
        List<String> serialized = database.getStringList("mails." + mailId + ".items");
        return ItemSerialization.deserializeItems(serialized);
    }

    @Override
    public Optional<MailRecord> findRecord(String mailId) {
        return findMail(mailId).flatMap(data -> MailRecord.from(mailId, data));
    }

    @Override
    public int countActiveMailFor(String playerName) {
        return listActiveMailIdsFor(playerName).size();
    }

    private ConfigurationSection getMailSection(String mailId, boolean create) {
        String path = "mails." + mailId;
        if (database.contains(path)) {
            return database.getConfigurationSection(path);
        }
        if (create) {
            return database.createSection(path);
        }
        return null;
    }

    @Override
    public void shutdown() {
        save();
    }
}

package com.yusaki.lammailbox.mailing.status;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class YamlMailingStatusRepository implements MailingStatusRepository {
    private final LamMailBox plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public YamlMailingStatusRepository(LamMailBox plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mailing-status.yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Unable to create mailing-status.yml: " + e.getMessage());
            }
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public synchronized long getLastRun(String mailingId) {
        return yaml.getLong(nodePath(mailingId, "last-run"), 0L);
    }

    @Override
    public synchronized void setLastRun(String mailingId, long timestamp) {
        ensureMailingSection(mailingId);
        yaml.set(nodePath(mailingId, "last-run"), timestamp);
        saveQuietly();
    }

    @Override
    public synchronized int getRunCount(String mailingId) {
        return yaml.getInt(nodePath(mailingId, "run-count"), 0);
    }

    @Override
    public synchronized void incrementRunCount(String mailingId) {
        ensureMailingSection(mailingId);
        String path = nodePath(mailingId, "run-count");
        int current = yaml.getInt(path, 0) + 1;
        yaml.set(path, current);
        saveQuietly();
    }

    @Override
    public synchronized boolean incrementRunCountIfBelow(String mailingId, int maxRuns) {
        ensureMailingSection(mailingId);
        String path = nodePath(mailingId, "run-count");
        int current = yaml.getInt(path, 0);
        if (current >= maxRuns) {
            return false;
        }
        yaml.set(path, current + 1);
        saveQuietly();
        return true;
    }

    @Override
    public synchronized Optional<Long> getLastRunForPlayer(String mailingId, UUID playerId) {
        String path = nodePath(mailingId, "players." + playerId);
        if (!yaml.contains(path)) {
            return Optional.empty();
        }
        return Optional.of(yaml.getLong(path));
    }

    @Override
    public synchronized void setLastRunForPlayer(String mailingId, UUID playerId, long timestamp) {
        ensureMailingSection(mailingId);
        yaml.set(nodePath(mailingId, "players." + playerId), timestamp);
        saveQuietly();
    }

    @Override
    public synchronized boolean hasReceived(String mailingId, UUID playerId) {
        return yaml.contains(nodePath(mailingId, "players." + playerId));
    }

    @Override
    public synchronized void markReceived(String mailingId, UUID playerId, long timestamp) {
        setLastRunForPlayer(mailingId, playerId, timestamp);
    }

    @Override
    public synchronized boolean markReceivedIfNew(String mailingId, UUID playerId, long timestamp) {
        String path = nodePath(mailingId, "players." + playerId);
        if (yaml.contains(path)) {
            return false;
        }
        ensureMailingSection(mailingId);
        yaml.set(path, timestamp);
        saveQuietly();
        return true;
    }

    @Override
    public synchronized void flush() {
        saveQuietly();
    }

    @Override
    public synchronized void purgeMissingMailings(Set<String> activeIds) {
        ConfigurationSection root = yaml.getConfigurationSection("mailings");
        if (root == null) {
            return;
        }
        boolean changed = false;
        for (String key : root.getKeys(false)) {
            if (!activeIds.contains(key)) {
                yaml.set("mailings." + key, null);
                changed = true;
            }
        }
        if (changed) {
            saveQuietly();
        }
    }

    private void saveQuietly() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save mailing-status.yml: " + e.getMessage());
        }
    }

    private String nodePath(String mailingId, String suffix) {
        return "mailings." + mailingId + "." + suffix;
    }

    private void ensureMailingSection(String mailingId) {
        String base = "mailings." + mailingId;
        if (!yaml.contains(base)) {
            yaml.createSection(base);
        }
    }
}

package com.yusaki.lammailbox.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads persistence backend settings from storage.yml.
 */
public final class StorageSettings {
    public enum BackendType {
        YAML,
        SQLITE;

        public static BackendType from(String value, BackendType fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return BackendType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }
    }

    private final BackendType backendType;
    private final Path sqlitePath;

    private StorageSettings(BackendType backendType, Path sqlitePath) {
        this.backendType = backendType;
        this.sqlitePath = sqlitePath;
    }

    public static StorageSettings load(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create data folder: " + dataFolder);
        }

        File storageFile = new File(dataFolder, "storage.yml");
        if (!storageFile.exists()) {
            plugin.saveResource("storage.yml", false);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        BackendType backend = BackendType.from(yaml.getString("storage.type"), BackendType.SQLITE);
        
        // Hardcode SQLite database path
        Path sqlitePath = plugin.getDataFolder().toPath().resolve("mailbox.db");
        try {
            Files.createDirectories(sqlitePath.getParent());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create directories for SQLite database: " + e.getMessage());
        }
        
        return new StorageSettings(backend, sqlitePath);
    }

    public BackendType backendType() {
        return backendType;
    }

    public Path sqlitePath() {
        return sqlitePath;
    }
}

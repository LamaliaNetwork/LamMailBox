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
    private final SqliteSettings sqliteSettings;

    private StorageSettings(BackendType backendType, SqliteSettings sqliteSettings) {
        this.backendType = backendType;
        this.sqliteSettings = sqliteSettings;
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
        SqliteSettings sqlite = SqliteSettings.from(plugin, yaml);
        if (backend != BackendType.SQLITE) {
            sqlite = sqlite.withImport(false);
        }
        return new StorageSettings(backend, sqlite);
    }

    public BackendType backendType() {
        return backendType;
    }

    public SqliteSettings sqlite() {
        return sqliteSettings;
    }

    /**
     * Holder for SQLite-specific configuration values.
     */
    public static final class SqliteSettings {
        private final Path databasePath;
        private final boolean importFromYaml;

        private SqliteSettings(Path databasePath, boolean importFromYaml) {
            this.databasePath = databasePath;
            this.importFromYaml = importFromYaml;
        }

        private static SqliteSettings from(JavaPlugin plugin, YamlConfiguration yaml) {
            String fileName = defaultString(yaml.getString("storage.sqlite.file"), "mailbox.db");
            Path dbPath = resolveDatabasePath(plugin, fileName);

            boolean importFromYaml = yaml.getBoolean("storage.sqlite.import-from-yaml", true);

            return new SqliteSettings(dbPath, importFromYaml);
        }

        private static Path resolveDatabasePath(JavaPlugin plugin, String fileName) {
            Path path = new File(fileName).toPath();
            if (!path.isAbsolute()) {
                path = plugin.getDataFolder().toPath().resolve(fileName).normalize();
            }
            try {
                Files.createDirectories(path.getParent() == null ? plugin.getDataFolder().toPath() : path.getParent());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create directories for SQLite database: " + e.getMessage());
            }
            return path;
        }

        public Path databasePath() {
            return databasePath;
        }

        public boolean importFromYaml() {
            return importFromYaml;
        }

        public boolean isEnabled() {
            return databasePath != null;
        }

        public SqliteSettings withImport(boolean value) {
            return new SqliteSettings(databasePath, value);
        }

        private static String defaultString(String value, String fallback) {
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            return value;
        }
    }
}

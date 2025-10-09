package com.yusaki.lammailbox.config;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yusaki.lib.config.ConfigMigration;
import org.yusaki.lib.config.ConfigUpdateOptions;
import org.yusaki.lib.config.ConfigUpdateService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
/**
 * Handles configuration updates for LamMailBox using YskLib's ConfigUpdateService.
 */
public class MailBoxConfigUpdater {
    private final LamMailBox plugin;
    private final YamlConfiguration bundledDefaults;

    public MailBoxConfigUpdater(LamMailBox plugin) {
        this.plugin = plugin;
        this.bundledDefaults = loadBundledDefaults();
    }

    /**
     * Updates all configuration files for the plugin.
     */
    public void updateConfigs() {
        updateMainConfig();
        updateStorageConfig();
    }

    /**
     * Updates the main config.yml file.
     */
    private void updateMainConfig() {
        ConfigUpdateOptions options = ConfigUpdateOptions.builder()
                .fileName("config.yml")
                .resourcePath("config.yml")
                .versionPath("version")
                .backupEnabled(true)
                .preserveExistingValues(true)
                .reorderToTemplate(true)
                .skipMergeIfVersionMatches(true)
                .reloadAction(this::reloadMainConfig)
                .addMigration(createVersionMigration())
                .build();

        ConfigUpdateService.update(plugin, options);
    }

    /**
     * Updates the storage.yml file that controls the persistence backend.
     */
    private void updateStorageConfig() {
        ConfigUpdateOptions options = ConfigUpdateOptions.builder()
                .fileName("storage.yml")
                .resourcePath("storage.yml")
                .backupEnabled(true)
                .preserveExistingValues(true)
                .reorderToTemplate(true)
                .skipMergeIfVersionMatches(false)
                .build();

        ConfigUpdateService.update(plugin, options);
    }

    // Config version - tracks configuration schema changes
    private static final double CURRENT_CONFIG_VERSION = 1.7;

    /**
     * Creates migrations for different config versions.
     */
    private ConfigMigration createVersionMigration() {
        return ConfigMigration.guarded(
                CURRENT_CONFIG_VERSION,
                config -> {
                    double existingVersion = config.getDouble("version", 0.0);
                    return !config.contains("version")
                            || existingVersion < CURRENT_CONFIG_VERSION
                            || hasMissingMessages(config);
                },
                this::migrateToCurrentConfigVersion,
                "Update config schema to version " + CURRENT_CONFIG_VERSION
        );
    }

    /**
     * Migration logic for the current config version.
     */
    private void migrateToCurrentConfigVersion(FileConfiguration config) {
        plugin.getLogger().info("Migrating config schema to version " + CURRENT_CONFIG_VERSION + "...");

        // Add version if it doesn't exist or update it
        config.set("version", CURRENT_CONFIG_VERSION);

        if (!config.contains("settings.default-expire-days")) {
            int legacy = config.getInt("settings.admin-mail-expire-days", 7);
            config.set("settings.default-expire-days", legacy);
        }
        if (config.contains("settings.admin-mail-expire-days")) {
            config.set("settings.admin-mail-expire-days", null);
        }

        ConfigurationSection aliasSection = config.getConfigurationSection("settings.command-aliases");
        if (aliasSection == null) {
            aliasSection = config.createSection("settings.command-aliases");
        }
        if (!aliasSection.contains("lmb")) {
            var aliases = aliasSection.getStringList("base");
            if (aliases == null || aliases.isEmpty()) {
                aliases = Arrays.asList("mailbox", "mail", "mb");
            }
            aliasSection.set("lmb", aliases);
        }

        mergeMessages(config);

        plugin.getLogger().info("Config schema migration to version " + CURRENT_CONFIG_VERSION + " completed.");
    }

    /**
     * Reloads the main configuration after update.
     */
    private void reloadMainConfig(File configFile) {
        plugin.reloadConfig();
        plugin.getLogger().info("Main configuration reloaded after update.");
    }

    private YamlConfiguration loadBundledDefaults() {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                plugin.getLogger().warning("Bundled config.yml resource not found; message merge skipped.");
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("Failed to load default config.yml: " + ex.getMessage());
            return null;
        }
    }

    private boolean hasMissingMessages(FileConfiguration config) {
        if (bundledDefaults == null) {
            return false;
        }
        ConfigurationSection defaults = bundledDefaults.getConfigurationSection("messages");
        if (defaults == null) {
            return false;
        }
        ConfigurationSection current = config.getConfigurationSection("messages");
        if (current == null) {
            return true;
        }
        return hasMissingMessages(current, defaults);
    }

    private boolean hasMissingMessages(ConfigurationSection target, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection childDefaults = Objects.requireNonNull(defaults.getConfigurationSection(key));
                ConfigurationSection childTarget = target.getConfigurationSection(key);
                if (childTarget == null || hasMissingMessages(childTarget, childDefaults)) {
                    return true;
                }
            } else if (!target.isSet(key)) {
                return true;
            }
        }
        return false;
    }

    private void mergeMessages(FileConfiguration config) {
        if (bundledDefaults == null) {
            return;
        }
        ConfigurationSection defaults = bundledDefaults.getConfigurationSection("messages");
        if (defaults == null) {
            return;
        }
        ConfigurationSection messages = config.getConfigurationSection("messages");
        if (messages == null) {
            messages = config.createSection("messages");
        }
        mergeSections(messages, defaults);
    }

    private void mergeSections(ConfigurationSection target, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultChild = Objects.requireNonNull(defaults.getConfigurationSection(key));
                ConfigurationSection targetChild = target.getConfigurationSection(key);
                if (targetChild == null) {
                    targetChild = target.createSection(key);
                }
                mergeSections(targetChild, defaultChild);
            } else if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
            }
        }
    }
}

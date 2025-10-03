package com.yusaki.lammailbox.config;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.yusaki.lib.config.ConfigMigration;
import org.yusaki.lib.config.ConfigUpdateOptions;
import org.yusaki.lib.config.ConfigUpdateService;

import java.io.File;
import java.util.Arrays;
/**
 * Handles configuration updates for LamMailBox using YskLib's ConfigUpdateService.
 */
public class MailBoxConfigUpdater {
    private final LamMailBox plugin;

    public MailBoxConfigUpdater(LamMailBox plugin) {
        this.plugin = plugin;
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
    private static final double CURRENT_CONFIG_VERSION = 1.5;

    /**
     * Creates migrations for different config versions.
     */
    private ConfigMigration createVersionMigration() {
        return ConfigMigration.guarded(
            CURRENT_CONFIG_VERSION,
            config -> !config.contains("version") || config.getDouble("version", 0.0) < CURRENT_CONFIG_VERSION,
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
        if (!aliasSection.contains("base")) {
            aliasSection.set("base", Arrays.asList("mailbox", "mail", "mb"));
        }

        // Add any config-specific migrations here
        // Example: if (config.getDouble("version", 0.0) < 1.1) { ... }

        plugin.getLogger().info("Config schema migration to version " + CURRENT_CONFIG_VERSION + " completed.");
    }

    /**
     * Reloads the main configuration after update.
     */
    private void reloadMainConfig(File configFile) {
        plugin.reloadConfig();
        plugin.getLogger().info("Main configuration reloaded after update.");
    }
}

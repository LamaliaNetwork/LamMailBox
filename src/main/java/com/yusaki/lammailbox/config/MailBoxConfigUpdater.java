package com.yusaki.lammailbox.config;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.yusaki.lib.config.ConfigMigration;
import org.yusaki.lib.config.ConfigUpdateOptions;
import org.yusaki.lib.config.ConfigUpdateService;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        updateDatabaseConfig();
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
     * Updates the database.yml file.
     */
    private void updateDatabaseConfig() {
        ConfigUpdateOptions options = ConfigUpdateOptions.builder()
                .fileName("database.yml")
                .resourcePath("database.yml")
                .backupEnabled(true)
                .preserveExistingValues(true)
                .reorderToTemplate(false)
                .skipMergeIfVersionMatches(false)
                .build();

        ConfigUpdateService.update(plugin, options);
    }

    // Config version - tracks configuration schema changes
    private static final double CURRENT_CONFIG_VERSION = 1.0;

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
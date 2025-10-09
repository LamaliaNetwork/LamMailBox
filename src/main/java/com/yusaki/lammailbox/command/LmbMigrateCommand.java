package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.config.StorageSettings;
import com.yusaki.lammailbox.repository.MailRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class LmbMigrateCommand implements CommandExecutor {
    private final LamMailBox plugin;

    public LmbMigrateCommand(LamMailBox plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration config = plugin.getConfig();
        String permission = config.getString("settings.permissions.migrate", "lammailbox.migrate");

        if (!sender.hasPermission(permission)) {
            plugin.sendPrefixedMessage(sender, "messages.no-permission");
            return true;
        }

        if (args.length < 2) {
            plugin.sendPrefixedMessage(sender, "messages.migrate-usage");
            return true;
        }

        StorageSettings.BackendType sourceType = parseBackend(args[0]);
        StorageSettings.BackendType targetType = parseBackend(args[1]);

        if (sourceType == null) {
            plugin.sendPrefixedMessage(sender, "messages.migrate-invalid",
                    plugin.placeholders("type", args[0].toLowerCase(Locale.ROOT)));
            return true;
        }
        if (targetType == null) {
            plugin.sendPrefixedMessage(sender, "messages.migrate-invalid",
                    plugin.placeholders("type", args[1].toLowerCase(Locale.ROOT)));
            return true;
        }
        if (sourceType == targetType) {
            plugin.sendPrefixedMessage(sender, "messages.migrate-same");
            return true;
        }

        StorageSettings storageSettings = StorageSettings.load(plugin);
        boolean reuseSource = sourceType == plugin.getActiveBackend();
        boolean reuseTarget = targetType == plugin.getActiveBackend();

        MailRepository sourceRepo = null;
        MailRepository targetRepo = null;

        try {
            sourceRepo = reuseSource ? plugin.getMailRepository() : plugin.buildRepository(storageSettings, sourceType, false);
            targetRepo = reuseTarget ? plugin.getMailRepository() : plugin.buildRepository(storageSettings, targetType, false);

            List<String> sourceIds = sourceRepo.listMailIds();
            if (sourceIds.isEmpty()) {
                plugin.sendPrefixedMessage(sender, "messages.migrate-empty",
                        plugin.placeholders("source", args[0].toLowerCase(Locale.ROOT)));
                return true;
            }

            // Check if target database is empty before proceeding
            List<String> targetIds = targetRepo.listMailIds();
            if (!targetIds.isEmpty()) {
                plugin.sendPrefixedMessage(sender, "messages.migrate-target-not-empty",
                        plugin.placeholders(
                                "count", String.valueOf(targetIds.size()),
                                "target", args[1].toLowerCase(Locale.ROOT)));
                return true;
            }

            plugin.sendPrefixedMessage(sender, "messages.migrate-start",
                    plugin.placeholders(
                            "source", args[0].toLowerCase(Locale.ROOT),
                            "target", args[1].toLowerCase(Locale.ROOT)));

            int migrated = 0;
            for (String mailId : sourceIds) {
                Map<String, Object> data = sourceRepo.loadMail(mailId);
                targetRepo.saveMail(mailId, data);
                List<ItemStack> items = sourceRepo.loadMailItems(mailId);
                targetRepo.saveMailItems(mailId, items);
                migrated++;
            }
            targetRepo.save();

            plugin.sendPrefixedMessage(sender, "messages.migrate-success",
                    plugin.placeholders(
                            "count", String.valueOf(migrated),
                            "target", args[1].toLowerCase(Locale.ROOT)));
        } catch (Exception ex) {
            plugin.getLogger().severe("Migration failed: " + ex.getMessage());
            plugin.sendPrefixedMessage(sender, "messages.migrate-error",
                    plugin.placeholders("error", Optional.ofNullable(ex.getMessage()).orElse("unknown")));
        } finally {
            if (!reuseSource && sourceRepo != null) {
                sourceRepo.shutdown();
            }
            if (!reuseTarget && targetRepo != null && targetRepo != sourceRepo) {
                targetRepo.shutdown();
            }
        }

        return true;
    }

    private StorageSettings.BackendType parseBackend(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return StorageSettings.BackendType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

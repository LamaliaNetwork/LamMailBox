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
        String prefix = plugin.colorize(config.getString("messages.prefix"));
        String permission = config.getString("settings.permissions.migrate", "lammailbox.migrate");

        if (!sender.hasPermission(permission)) {
            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.no-permission")));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-usage", "&cUsage: /lmbmigrate <source> <target>")));
            return true;
        }

        StorageSettings.BackendType sourceType = parseBackend(args[0]);
        StorageSettings.BackendType targetType = parseBackend(args[1]);

        if (sourceType == null) {
            sender.sendMessage(plugin.colorize(prefix + plugin.applyPlaceholderVariants(
                    config.getString("messages.migrate-invalid", "&c✖ Unknown storage type: %type%"),
                    "type",
                    args[0].toLowerCase(Locale.ROOT))));
            return true;
        }
        if (targetType == null) {
            sender.sendMessage(plugin.colorize(prefix + plugin.applyPlaceholderVariants(
                    config.getString("messages.migrate-invalid", "&c✖ Unknown storage type: %type%"),
                    "type",
                    args[1].toLowerCase(Locale.ROOT))));
            return true;
        }
        if (sourceType == targetType) {
            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-same", "&c✖ Source and target storage must be different.")));
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
                sender.sendMessage(plugin.colorize(prefix + plugin.applyPlaceholderVariants(
                        config.getString("messages.migrate-empty", "&eNo mail entries found in %source% storage."),
                        "source",
                        args[0].toLowerCase(Locale.ROOT))));
                return true;
            }

            // Check if target database is empty before proceeding
            List<String> targetIds = targetRepo.listMailIds();
            if (!targetIds.isEmpty()) {
                String template = config.getString("messages.migrate-target-not-empty",
                        "&c✖ Target storage contains %count% mail entries. Please clear the target storage first or choose an empty target.");
                sender.sendMessage(plugin.colorize(prefix + plugin.applyPlaceholderVariants(template, Map.of(
                        "count", String.valueOf(targetIds.size()),
                        "target", args[1].toLowerCase(Locale.ROOT)))));
                return true;
            }

            String startTemplate = config.getString("messages.migrate-start",
                    "&eMigrating mail from &f%source% &eto &f%target%&e...");
            sender.sendMessage(plugin.colorize(prefix + plugin.applyPlaceholderVariants(startTemplate, Map.of(
                    "source", args[0].toLowerCase(Locale.ROOT),
                    "target", args[1].toLowerCase(Locale.ROOT)))));

            int migrated = 0;
            for (String mailId : sourceIds) {
                Map<String, Object> data = sourceRepo.loadMail(mailId);
                targetRepo.saveMail(mailId, data);
                List<ItemStack> items = sourceRepo.loadMailItems(mailId);
                targetRepo.saveMailItems(mailId, items);
                migrated++;
            }
            targetRepo.save();

            String successTemplate = config.getString("messages.migrate-success",
                    "&a✔ Migration complete: %count% mails moved to %target%.");
            String successMessage = plugin.applyPlaceholderVariants(successTemplate, Map.of(
                    "count", String.valueOf(migrated),
                    "target", args[1].toLowerCase(Locale.ROOT)));
            sender.sendMessage(plugin.colorize(prefix + successMessage));
        } catch (Exception ex) {
            plugin.getLogger().severe("Migration failed: " + ex.getMessage());
            String errorTemplate = config.getString("messages.migrate-error", "&c✖ Migration failed: %error%");
            String errorMessage = plugin.applyPlaceholderVariants(errorTemplate,
                    "error",
                    Optional.ofNullable(ex.getMessage()).orElse("unknown"));
            sender.sendMessage(plugin.colorize(prefix + errorMessage));
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

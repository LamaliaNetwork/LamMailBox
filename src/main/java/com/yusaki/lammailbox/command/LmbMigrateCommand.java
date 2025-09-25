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
            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-invalid", "&c✖ Unknown storage type: %type%").replace("%type%", args[0].toLowerCase(Locale.ROOT))));
            return true;
        }
        if (targetType == null) {
            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-invalid", "&c✖ Unknown storage type: %type%").replace("%type%", args[1].toLowerCase(Locale.ROOT))));
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
                sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-empty", "&eNo mail entries found in %source% storage.").replace("%source%", args[0].toLowerCase(Locale.ROOT))));
                return true;
            }

            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-start", "&eMigrating mail from %source% to %target%...")
                    .replace("%source%", args[0].toLowerCase(Locale.ROOT))
                    .replace("%target%", args[1].toLowerCase(Locale.ROOT))));

            // Clear existing target entries
            for (String existingId : targetRepo.listMailIds()) {
                targetRepo.deleteMail(existingId);
            }

            int migrated = 0;
            for (String mailId : sourceIds) {
                Map<String, Object> data = sourceRepo.loadMail(mailId);
                targetRepo.saveMail(mailId, data);
                List<ItemStack> items = sourceRepo.loadMailItems(mailId);
                targetRepo.saveMailItems(mailId, items);
                migrated++;
            }
            targetRepo.save();

            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-success", "&a✔ Migration complete: %count% mails moved to %target%.")
                    .replace("%count%", String.valueOf(migrated))
                    .replace("%target%", args[1].toLowerCase(Locale.ROOT))));
        } catch (Exception ex) {
            plugin.getLogger().severe("Migration failed: " + ex.getMessage());
            sender.sendMessage(plugin.colorize(prefix + config.getString("messages.migrate-error", "&c✖ Migration failed: %error%")
                    .replace("%error%", Optional.ofNullable(ex.getMessage()).orElse("unknown"))));
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

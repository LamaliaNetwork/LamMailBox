package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;

/**
 * Executes configured commands attached to decorative GUI elements.
 */
final class DecorationCommandExecutor {
    private final LamMailBox plugin;
    private final NamespacedKey decorationKey;

    DecorationCommandExecutor(LamMailBox plugin, NamespacedKey decorationKey) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.decorationKey = Objects.requireNonNull(decorationKey, "decorationKey");
    }

    boolean execute(InventoryClickEvent event, Player player, ItemStack clicked) {
        if (clicked == null) {
            return false;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return false;
        }
        String configPath = meta.getPersistentDataContainer().get(decorationKey, PersistentDataType.STRING);
        if (configPath == null || configPath.isEmpty()) {
            return false;
        }

        List<String> commands = config().getStringList(configPath + ".commands");
        if (commands.isEmpty()) {
            return false;
        }

        event.setCancelled(true);
        String playerName = player.getName();
        String uuid = player.getUniqueId().toString();
        plugin.getFoliaLib().getScheduler().runNextTick(task -> {
            for (String template : commands) {
                if (template == null || template.trim().isEmpty()) {
                    continue;
                }
                // YskLib MessageManager now supports both {placeholder} and %placeholder% formats
                String command = template
                        .replace("{player}", playerName)
                        .replace("{uuid}", uuid)
                        .replace("%player%", playerName)
                        .replace("%uuid%", uuid);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });
        return true;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

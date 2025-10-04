package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles interactions within the item editor GUI.
 */
final class ItemsGuiClickActions {
    private final LamMailBox plugin;
    private final NamespacedKeyProvider keyProvider;
    private final DecorationCommandExecutor decorationExecutor;

    ItemsGuiClickActions(LamMailBox plugin,
                         NamespacedKeyProvider keyProvider,
                         DecorationCommandExecutor decorationExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.decorationExecutor = Objects.requireNonNull(decorationExecutor, "decorationExecutor");
    }

    void handle(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            String action = meta != null
                    ? meta.getPersistentDataContainer().get(keyProvider.actionKey(), PersistentDataType.STRING)
                    : null;
            if ("items-back".equals(action)) {
                event.setCancelled(true);
                Player player = (Player) event.getWhoClicked();
                player.closeInventory();
                plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCreateMailGUI(player));
                return;
            }
        }

        if (clicked != null && decorationExecutor.execute(event, (Player) event.getWhoClicked(), clicked)) {
            return;
        }

        if (isEnabled("gui.items.items.save-button") &&
                event.getSlot() == config().getInt("gui.items.items.save-button.slot")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
            if (session != null) {
                List<ItemStack> items = collectItems(event.getInventory());
                session.setItems(items);
                player.closeInventory();
            }
        }
    }

    private List<ItemStack> collectItems(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        int saveSlot = config().getInt("gui.items.items.save-button.slot");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == saveSlot) {
                continue;
            }
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }
        return items;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }

    /** Simple wrapper to reuse the common action key. */
    record NamespacedKeyProvider(org.bukkit.NamespacedKey actionKey) {}
}

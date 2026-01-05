package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Objects;

/**
 * Handles interactions inside the create-mail GUI.
 */
final class MailCreationGuiClickActions {
    private final LamMailBox plugin;
    private final NamespacedKeyProvider keyProvider;
    private final DecorationCommandExecutor decorationExecutor;

    MailCreationGuiClickActions(LamMailBox plugin,
                                NamespacedKeyProvider keyProvider,
                                DecorationCommandExecutor decorationExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.decorationExecutor = Objects.requireNonNull(decorationExecutor, "decorationExecutor");
    }

    void handle(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.hasPermission(config().getString("settings.admin-permission")) &&
                event.getClickedInventory() == player.getInventory()) {
            return;
        }
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        if (meta != null) {
            String action = meta.getPersistentDataContainer().get(keyProvider.actionKey(), PersistentDataType.STRING);
            if ("create-back".equals(action)) {
                plugin.getInMailCreation().put(player.getUniqueId(), true);
                plugin.openMainGUI(player);
                return;
            }
        }

        if (decorationExecutor.execute(event, player, clicked)) {
            return;
        }

        MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
        if (session == null) {
            return;
        }

        int slot = event.getSlot();
        if (slotMatches("gui.create-mail.items.receiver-head", slot)) {
            handleReceiverHeadClick(player);
            return;
        }
        if (slotMatches("gui.create-mail.items.message-paper", slot)) {
            handleMessagePaperClick(player);
            return;
        }
        if (slotMatches("gui.create-mail.items.items-chest", slot)) {
            handleItemsChestClick(player);
            return;
        }
        if (slotMatches("gui.create-mail.items.send-button", slot)) {
            plugin.handleMailSend(player);
            return;
        }
        if (slotMatches("gui.create-mail.items.command-block", slot)) {
            handleCommandItemsClick(player);
            return;
        }
        if (slotMatches("gui.create-mail.items.schedule-clock", slot)) {
            handleScheduleClockClick(event, player);
        }
    }

    private void handleReceiverHeadClick(Player player) {
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        player.closeInventory();
        plugin.getAwaitingInput().put(player.getUniqueId(), "receiver");
        plugin.getMailCreationController().showInputTitle(player, "receiver");
        player.sendMessage(plugin.legacy(config().getString("messages.enter-receiver")));
    }

    private void handleMessagePaperClick(Player player) {
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        player.closeInventory();
        plugin.getAwaitingInput().put(player.getUniqueId(), "message");
        plugin.getMailCreationController().showInputTitle(player, "message");
        player.sendMessage(plugin.legacy(config().getString("messages.prefix") +
                config().getString("messages.enter-message")));
    }

    private void handleItemsChestClick(Player player) {
        if (!player.hasPermission(config().getString("settings.permissions.add-items"))) {
            player.sendMessage(plugin.legacy(config().getString("messages.no-permission")));
            return;
        }
        plugin.openItemsGUI(player);
    }

    private void handleCommandItemsClick(Player player) {
        if (!player.hasPermission(config().getString("settings.admin-permission"))) {
            player.sendMessage(plugin.legacy(config().getString("messages.no-permission")));
            return;
        }
        plugin.openCommandItemsEditor(player);
    }

    private void handleScheduleClockClick(InventoryClickEvent event, Player player) {
        if (!player.hasPermission(config().getString("settings.admin-permission"))) {
            return;
        }
        player.closeInventory();
        String awaitingKey = event.getClick().isRightClick() ? "expire-date" : "schedule-date";
        Map<String, String> titles = Map.of(
                "expire-date", "expire",
                "schedule-date", "schedule"
        );

        plugin.getAwaitingInput().put(player.getUniqueId(), awaitingKey);
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        plugin.getMailCreationController().showInputTitle(player, titles.get(awaitingKey));

        String messageKey = awaitingKey.equals("expire-date")
                ? "messages.enter-expire-date"
                : "messages.enter-schedule-date";
        player.sendMessage(plugin.legacy(config().getString("messages.prefix") +
                config().getString(messageKey)));
    }

    private boolean slotMatches(String path, int slot) {
        return isEnabled(path) && slot == config().getInt(path + ".slot");
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }

    /**
     * Provides access to the action key without duplicating dependencies.
     */
    record NamespacedKeyProvider(org.bukkit.NamespacedKey actionKey) {
    }
}

package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handles both the command items editor and command item creator GUIs.
 */
final class CommandItemsClickActions {
    private final LamMailBox plugin;
    private final NamespacedKey commandItemIndexKey;
    private final NamespacedKey commandItemActionKey;

    CommandItemsClickActions(LamMailBox plugin,
                             NamespacedKey commandItemIndexKey,
                             NamespacedKey commandItemActionKey) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.commandItemIndexKey = Objects.requireNonNull(commandItemIndexKey, "commandItemIndexKey");
        this.commandItemActionKey = Objects.requireNonNull(commandItemActionKey, "commandItemActionKey");
    }

    void handleEditorClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(player.getUniqueId(), key -> new MailCreationSession());
        ensureCommandItemsInitialized(session);

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }

        Integer index = meta.getPersistentDataContainer().get(commandItemIndexKey, PersistentDataType.INTEGER);
        String action = meta.getPersistentDataContainer().get(commandItemActionKey, PersistentDataType.STRING);

        if (index != null) {
            handleEditorItemClick(event, player, session, index);
            return;
        }

        if (action == null) {
            return;
        }

        if ("add".equals(action)) {
            session.setCommandItemEditIndex(null);
            session.setCommandItemDraft(new CommandItem.Builder());
            plugin.openCommandItemCreator(player);
        } else if ("back".equals(action)) {
            plugin.openCreateMailGUI(player);
        }
    }

    void handleCreatorClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(player.getUniqueId(), key -> new MailCreationSession());
        ensureCommandItemsInitialized(session);

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }
        String action = meta.getPersistentDataContainer().get(commandItemActionKey, PersistentDataType.STRING);
        if (action == null) {
            action = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "action"), PersistentDataType.STRING);
        }
        if (action == null) {
            return;
        }

        switch (action) {
            case "material" -> startAwaiting(player, "command-item-material", "command-item-material", config().getString("messages.enter-command-item-material"));
            case "name" -> startAwaiting(player, "command-item-name", "command-item-name", config().getString("messages.enter-command-item-name"));
            case "lore" -> handleListEdit(event, player, session, true);
            case "command" -> handleListEdit(event, player, session, false);
            case "custom-model" -> handleCustomModelEdit(event, player, session);
            case "command-creator-back" -> plugin.openCommandItemsEditor(player);
            case "save" -> finalizeCommandItem(player, session);
            default -> {
                // no-op
            }
        }
    }

    private void handleEditorItemClick(InventoryClickEvent event, Player player, MailCreationSession session, int index) {
        List<CommandItem> items = session.getCommandItems();
        if (index < 0 || index >= items.size()) {
            return;
        }
        if (event.isRightClick()) {
            items.remove(index);
            session.setCommandItems(items);
            plugin.openCommandItemsEditor(player);
        } else {
            session.setCommandItemEditIndex(index);
            session.setCommandItemDraft(items.get(index).toBuilder());
            plugin.openCommandItemCreator(player);
        }
    }

    private void handleListEdit(InventoryClickEvent event, Player player, MailCreationSession session, boolean isLore) {
        CommandItem.Builder draft = session.getCommandItemDraft();
        if (event.isRightClick()) {
            boolean removed = isLore ? draft != null && draft.removeLastLoreLine()
                    : draft != null && draft.removeLastCommand();
            String messageKey = isLore ? (removed ? "messages.command-item-lore-removed" : "messages.command-item-lore-empty")
                    : (removed ? "messages.command-item-command-removed" : "messages.command-item-command-empty");
            player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                    config().getString(messageKey)));
            plugin.openCommandItemCreator(player);
        } else {
            String awaitingKey = isLore ? "command-item-lore" : "command-item-command";
            String titleKey = isLore ? "command-item-lore" : "command-item-command";
            String messageKey = isLore ? "messages.enter-command-item-lore" : "messages.enter-command-item-command";
            startAwaiting(player, awaitingKey, titleKey, config().getString(messageKey));
        }
    }

    private void handleCustomModelEdit(InventoryClickEvent event, Player player, MailCreationSession session) {
        CommandItem.Builder draft = session.getCommandItemDraft();
        if (event.isRightClick()) {
            if (draft != null) {
                draft.customModelData(null);
                player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                        config().getString("messages.command-item-model-cleared")));
            }
            plugin.openCommandItemCreator(player);
        } else {
            startAwaiting(player,
                    "command-item-custom-model",
                    "command-item-custom-model",
                    config().getString("messages.enter-command-item-custom-model"));
        }
    }

    private void finalizeCommandItem(Player player, MailCreationSession session) {
        if (plugin.getMailCreationController().finalizeCommandItem(player, session)) {
            plugin.openCommandItemsEditor(player);
        } else {
            plugin.openCommandItemCreator(player);
        }
    }

    private void ensureCommandItemsInitialized(MailCreationSession session) {
        if (session.getCommandItems() == null) {
            session.setCommandItems(new java.util.ArrayList<>());
        }
    }

    private void startAwaiting(Player player, String key, String titleKey, String message) {
        plugin.getAwaitingInput().put(player.getUniqueId(), key);
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        plugin.getMailCreationController().showInputTitle(player, titleKey);
        if (message != null) {
            player.sendMessage(plugin.colorize(config().getString("messages.prefix") + message));
        }
        player.closeInventory();
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

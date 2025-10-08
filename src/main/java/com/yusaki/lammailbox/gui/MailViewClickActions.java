package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import com.yusaki.lammailbox.repository.MailRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles interactions inside the mail view GUI.
 */
final class MailViewClickActions {
    private final LamMailBox plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey mailIdKey;
    private final DecorationCommandExecutor decorationExecutor;

    MailViewClickActions(LamMailBox plugin,
                         NamespacedKey actionKey,
                         NamespacedKey mailIdKey,
                         DecorationCommandExecutor decorationExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionKey = Objects.requireNonNull(actionKey, "actionKey");
        this.mailIdKey = Objects.requireNonNull(mailIdKey, "mailIdKey");
        this.decorationExecutor = Objects.requireNonNull(decorationExecutor, "decorationExecutor");
    }

    void handle(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);

        if (handleButtonAction(event, player, clicked)) {
            return;
        }
        if (decorationExecutor.execute(event, player, clicked)) {
            return;
        }

        int buttonSlot = config().getInt("gui.mail-view.items.claim-button.slot");
        boolean claimEnabled = isEnabled("gui.mail-view.items.claim-button") ||
                isEnabled("gui.mail-view.items.dismiss-button");
        if (claimEnabled && event.getSlot() == buttonSlot) {
            String mailId = extractMailId(clicked);
            if (mailId != null) {
                claimMail(player, mailId);
            }
        }
    }

    private boolean handleButtonAction(InventoryClickEvent event, Player player, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return false;
        }

        if ("mail-view-back".equals(action)) {
            navigateBackToMailbox(player);
            return true;
        }

        if (action.equals("page-prev") || action.equals("page-next")) {
            String mailId = meta.getPersistentDataContainer().get(mailIdKey, PersistentDataType.STRING);
            if (mailId != null) {
                handlePagination(player, mailId, action.equals("page-next"));
                return true;
            }
        }
        return false;
    }

    private void navigateBackToMailbox(Player player) {
        String viewingAs = plugin.getViewingAsPlayer().get(player.getUniqueId());
        if (viewingAs != null) {
            Player target = Bukkit.getPlayerExact(viewingAs);
            if (target != null) {
                plugin.openMailboxAsPlayer(player, target);
            } else {
                plugin.getViewingAsPlayer().remove(player.getUniqueId());
                plugin.openMainGUI(player);
            }
        } else {
            plugin.openMainGUI(player);
        }
    }

    private void handlePagination(Player player, String mailId, boolean isNext) {
        int currentPage = plugin.getMailViewPages().getOrDefault(player.getUniqueId(), 1);

        List<ItemStack> items = plugin.getMailRepository().loadMailItems(mailId);
        int commandCount = plugin.getMailRepository().findRecord(mailId)
                .map(record -> record.commandItems().size())
                .orElse(0);

        List<Integer> slots = config().getIntegerList("gui.mail-view.items.items-display.slots");
        int slotsPerPage = (slots != null && !slots.isEmpty()) ? slots.size() : 21;
        int totalElements = items.size() + commandCount;
        int totalPages = Math.max(1, (totalElements + slotsPerPage - 1) / slotsPerPage);

        int newPage = isNext ? currentPage + 1 : currentPage - 1;
        newPage = Math.max(1, Math.min(totalPages, newPage));

        if (newPage == currentPage) {
            return;
        }

        plugin.getMailViewPages().put(player.getUniqueId(), newPage);
        plugin.openMailView(player, mailId);
    }

    private void claimMail(Player player, String mailId) {
        List<ItemStack> items = plugin.getMailRepository().loadMailItems(mailId);
        Optional<MailRecord> mailRecord = plugin.getMailRepository().findRecord(mailId);
        List<CommandItem> commandItems = mailRecord.map(MailRecord::commandItems).orElse(Collections.emptyList());
        boolean hasRewards = !items.isEmpty() || !commandItems.isEmpty();

        if (!items.isEmpty()) {
            long emptySlots = Arrays.stream(player.getInventory().getStorageContents())
                    .filter(item -> item == null || item.getType() == Material.AIR)
                    .count();
            if (emptySlots < items.size()) {
                plugin.sendMessage(player, "messages.prefix");
                plugin.sendMessage(player, "messages.inventory-space-needed",
                        plugin.placeholders("amount", String.valueOf(items.size())));
                return;
            }

            items.forEach(item -> player.getInventory().addItem(item));
        }

        if (!commandItems.isEmpty()) {
            plugin.getFoliaLib().getScheduler().runNextTick(task -> commandItems.forEach(commandItem ->
                    commandItem.commands().forEach(command -> {
                        // YskLib MessageManager now supports both {placeholder} and %placeholder% formats
                        String processed = command
                                .replace("{player}", player.getName())
                                .replace("%player%", player.getName());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
                    })));
        }

        plugin.getMailService().claimMail(player, mailId);
        player.closeInventory();
        plugin.openMainGUI(player);
        String messageKey = hasRewards ? "messages.items-claimed" : "messages.no-items-in-mail";
        player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                config().getString(messageKey)));
    }

    private String extractMailId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().get(mailIdKey, PersistentDataType.STRING);
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }
}

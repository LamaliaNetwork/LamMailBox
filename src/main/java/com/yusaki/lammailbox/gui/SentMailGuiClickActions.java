package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.repository.MailRecord;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles interactions inside the sent-mail list GUI and sent-mail detail view.
 */
final class SentMailGuiClickActions {
    private final LamMailBox plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey paginationTargetKey;
    private final DecorationCommandExecutor decorationExecutor;

    SentMailGuiClickActions(LamMailBox plugin,
                            NamespacedKey actionKey,
                            NamespacedKey paginationTargetKey,
                            DecorationCommandExecutor decorationExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionKey = Objects.requireNonNull(actionKey, "actionKey");
        this.paginationTargetKey = Objects.requireNonNull(paginationTargetKey, "paginationTargetKey");
        this.decorationExecutor = Objects.requireNonNull(decorationExecutor, "decorationExecutor");
    }

    void handleListClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (handlePagination(event, player, clicked)) {
            return;
        }
        if (decorationExecutor.execute(event, player, clicked)) {
            return;
        }

        if (isEnabled("gui.sent-mail.items.back-button") &&
                event.getSlot() == config().getInt("gui.sent-mail.items.back-button.slot")) {
            handleBackButton(player);
            return;
        }

        if (isEnabled("gui.sent-mail.items.sent-mail-display") &&
                config().getIntegerList("gui.sent-mail.items.sent-mail-display.slots").contains(event.getSlot())) {
            String mailId = extractMailId(clicked);
            if (mailId != null) {
                plugin.openSentMailView(player, mailId);
            }
        }
    }

    void handleDetailClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if ("sent-mail-view-back".equals(action)) {
                plugin.openSentMailGUI(player);
                return;
            }
        }

        if (decorationExecutor.execute(event, player, clicked)) {
            return;
        }

        if (isEnabled("gui.sent-mail-view.items.delete-button") &&
                event.getSlot() == config().getInt("gui.sent-mail-view.items.delete-button.slot")) {
            String mailId = extractMailId(clicked);
            if (mailId != null) {
                plugin.handleSentMailDelete(player, mailId);
            }
        }
    }

    private boolean handlePagination(InventoryClickEvent event, Player player, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || (!action.equals("sent-page-prev") && !action.equals("sent-page-next"))) {
            return false;
        }

        String targetName = Optional.ofNullable(meta.getPersistentDataContainer()
                        .get(paginationTargetKey, PersistentDataType.STRING))
                .filter(name -> !name.isBlank())
                .orElse(player.getName());

        handleSentMailboxPageNavigation(player, targetName, action.equals("sent-page-next"));
        return true;
    }

    private void handleBackButton(Player player) {
        String viewingAs = plugin.getViewingAsPlayer().get(player.getUniqueId());
        if (viewingAs != null) {
            Player target = Bukkit.getPlayer(viewingAs);
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

    private void handleSentMailboxPageNavigation(Player player, String targetName, boolean isNext) {
        UUID viewerId = player.getUniqueId();
        List<Integer> slots = config().getIntegerList("gui.sent-mail.items.sent-mail-display.slots");
        int slotsPerPage = (slots != null && !slots.isEmpty()) ? slots.size() : 21;

        List<MailRecord> records = plugin.getMailRepository().listMailIdsBySender(targetName).stream()
                .map(id -> plugin.getMailRepository().findRecord(id).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(MailRecord::sentDate).reversed())
                .collect(Collectors.toList());

        int totalPages = Math.max(1, (records.size() + slotsPerPage - 1) / slotsPerPage);
        int currentPage = plugin.getSentMailboxPages().getOrDefault(viewerId, 1);
        int newPage = isNext ? currentPage + 1 : currentPage - 1;
        newPage = Math.max(1, Math.min(totalPages, newPage));
        if (newPage == currentPage) {
            return;
        }

        plugin.getSentMailboxPages().put(viewerId, newPage);
        plugin.openSentMailGUI(player);
    }

    private String extractMailId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "mailId"),
                PersistentDataType.STRING);
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }
}

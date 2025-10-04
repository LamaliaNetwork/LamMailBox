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
 * Handles interactions inside the main mailbox GUI.
 */
final class MainGuiClickActions {
    private final LamMailBox plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey paginationTargetKey;
    private final DecorationCommandExecutor decorationExecutor;

    MainGuiClickActions(LamMailBox plugin,
                        NamespacedKey actionKey,
                        NamespacedKey paginationTargetKey,
                        DecorationCommandExecutor decorationExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionKey = Objects.requireNonNull(actionKey, "actionKey");
        this.paginationTargetKey = Objects.requireNonNull(paginationTargetKey, "paginationTargetKey");
        this.decorationExecutor = Objects.requireNonNull(decorationExecutor, "decorationExecutor");
    }

    void handle(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (handlePagination(event, player, clicked)) {
            return;
        }
        if (decorationExecutor.execute(event, player, clicked)) {
            return;
        }

        if (isEnabled("gui.main.items.create-mail") &&
                event.getSlot() == config().getInt("gui.main.items.create-mail.slot")) {
            if (isViewingAsOther(player)) {
                player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                        config().getString("messages.cannot-create-as-other")));
                return;
            }
            plugin.openCreateMailGUI(player);
            return;
        }

        if (isEnabled("gui.main.items.sent-mail") &&
                event.getSlot() == config().getInt("gui.main.items.sent-mail.slot")) {
            plugin.openSentMailGUI(player);
            return;
        }

        if (isEnabled("gui.main.items.mail-display") &&
                config().getIntegerList("gui.main.items.mail-display.slots").contains(event.getSlot())) {
            String mailId = extractMailId(clicked);
            if (mailId != null) {
                plugin.openMailView(player, mailId);
            }
        }
    }

    private boolean handlePagination(InventoryClickEvent event, Player player, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || (!action.equals("mailbox-page-prev") && !action.equals("mailbox-page-next"))) {
            return false;
        }

        String targetName = Optional.ofNullable(meta.getPersistentDataContainer()
                        .get(paginationTargetKey, PersistentDataType.STRING))
                .filter(name -> !name.isBlank())
                .orElse(player.getName());

        handleMailboxPageNavigation(player, targetName, action.equals("mailbox-page-next"));
        return true;
    }

    private void handleMailboxPageNavigation(Player player, String targetName, boolean isNext) {
        UUID viewerId = player.getUniqueId();
        List<Integer> slots = config().getIntegerList("gui.main.items.mail-display.slots");
        int slotsPerPage = (slots != null && !slots.isEmpty()) ? slots.size() : 21;

        List<MailRecord> records = plugin.getMailRepository().listMailIds().stream()
                .map(id -> plugin.getMailRepository().findRecord(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(MailRecord::active)
                .filter(record -> record.canBeClaimedBy(targetName))
                .sorted(Comparator.comparingLong(MailRecord::sentDate).reversed())
                .collect(Collectors.toList());

        int totalPages = Math.max(1, (records.size() + slotsPerPage - 1) / slotsPerPage);
        int currentPage = plugin.getMailboxPages().getOrDefault(viewerId, 1);
        int newPage = isNext ? currentPage + 1 : currentPage - 1;
        newPage = Math.max(1, Math.min(totalPages, newPage));
        if (newPage == currentPage) {
            return;
        }

        plugin.getMailboxPages().put(viewerId, newPage);

        if (!targetName.equalsIgnoreCase(player.getName())) {
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                plugin.openMailboxAsPlayer(player, target);
            } else {
                plugin.getViewingAsPlayer().remove(viewerId);
                plugin.openMainGUI(player);
            }
        } else {
            plugin.openMainGUI(player);
        }
    }

    private boolean isViewingAsOther(Player player) {
        return plugin.getViewingAsPlayer().get(player.getUniqueId()) != null;
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

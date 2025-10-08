package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates cleanup after GUI close events.
 */
final class GuiCloseHandler {
    private final LamMailBox plugin;

    GuiCloseHandler(LamMailBox plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void handle(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();
        UUID playerId = player.getUniqueId();

        if (title.equals(plugin.legacy(config().getString("gui.items.title")))) {
            handleItemsClose(event, player);
        } else if (title.equals(plugin.legacy(config().getString("gui.create-mail.title")))) {
            handleCreateMailClose(player);
        }

        if (!plugin.getAwaitingInput().containsKey(playerId)) {
            plugin.getInMailCreation().remove(playerId);
        }
        plugin.getDeleteConfirmations().remove(playerId);

        handleMainGuiClose(player, title);
        handleSentGuiClose(player, title);
        handleMailViewClose(player, title);
    }

    private void handleItemsClose(InventoryCloseEvent event, Player player) {
        MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
        if (session == null) {
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        Material saveButtonMaterial = Material.valueOf(config().getString("gui.items.items.save-button.material"));
        Inventory inventory = event.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != saveButtonMaterial) {
                items.add(item.clone());
            }
        }
        session.setItems(items);
        plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCreateMailGUI(player));
    }

    private void handleCreateMailClose(Player player) {
        MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (player.hasPermission(config().getString("settings.admin-permission"))) {
            return;
        }
        if (plugin.getInMailCreation().getOrDefault(player.getUniqueId(), false)) {
            return;
        }

        for (ItemStack item : session.getItems()) {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
            leftover.values().forEach(leftItem -> player.getWorld().dropItem(player.getLocation(), leftItem));
        }
        session.setItems(new ArrayList<>());
    }

    private void handleMainGuiClose(Player player, String title) {
        String mainTitle = plugin.legacy(config().getString("gui.main.title"));
        String mainViewingPrefix = plugin.legacy(config().getString("gui.main.title") + " &7(as ");
        if (!title.equals(mainTitle) && !title.startsWith(mainViewingPrefix)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        plugin.getFoliaLib().getScheduler().runNextTick(task -> {
            if (!player.isOnline()) {
                plugin.getViewingAsPlayer().remove(playerId);
                plugin.getMailboxPages().remove(playerId);
                return;
            }

            String currentTitle = player.getOpenInventory().getTitle();
            boolean stillMain = currentTitle.equals(mainTitle) || currentTitle.startsWith(mainViewingPrefix);
            if (!stillMain) {
                plugin.getViewingAsPlayer().remove(playerId);
                plugin.getMailboxPages().remove(playerId);
            }
        });
    }

    private void handleSentGuiClose(Player player, String title) {
        String sentTitle = plugin.legacy(config().getString("gui.sent-mail.title"));
        String sentViewingPrefix = plugin.legacy(config().getString("gui.sent-mail.title") + " &7(as ");
        if (!title.equals(sentTitle) && !title.startsWith(sentViewingPrefix)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        plugin.getFoliaLib().getScheduler().runNextTick(task -> {
            if (!player.isOnline()) {
                plugin.getSentMailboxPages().remove(playerId);
                return;
            }

            String currentTitle = player.getOpenInventory().getTitle();
            boolean stillSent = currentTitle.equals(sentTitle) || currentTitle.startsWith(sentViewingPrefix);
            if (!stillSent) {
                plugin.getSentMailboxPages().remove(playerId);
            }
        });
    }

    private void handleMailViewClose(Player player, String title) {
        String mailViewTitle = plugin.legacy(config().getString("gui.mail-view.title"));
        if (!title.equals(mailViewTitle)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        plugin.getFoliaLib().getScheduler().runNextTick(task -> {
            if (!player.isOnline()) {
                plugin.getMailViewPages().remove(playerId);
                return;
            }

            String currentTitle = player.getOpenInventory().getTitle();
            if (!mailViewTitle.equals(currentTitle)) {
                plugin.getMailViewPages().remove(playerId);
            }
        });
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

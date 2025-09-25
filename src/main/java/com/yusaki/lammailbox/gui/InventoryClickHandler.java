package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.session.MailCreationSession;
import com.yusaki.lammailbox.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

public class InventoryClickHandler implements MailInventoryHandler {
    private final LamMailBox plugin;
    private final NamespacedKey decorationKey;

    public InventoryClickHandler(LamMailBox plugin) {
        this.plugin = plugin;
        this.decorationKey = new NamespacedKey(plugin, "decorationPath");
    }


    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        ItemStack clicked = event.getCurrentItem();

        String mainTitle = plugin.colorize(config().getString("gui.main.title"));
        String mainViewingPrefix = plugin.colorize(config().getString("gui.main.title") + " &7(as ");
        boolean isMainGUI = title.equals(mainTitle) || title.startsWith(mainViewingPrefix);

        if (isMainGUI && event.getClickedInventory() == player.getInventory()) {
            event.setCancelled(true);
            return;
        }
        if (clicked == null || event.getClickedInventory() == event.getWhoClicked().getInventory()) {
            return;
        }

        String sentTitle = plugin.colorize(config().getString("gui.sent-mail.title"));
        String sentViewingPrefix = plugin.colorize(config().getString("gui.sent-mail.title") + " &7(as ");

        if (isMainGUI) {
            handleMainGuiClick(event, player, clicked);
        } else if (title.equals(sentTitle) || title.startsWith(sentViewingPrefix)) {
            handleSentMailGuiClick(event, player, clicked);
        } else if (title.equals(plugin.colorize(config().getString("gui.sent-mail-view.title")))) {
            handleSentMailViewClick(event, player, clicked);
        } else if (title.equals(plugin.colorize(config().getString("gui.create-mail.title")))) {
            handleCreateMailGuiClick(event);
        } else if (title.equals(plugin.colorize(config().getString("gui.items.title")))) {
            handleItemsGuiClick(event);
        } else if (title.equals(plugin.colorize(config().getString("gui.mail-view.title")))) {
            handleMailViewClick(event, player, clicked);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();
        UUID playerId = player.getUniqueId();

        if (title.equals(plugin.colorize(config().getString("gui.items.title")))) {
            onItemsGuiClose(event, player);
        } else if (title.equals(plugin.colorize(config().getString("gui.create-mail.title")))) {
            onCreateGuiClose(event, player);
        }

        if (!plugin.getAwaitingInput().containsKey(playerId)) {
            plugin.getInMailCreation().remove(playerId);
        }
        plugin.getDeleteConfirmations().remove(playerId);

        String mainTitle = plugin.colorize(config().getString("gui.main.title"));
        String mainViewingPrefix = plugin.colorize(config().getString("gui.main.title") + " &7(as ");
        boolean isMainGUI = title.equals(mainTitle) || title.startsWith(mainViewingPrefix);
        if (isMainGUI) {
            plugin.getViewingAsPlayer().remove(playerId);
        }
    }

    private void handleMainGuiClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (executeDecorationCommand(event, player, clicked)) {
            return;
        }
        if (isEnabled("gui.main.items.create-mail") &&
                event.getSlot() == config().getInt("gui.main.items.create-mail.slot")) {
            String viewingAs = plugin.getViewingAsPlayer().get(player.getUniqueId());
            if (viewingAs != null) {
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
            String mailId = getMailId(clicked);
            if (mailId != null) {
                plugin.openMailView(player, mailId);
            }
        }
    }

    private void handleSentMailGuiClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (executeDecorationCommand(event, player, clicked)) {
            return;
        }
        if (isEnabled("gui.sent-mail.items.back-button") &&
                event.getSlot() == config().getInt("gui.sent-mail.items.back-button.slot")) {
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
            return;
        }
        if (isEnabled("gui.sent-mail.items.sent-mail-display") &&
                config().getIntegerList("gui.sent-mail.items.sent-mail-display.slots").contains(event.getSlot())) {
            String mailId = getMailId(clicked);
            if (mailId != null) {
                plugin.openSentMailView(player, mailId);
            }
        }
    }

    private void handleSentMailViewClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (executeDecorationCommand(event, player, clicked)) {
            return;
        }
        if (isEnabled("gui.sent-mail-view.items.delete-button") &&
                event.getSlot() == config().getInt("gui.sent-mail-view.items.delete-button.slot")) {
            String mailId = getMailId(clicked);
            if (mailId != null) {
                plugin.handleSentMailDelete(player, mailId);
            }
        }
    }

    private void handleCreateMailGuiClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.hasPermission(config().getString("settings.admin-permission")) &&
                event.getClickedInventory() == player.getInventory()) {
            return;
        }
        event.setCancelled(true);

        Player p = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        if (executeDecorationCommand(event, p, clicked)) {
            return;
        }
        MailCreationSession session = plugin.getMailSessions().get(p.getUniqueId());
        if (session == null) {
            return;
        }

        int slot = event.getSlot();
        if (isEnabled("gui.create-mail.items.receiver-head") &&
                slot == config().getInt("gui.create-mail.items.receiver-head.slot")) {
            onReceiverHeadClick(p);
        } else if (isEnabled("gui.create-mail.items.message-paper") &&
                slot == config().getInt("gui.create-mail.items.message-paper.slot")) {
            onMessagePaperClick(p);
        } else if (isEnabled("gui.create-mail.items.items-chest") &&
                slot == config().getInt("gui.create-mail.items.items-chest.slot")) {
            if (!p.hasPermission(config().getString("settings.permissions.add-items"))) {
                p.sendMessage(plugin.colorize(config().getString("messages.no-permission")));
                return;
            }
            plugin.openItemsGUI(p);
        } else if (isEnabled("gui.create-mail.items.send-button") &&
                slot == config().getInt("gui.create-mail.items.send-button.slot")) {
            plugin.handleMailSend(p);
        } else if (isEnabled("gui.create-mail.items.command-block") &&
                slot == config().getInt("gui.create-mail.items.command-block.slot")) {
            if (event.getClick().isRightClick()) {
                removeLastCommandAndRefresh(p, session);
            } else {
                handleCommandBlockItem(p, event, session);
            }
        } else if (isEnabled("gui.create-mail.items.schedule-clock") &&
                slot == config().getInt("gui.create-mail.items.schedule-clock.slot")) {
            handleScheduleClockClick(event, p);
        }
    }

    private void handleItemsGuiClick(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && executeDecorationCommand(event, (Player) event.getWhoClicked(), clicked)) {
            return;
        }
        if (isEnabled("gui.items.items.save-button") &&
                event.getSlot() == config().getInt("gui.items.items.save-button.slot")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
            if (session != null) {
                List<ItemStack> items = new ArrayList<>();
                Inventory inv = event.getInventory();
                for (int i = 0; i < inv.getSize() - 1; i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && i != config().getInt("gui.items.items.save-button.slot")) {
                        items.add(item.clone());
                    }
                }
                session.setItems(items);
                player.closeInventory();
            }
        }
    }

    private void handleMailViewClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (executeDecorationCommand(event, player, clicked)) {
            return;
        }
        int buttonSlot = config().getInt("gui.mail-view.items.claim-button.slot");
        if ((isEnabled("gui.mail-view.items.claim-button") || isEnabled("gui.mail-view.items.dismiss-button"))
                && event.getSlot() == buttonSlot) {
            String mailId = getMailId(clicked);
            if (mailId != null) {
                handleMailClaim(player, mailId);
            }
        }
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }

    private boolean executeDecorationCommand(InventoryClickEvent event, Player player, ItemStack clicked) {
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
                String command = template
                        .replace("%player%", playerName)
                        .replace("%uuid%", uuid);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });
        return true;
    }

    private void onReceiverHeadClick(Player player) {
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        player.closeInventory();
        plugin.getAwaitingInput().put(player.getUniqueId(), "receiver");
        plugin.getMailCreationController().showInputTitle(player, "receiver");
        player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.enter-receiver")));
    }

    private void onMessagePaperClick(Player player) {
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        player.closeInventory();
        plugin.getAwaitingInput().put(player.getUniqueId(), "message");
        plugin.getMailCreationController().showInputTitle(player, "message");
        player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") +
                plugin.getConfig().getString("messages.enter-message")));
    }

    private void handleScheduleClockClick(InventoryClickEvent event, Player player) {
        if (!player.hasPermission(config().getString("settings.admin-permission"))) {
            return;
        }
        player.closeInventory();
        if (event.getClick().isRightClick()) {
            plugin.getAwaitingInput().put(player.getUniqueId(), "expire-date");
            plugin.getMailCreationController().showInputTitle(player, "expire");
            player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") +
                    plugin.getConfig().getString("messages.enter-expire-date")));
        } else {
            plugin.getAwaitingInput().put(player.getUniqueId(), "schedule-date");
            plugin.getMailCreationController().showInputTitle(player, "schedule");
            player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") +
                    plugin.getConfig().getString("messages.enter-schedule-date")));
        }
    }

    private void removeLastCommandAndRefresh(Player player, MailCreationSession session) {
        List<String> commands = new ArrayList<>(session.getCommands());
        if (commands.isEmpty()) {
            return;
        }
        commands.remove(commands.size() - 1);
        session.setCommands(commands);

        ItemStack commandBlock = session.getCommandBlock();
        if (commandBlock == null) {
            return;
        }
        ItemStack displayBlock = commandBlock.clone();
        ItemMeta meta = displayBlock.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(plugin.colorize(config().getString("gui.create-mail.items.command-block.commands-prefix")));
        for (String cmd : commands) {
            lore.add(plugin.colorize(config().getString("gui.create-mail.items.command-block.command-format")
                    .replace("%command%", cmd)));
        }
        meta.setLore(lore);
        displayBlock.setItemMeta(meta);
        session.setDisplayCommandBlock(displayBlock);
        plugin.openCreateMailGUI(player);
    }

    private void handleCommandBlockItem(Player player, InventoryClickEvent event, MailCreationSession session) {
        if (!player.hasPermission(config().getString("settings.admin-permission"))) {
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            List<String> commands = new ArrayList<>(session.getCommands());
            session.setCommandBlock(cursor.clone());
            ItemStack displayBlock = cursor.clone();
            ItemMeta meta = displayBlock.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(plugin.colorize(config().getString("messages.command-prefix")));
            for (String cmd : commands) {
                lore.add(plugin.colorize(config().getString("messages.command-format-display")
                        .replace("%command%", cmd)));
            }
            meta.setLore(lore);
            displayBlock.setItemMeta(meta);
            session.setDisplayCommandBlock(displayBlock);
            event.getView().setCursor(null);
            plugin.openCreateMailGUI(player);
        } else {
            plugin.getAwaitingInput().put(player.getUniqueId(), "command");
            plugin.getMailCreationController().showInputTitle(player, "command");
            player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") +
                    plugin.getConfig().getString("messages.enter-command")));
            player.closeInventory();
        }
    }

    private void handleMailClaim(Player player, String mailId) {
        String dbPath = "mails." + mailId + ".";

        List<String> serializedItems = database().getStringList(dbPath + "items");
        List<ItemStack> items = serializedItems.stream()
                .map(ItemSerialization::deserializeItem)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<String> commands = database().getStringList(dbPath + "commands");
        boolean hasRewards = !items.isEmpty() || !commands.isEmpty();

        if (!items.isEmpty()) {
            long emptySlots = Arrays.stream(player.getInventory().getStorageContents())
                    .filter(item -> item == null || item.getType() == Material.AIR)
                    .count();
            if (emptySlots < items.size()) {
                player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") +
                        plugin.getConfig().getString("messages.inventory-space-needed")
                                .replace("%amount%", String.valueOf(items.size()))));
                return;
            }

            items.forEach(item -> player.getInventory().addItem(item));
        }

        if (!commands.isEmpty()) {
            plugin.getFoliaLib().getScheduler().runNextTick(task -> {
                for (String command : commands) {
                    String processed = command.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
                }
            });
        }

        plugin.getMailService().claimMail(player, mailId);
        player.closeInventory();
        plugin.openMainGUI(player);
        String messageKey = hasRewards ? "messages.items-claimed" : "messages.no-items-in-mail";
        player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") +
                plugin.getConfig().getString(messageKey)));
    }

    private void onItemsGuiClose(InventoryCloseEvent event, Player player) {
        MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
        if (session != null) {
            List<ItemStack> items = new ArrayList<>();
            Material saveButtonMaterial = Material.valueOf(config().getString("gui.items.items.save-button.material"));
            for (int i = 0; i < event.getInventory().getSize() - 1; i++) {
                ItemStack item = event.getInventory().getItem(i);
                if (item != null && item.getType() != saveButtonMaterial) {
                    items.add(item.clone());
                }
            }
            session.setItems(items);
            plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCreateMailGUI(player));
        }
    }

    private void onCreateGuiClose(InventoryCloseEvent event, Player player) {
        MailCreationSession session = plugin.getMailSessions().get(player.getUniqueId());
        if (session != null && !player.hasPermission(config().getString("settings.admin-permission"))) {
            if (!plugin.getInMailCreation().getOrDefault(player.getUniqueId(), false)) {
                List<ItemStack> items = session.getItems();
                for (ItemStack item : items) {
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                    leftover.values().forEach(leftItem ->
                            player.getWorld().dropItem(player.getLocation(), leftItem));
                }
                session.setItems(new ArrayList<>());
            }
        }
    }

    private String getMailId(ItemStack item) {
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

    private FileConfiguration database() {
        return plugin.getMailRepository().getBackingConfiguration();
    }
}

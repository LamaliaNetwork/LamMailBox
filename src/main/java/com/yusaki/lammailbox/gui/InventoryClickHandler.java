package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import com.yusaki.lammailbox.repository.MailRecord;
import com.yusaki.lammailbox.session.MailCreationSession;
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
    private final NamespacedKey commandItemIndexKey;
    private final NamespacedKey commandItemActionKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey paginationTargetKey;

    public InventoryClickHandler(LamMailBox plugin) {
        this.plugin = plugin;
        this.decorationKey = new NamespacedKey(plugin, "decorationPath");
        this.commandItemIndexKey = new NamespacedKey(plugin, "commandItemIndex");
        this.commandItemActionKey = new NamespacedKey(plugin, "commandItemAction");
        this.actionKey = new NamespacedKey(plugin, "action");
        this.paginationTargetKey = new NamespacedKey(plugin, "paginationTarget");
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
        String commandItemsTitle = plugin.colorize(config().getString("gui.command-items-editor.title", "Command Items"));
        String commandItemCreatorTitle = plugin.colorize(config().getString("gui.command-item-creator.title", "Create Command Item"));

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
        } else if (title.equals(commandItemsTitle)) {
             handleCommandItemsEditorClick(event, player, clicked);
        } else if (title.equals(commandItemCreatorTitle)) {
             handleCommandItemCreatorClick(event, player, clicked);
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
            UUID capturedId = playerId;
            plugin.getFoliaLib().getScheduler().runNextTick(task -> {
                if (!player.isOnline()) {
                    plugin.getViewingAsPlayer().remove(capturedId);
                    plugin.getMailboxPages().remove(capturedId);
                    return;
                }

                String currentTitle = player.getOpenInventory().getTitle();
                boolean stillMain = currentTitle.equals(mainTitle) || currentTitle.startsWith(mainViewingPrefix);
                if (!stillMain) {
                    plugin.getViewingAsPlayer().remove(capturedId);
                    plugin.getMailboxPages().remove(capturedId);
                }
            });
        }

        String sentTitle = plugin.colorize(config().getString("gui.sent-mail.title"));
        String sentViewingPrefix = plugin.colorize(config().getString("gui.sent-mail.title") + " &7(as ");
        boolean isSentGUI = title.equals(sentTitle) || title.startsWith(sentViewingPrefix);
        if (isSentGUI) {
            UUID capturedId = playerId;
            plugin.getFoliaLib().getScheduler().runNextTick(task -> {
                if (!player.isOnline()) {
                    plugin.getSentMailboxPages().remove(capturedId);
                    return;
                }

                String currentTitle = player.getOpenInventory().getTitle();
                boolean stillSent = currentTitle.equals(sentTitle) || currentTitle.startsWith(sentViewingPrefix);
                if (!stillSent) {
                    plugin.getSentMailboxPages().remove(capturedId);
                }
            });
        }

        // Reset mail view pagination after we confirm the player is no longer in the mail view
        final String mailViewTitle = plugin.colorize(config().getString("gui.mail-view.title"));
        if (title.equals(mailViewTitle)) {
            UUID capturedId = playerId;
            plugin.getFoliaLib().getScheduler().runNextTick(task -> {
                if (!player.isOnline()) {
                    plugin.getMailViewPages().remove(capturedId);
                    return;
                }

                String currentTitle = player.getOpenInventory().getTitle();
                if (!mailViewTitle.equals(currentTitle)) {
                    plugin.getMailViewPages().remove(capturedId);
                }
            });
        }
    }

    private void handleMainGuiClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (action != null && (action.equals("mailbox-page-prev") || action.equals("mailbox-page-next"))) {
                String targetName = meta.getPersistentDataContainer().get(paginationTargetKey, PersistentDataType.STRING);
                if (targetName == null || targetName.isBlank()) {
                    targetName = player.getName();
                }
                handleMailboxPageNavigation(player, targetName, action.equals("mailbox-page-next"));
                return;
            }
        }
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
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (action != null && (action.equals("sent-page-prev") || action.equals("sent-page-next"))) {
                String targetName = meta.getPersistentDataContainer().get(paginationTargetKey, PersistentDataType.STRING);
                if (targetName == null || targetName.isBlank()) {
                    targetName = player.getName();
                }
                handleSentMailboxPageNavigation(player, targetName, action.equals("sent-page-next"));
                return;
            }
        }
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
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta != null) {
                String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
                if ("sent-mail-view-back".equals(action)) {
                    plugin.openSentMailGUI(player);
                    return;
                }
            }
        }
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
        ItemMeta meta = clicked.getItemMeta();
        if (meta != null) {
            String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if ("create-back".equals(action)) {
                plugin.getInMailCreation().put(p.getUniqueId(), true);
                plugin.openMainGUI(p);
                return;
            }
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
            if (!p.hasPermission(config().getString("settings.admin-permission"))) {
                p.sendMessage(plugin.colorize(config().getString("messages.no-permission")));
                return;
            }
            plugin.openCommandItemsEditor(p);
        } else if (isEnabled("gui.create-mail.items.schedule-clock") &&
                slot == config().getInt("gui.create-mail.items.schedule-clock.slot")) {
            handleScheduleClockClick(event, p);
        }
    }

    private void handleItemsGuiClick(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta != null) {
                String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
                if ("items-back".equals(action)) {
                    event.setCancelled(true);
                    Player player = (Player) event.getWhoClicked();
                    player.closeInventory();
                    plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCreateMailGUI(player));
                    return;
                }
            }
        }
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

    private void handleCommandItemsEditorClick(InventoryClickEvent event, Player player, ItemStack clicked) {
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
            List<CommandItem> items = session.getCommandItems();
            if (index < 0 || index >= items.size()) {
                return;
            }
            if (event.isRightClick()) {
                items.remove((int) index);
                session.setCommandItems(items);
                plugin.openCommandItemsEditor(player);
            } else {
                session.setCommandItemEditIndex(index);
                session.setCommandItemDraft(items.get(index).toBuilder());
                plugin.openCommandItemCreator(player);
            }
            return;
        }

        if (action == null) {
            return;
        }

        switch (action) {
            case "add":
                session.setCommandItemEditIndex(null);
                session.setCommandItemDraft(new CommandItem.Builder());
                plugin.openCommandItemCreator(player);
                break;
            case "back":
                plugin.openCreateMailGUI(player);
                break;
        }
    }

    private void handleCommandItemCreatorClick(InventoryClickEvent event, Player player, ItemStack clicked) {
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
            return;
        }

        switch (action) {
            case "material":
                startAwaiting(player, "command-item-material", "command-item-material", config().getString("messages.enter-command-item-material"));
                break;
            case "name":
                startAwaiting(player, "command-item-name", "command-item-name", config().getString("messages.enter-command-item-name"));
                break;
            case "lore":
                if (event.isRightClick()) {
                    CommandItem.Builder draft = session.getCommandItemDraft();
                    if (draft != null && draft.removeLastLoreLine()) {
                        player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                                config().getString("messages.command-item-lore-removed", "&a✔ Removed last lore line.")));
                    } else {
                        player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                                config().getString("messages.command-item-lore-empty", "&c✖ No lore lines to remove.")));
                    }
                    plugin.openCommandItemCreator(player);
                } else {
                    startAwaiting(player, "command-item-lore", "command-item-lore", config().getString("messages.enter-command-item-lore"));
                }
                break;
            case "command":
                if (event.isRightClick()) {
                    CommandItem.Builder draft = session.getCommandItemDraft();
                    if (draft != null && draft.removeLastCommand()) {
                        player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                                config().getString("messages.command-item-command-removed", "&a✔ Removed last command.")));
                    } else {
                        player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                                config().getString("messages.command-item-command-empty", "&c✖ No commands to remove.")));
                    }
                    plugin.openCommandItemCreator(player);
                } else {
                    startAwaiting(player, "command-item-command", "command-item-command", config().getString("messages.enter-command-item-command"));
                }
                break;
            case "save":
                if (plugin.getMailCreationController().finalizeCommandItem(player, session)) {
                    plugin.openCommandItemsEditor(player);
                } else {
                    plugin.openCommandItemCreator(player);
                }
                break;
        }
    }

    private void handleMailViewClick(InventoryClickEvent event, Player player, ItemStack clicked) {
        event.setCancelled(true);

        // Handle pagination FIRST (before decorations, as pagination buttons may have decoration tags)
        if (clicked != null && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (action != null) {
                if ("mail-view-back".equals(action)) {
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
                    return;
                }
                if (action.equals("page-prev") || action.equals("page-next")) {
                    String mailId = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "mailId"),
                            PersistentDataType.STRING);
                    if (mailId != null) {
                        handlePageNavigation(player, mailId, action.equals("page-next"));
                        return;
                    }
                }
            }
        }

        // Then check for decoration commands
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
            Player target = Bukkit.getPlayerExact(targetName);
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

    private void handlePageNavigation(Player player, String mailId, boolean isNext) {
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

    private void handleMailClaim(Player player, String mailId) {
        List<ItemStack> items = plugin.getMailRepository().loadMailItems(mailId);
        Optional<MailRecord> mailRecord = plugin.getMailRepository().findRecord(mailId);
        List<CommandItem> commandItems = mailRecord.map(MailRecord::commandItems).orElse(Collections.emptyList());
        boolean hasRewards = !items.isEmpty() || !commandItems.isEmpty();

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

        if (!commandItems.isEmpty()) {
            plugin.getFoliaLib().getScheduler().runNextTick(task -> {
                for (CommandItem commandItem : commandItems) {
                    for (String command : commandItem.commands()) {
                        String processed = command.replace("%player%", player.getName());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
                    }
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

    private void ensureCommandItemsInitialized(MailCreationSession session) {
        if (session.getCommandItems() == null) {
            session.setCommandItems(new ArrayList<>());
        }
    }

    private void startAwaiting(Player player, String key, String titleKey, String message) {
        plugin.getAwaitingInput().put(player.getUniqueId(), key);
        plugin.getInMailCreation().put(player.getUniqueId(), true);
        plugin.getMailCreationController().showInputTitle(player, titleKey);
        if (message != null) {
            player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix") + message));
        }
        player.closeInventory();
    }
}

package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import com.yusaki.lammailbox.repository.MailRecord;
import com.yusaki.lammailbox.session.MailCreationSession;
import com.yusaki.lammailbox.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.Date;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Builds GUI inventories from configuration so the main plugin class can stay small.
 */
public class ConfigMailGuiFactory implements MailGuiFactory {
    private final LamMailBox plugin;
    private final NamespacedKey decorationKey;
    private final NamespacedKey commandItemIndexKey;
    private final NamespacedKey commandItemActionKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey mailIdKey;
    private final NamespacedKey paginationTargetKey;

    public ConfigMailGuiFactory(LamMailBox plugin) {
        this.plugin = plugin;
        this.decorationKey = new NamespacedKey(plugin, "decorationPath");
        this.commandItemIndexKey = new NamespacedKey(plugin, "commandItemIndex");
        this.commandItemActionKey = new NamespacedKey(plugin, "commandItemAction");
        this.actionKey = new NamespacedKey(plugin, "action");
        this.mailIdKey = new NamespacedKey(plugin, "mailId");
        this.paginationTargetKey = new NamespacedKey(plugin, "paginationTarget");
    }

    private enum PaginationButtonType {
        PREVIOUS,
        NEXT,
        INDICATOR
    }

    private record PaginationSettings(String basePath,
                                       Set<Integer> reservedSlots,
                                       String previousAction,
                                       String nextAction,
                                       BiConsumer<ItemMeta, PaginationButtonType> metaCustomizer) {
    }

    private ItemStack styledCommandIcon(String basePath, ItemStack existing, int commandCount, String summary) {
        ItemStack stack = existing != null ? existing.clone() : null;

        String materialName = config().getString(basePath + ".material");
        if (materialName != null && !materialName.isBlank()) {
            try {
                Material material = Material.valueOf(materialName.trim().toUpperCase(Locale.ROOT));
                if (stack == null || stack.getType() != material) {
                    stack = new ItemStack(material);
                }
            } catch (IllegalArgumentException ignored) {
                if (stack == null) {
                    stack = new ItemStack(Material.COMMAND_BLOCK);
                }
            }
        } else if (stack == null) {
            stack = new ItemStack(Material.COMMAND_BLOCK);
        }

        if (stack == null) {
            stack = new ItemStack(Material.COMMAND_BLOCK);
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String displayName = config().getString(basePath + ".name");
            if (displayName != null && !displayName.isBlank()) {
                meta.setDisplayName(plugin.colorize(displayName
                        .replace("%count%", String.valueOf(commandCount))
                        .replace("%summary%", summary)));
            }

            List<String> rawLore = config().getStringList(basePath + ".lore");
            if (rawLore == null || rawLore.isEmpty()) {
                rawLore = Collections.singletonList("&7Contains hidden console actions");
            }
            List<String> lore = rawLore.stream()
                    .map(line -> plugin.colorize(line
                            .replace("%count%", String.valueOf(commandCount))
                            .replace("%summary%", summary)))
                    .collect(Collectors.toList());
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void placeItemsAndCommands(Inventory inv,
                                       List<ItemStack> items,
                                       List<CommandItem> commandItems,
                                       String itemPath,
                                       String commandPath,
                                       int currentPage,
                                       int slotsPerPage) {
        List<Integer> itemSlots = config().getIntegerList(itemPath + ".slots");
        if (itemSlots == null || itemSlots.isEmpty()) {
            return;
        }

        // Combine items and commands into a single list for pagination
        List<Object> allElements = new ArrayList<>();
        allElements.addAll(items != null ? items : Collections.emptyList());
        if (commandItems != null && !commandItems.isEmpty() && isEnabled(commandPath)) {
            allElements.addAll(commandItems);
        }

        // Calculate start and end indices for current page
        int startIndex = (currentPage - 1) * slotsPerPage;
        int endIndex = Math.min(startIndex + slotsPerPage, allElements.size());

        // Place elements for current page
        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < itemSlots.size(); i++) {
            Object element = allElements.get(i);

            if (element instanceof ItemStack) {
                // Place actual item
                inv.setItem(itemSlots.get(slotIndex++), (ItemStack) element);
            } else if (element instanceof CommandItem commandItem) {
                ItemStack commandIcon = createCommandPlaceholder(commandPath, commandItem);
                inv.setItem(itemSlots.get(slotIndex++), commandIcon);
            }
        }
    }

    private ItemStack createCommandPlaceholder(String commandPath, CommandItem commandItem) {
        ItemStack base = commandItem.toPreviewItem(plugin);

        // Check if this is a legacy command (single-line command with default styling)
        boolean isLegacy = commandItem.displayName() != null &&
                           commandItem.displayName().equals("&6Console Command");

        if (isLegacy) {
            // Apply config overrides for legacy commands
            String legacyPath = commandPath.replace("command-item", "command-legacy");
            ItemMeta meta = base.getItemMeta();
            if (meta != null) {
                Map<String, String> placeholders = createCommandItemPlaceholders(commandItem);

                String overrideName = config().getString(legacyPath + ".name");
                if (overrideName != null && !overrideName.isBlank()) {
                    meta.setDisplayName(plugin.colorize(applyPlaceholders(overrideName, placeholders)));
                }

                List<String> configuredLore = config().getStringList(legacyPath + ".lore");
                if (!configuredLore.isEmpty()) {
                    List<String> lore = configuredLore.stream()
                            .map(line -> plugin.colorize(applyPlaceholders(line, placeholders)))
                            .collect(Collectors.toList());
                    meta.setLore(lore);
                }

                base.setItemMeta(meta);
            }
        }

        // User-created CommandItems are returned as-is
        return base;
    }

    private void addPaginationButtons(Inventory inv,
                                      String mailId,
                                      int currentPage,
                                      int totalPages,
                                      Integer claimSlot,
                                      Integer dismissSlot) {
        Set<Integer> reservedSlots = new HashSet<>();
        if (claimSlot != null) {
            reservedSlots.add(claimSlot);
        }
        if (dismissSlot != null) {
            reservedSlots.add(dismissSlot);
        }

        PaginationSettings settings = new PaginationSettings(
                "gui.mail-view.items.pagination",
                reservedSlots,
                "page-prev",
                "page-next",
                (meta, type) -> {
                    if (type != PaginationButtonType.INDICATOR) {
                        meta.getPersistentDataContainer().set(mailIdKey, PersistentDataType.STRING, mailId);
                    }
                }
        );

        addPaginationButtons(inv, settings, currentPage, totalPages);
    }

    private void addPaginationButtons(Inventory inv,
                                      PaginationSettings settings,
                                      int currentPage,
                                      int totalPages) {
        if (totalPages <= 1) {
            return;
        }

        String basePath = settings.basePath();
        Set<Integer> reservedSlots = settings.reservedSlots() != null
                ? new HashSet<>(settings.reservedSlots())
                : new HashSet<>();

        if (isEnabled(basePath + ".previous-button") && currentPage > 1) {
            placePaginationButton(inv,
                    basePath + ".previous-button",
                    "ARROW",
                    "&e← Previous",
                    36,
                    PaginationButtonType.PREVIOUS,
                    currentPage,
                    totalPages,
                    settings.previousAction(),
                    reservedSlots,
                    settings.metaCustomizer());
        }

        if (isEnabled(basePath + ".next-button") && currentPage < totalPages) {
            placePaginationButton(inv,
                    basePath + ".next-button",
                    "ARROW",
                    "&eNext →",
                    44,
                    PaginationButtonType.NEXT,
                    currentPage,
                    totalPages,
                    settings.nextAction(),
                    reservedSlots,
                    settings.metaCustomizer());
        }

        if (isEnabled(basePath + ".page-indicator")) {
            placePaginationButton(inv,
                    basePath + ".page-indicator",
                    "BOOK",
                    "&6Page %current%/%total%",
                    40,
                    PaginationButtonType.INDICATOR,
                    currentPage,
                    totalPages,
                    null,
                    reservedSlots,
                    settings.metaCustomizer());
        }
    }

    private void placePaginationButton(Inventory inv,
                                       String path,
                                       String defaultMaterial,
                                       String defaultName,
                                       int defaultSlot,
                                       PaginationButtonType type,
                                       int currentPage,
                                       int totalPages,
                                       String action,
                                       Set<Integer> reservedSlots,
                                       BiConsumer<ItemMeta, PaginationButtonType> metaCustomizer) {
        int preferred = config().getInt(path + ".slot", defaultSlot);
        String materialName = config().getString(path + ".material", defaultMaterial);
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            return;
        }

        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = config().getString(path + ".name", defaultName);
        if (name != null) {
            if (type == PaginationButtonType.INDICATOR) {
                name = name.replace("%current%", String.valueOf(currentPage))
                        .replace("%total%", String.valueOf(totalPages));
            }
            meta.setDisplayName(plugin.colorize(name));
        }

        if (type != PaginationButtonType.INDICATOR && action != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }

        if (metaCustomizer != null) {
            metaCustomizer.accept(meta, type);
        }

        applyItemMetaCustomizations(meta, path);
        button.setItemMeta(meta);
        Integer targetSlot = findAvailableSlot(inv, preferred, reservedSlots);
        if (targetSlot != null) {
            inv.setItem(targetSlot, button);
        }
    }


    private void applyItemMetaCustomizations(ItemMeta meta, String basePath) {
        if (meta == null || basePath == null || basePath.isEmpty()) {
            return;
        }

        Object loreValue = config().get(basePath + ".lore");
        List<String> loreLines = new ArrayList<>();
        if (loreValue instanceof String singleLine && !singleLine.isBlank()) {
            loreLines.add(singleLine);
        } else if (loreValue instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry != null) {
                    String line = entry.toString();
                    if (!line.isBlank()) {
                        loreLines.add(line);
                    }
                }
            }
        }
        if (!loreLines.isEmpty()) {
            List<String> colorized = loreLines.stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList());
            meta.setLore(colorized);
        }

        if (config().contains(basePath + ".custom-model-data")) {
            Object rawValue = config().get(basePath + ".custom-model-data");
            Integer customModelData = null;
            if (rawValue instanceof Number number) {
                customModelData = number.intValue();
            } else if (rawValue instanceof String text && !text.isBlank()) {
                try {
                    customModelData = Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    customModelData = null;
                }
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
        }
    }

    private void placeBackButton(Inventory inv, String path, String action) {
        if (!isEnabled(path)) {
            return;
        }
        String materialName = config().getString(path + ".material", "ARROW");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.ARROW;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = config().getString(path + ".name", "&cBack");
        meta.setDisplayName(plugin.colorize(name));

        List<String> lore = config().getStringList(path + ".lore").stream()
                .map(plugin::colorize)
                .collect(Collectors.toList());
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }

        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        applyItemMetaCustomizations(meta, path);
        item.setItemMeta(meta);
        int slot = config().getInt(path + ".slot", inv.getSize() - 1);
        inv.setItem(slot, item);
    }


    private Integer findAvailableSlot(Inventory inv,
                                      int preferred,
                                      Set<Integer> reservedSlots) {
        int inventorySize = inv.getSize();
        int[] offsets = {0, -1, 1, -2, 2, -3, 3, -4, 4};
        for (int offset : offsets) {
            int candidate = preferred + offset;
            if (candidate < 0 || candidate >= inventorySize) {
                continue;
            }
            if (reservedSlots.contains(candidate)) {
                continue;
            }
            ItemStack existing = inv.getItem(candidate);
            if (existing != null) {
                ItemMeta meta = existing.getItemMeta();
                if (meta == null || !meta.getPersistentDataContainer().has(decorationKey, PersistentDataType.STRING)) {
                    continue;
                }
            }
            return candidate;
        }
        plugin.getLogger().warning("Unable to place pagination control; no free slot near " + preferred);
        return null;
    }

    private String summarizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "Console";
        }

        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 3 && parts[0].equalsIgnoreCase("give")) {
            String item = parts[2].replace("%player%", "@p");
            int amount = 1;
            if (parts.length >= 4) {
                try {
                    amount = Integer.parseInt(parts[3]);
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            return item + "/" + amount;
        }

        String main = parts[0];
        if (parts.length > 1) {
            return main + " " + parts[1].replace("%player%", "@p");
        }
        return main;
    }

    @Override
    public Inventory createMailbox(Player viewer) {
        int size = config().getInt("gui.main.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString("gui.main.title")));
        addDecorations(inv, "gui.main");

        addCreateMailButton(inv, viewer, viewer);
        addSentMailButton(inv);
        loadPlayerMails(viewer, viewer, inv);
        return inv;
    }

    @Override
    public Inventory createMailboxAs(Player admin, Player target) {
        int size = config().getInt("gui.main.size");
        String title = config().getString("gui.main.title") + " &7(as " + target.getName() + ")";
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(title));
        addDecorations(inv, "gui.main");

        addCreateMailButton(inv, admin, target);
        addSentMailButton(inv);
        loadPlayerMails(admin, target, inv);
        return inv;
    }

    @Override
    public Inventory createSentMailbox(Player viewer) {
        int size = config().getInt("gui.sent-mail.size");
        String title = config().getString("gui.sent-mail.title");
        String viewingAs = plugin.getViewingAsPlayer().get(viewer.getUniqueId());
        if (viewingAs != null) {
            title += " &7(as " + viewingAs + ")";
        }
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(title));
        addDecorations(inv, "gui.sent-mail");

        addBackButton(inv);
        loadSentMails(viewer, inv);
        return inv;
    }

    @Override
    public Inventory createSentMailView(Player viewer, String mailId) {
        int size = config().getInt("gui.sent-mail-view.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString("gui.sent-mail-view.title")));
        addDecorations(inv, "gui.sent-mail-view");

        Optional<MailRecord> recordOpt = plugin.getMailRepository().findRecord(mailId);
        if (recordOpt.isEmpty()) {
            return inv;
        }

        MailRecord record = recordOpt.get();
        String receiver = Optional.ofNullable(record.receiver()).orElse("");
        String message = record.message().replace("\\n", "\n");

        if (isEnabled("gui.sent-mail-view.items.receiver-head")) {
            ItemStack head = new ItemStack(Material.valueOf(config().getString("gui.sent-mail-view.items.receiver-head.material")));
            SkullMeta headMeta = (SkullMeta) head.getItemMeta();
            headMeta.setDisplayName(plugin.colorize(config().getString("gui.sent-mail-view.items.receiver-head.name")
                    .replace("%receiver%", receiver)));
            // Only set owning player if receiver is not empty and contains valid characters
            if (receiver != null && !receiver.trim().isEmpty() && receiver.matches("^[a-zA-Z0-9_]{1,16}$")) {
                headMeta.setOwningPlayer(Bukkit.getOfflinePlayer(receiver));
            }
            head.setItemMeta(headMeta);
            inv.setItem(config().getInt("gui.sent-mail-view.items.receiver-head.slot"), head);
        }

        if (isEnabled("gui.sent-mail-view.items.message")) {
            ItemStack messageItem = new ItemStack(Material.valueOf(config().getString("gui.sent-mail-view.items.message.material")));
            ItemMeta messageMeta = messageItem.getItemMeta();
            messageMeta.setDisplayName(plugin.colorize(config().getString("gui.sent-mail-view.items.message.name")));
            List<String> messageLore = Arrays.stream(message.split("\n"))
                    .map(line -> plugin.colorize("&f" + line))
                    .collect(Collectors.toList());
            messageMeta.setLore(messageLore);
            messageItem.setItemMeta(messageMeta);
            inv.setItem(config().getInt("gui.sent-mail-view.items.message.slot"), messageItem);
        }

        List<ItemStack> items = plugin.getMailRepository().loadMailItems(mailId);
        List<Integer> itemSlots = config().getIntegerList("gui.sent-mail-view.items.items-display.slots");
        int slotsPerPage = itemSlots != null && !itemSlots.isEmpty() ? itemSlots.size() : 21;
        placeItemsAndCommands(inv,
                items,
                record.commandItems(),
                "gui.sent-mail-view.items.items-display",
                "gui.sent-mail-view.items.command-item",
                1,
                slotsPerPage);

        placeBackButton(inv, "gui.sent-mail-view.items.back-button", "sent-mail-view-back");

        if (viewer.hasPermission(config().getString("settings.permissions.delete"))
                && isEnabled("gui.sent-mail-view.items.delete-button")) {
            ItemStack deleteButton = new ItemStack(Material.valueOf(config().getString("gui.sent-mail-view.items.delete-button.material")));
            ItemMeta deleteMeta = deleteButton.getItemMeta();
            deleteMeta.setDisplayName(plugin.colorize(config().getString("gui.sent-mail-view.items.delete-button.name")));
            deleteMeta.setLore(config().getStringList("gui.sent-mail-view.items.delete-button.lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList()));
            deleteMeta.getPersistentDataContainer().set(mailIdKey,
                    PersistentDataType.STRING, mailId);
            deleteButton.setItemMeta(deleteMeta);
            inv.setItem(config().getInt("gui.sent-mail-view.items.delete-button.slot"), deleteButton);
        }

        return inv;
    }


    @Override
    public Inventory createMailView(Player viewer, String mailId) {
        int size = config().getInt("gui.mail-view.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString("gui.mail-view.title")));
        addDecorations(inv, "gui.mail-view");

        Optional<MailRecord> recordOpt = plugin.getMailRepository().findRecord(mailId);
        if (recordOpt.isEmpty()) {
            return inv;
        }

        MailRecord record = recordOpt.get();
        String sender = Optional.ofNullable(record.sender()).orElse("Console");
        String message = record.message().replace("\\n", "\n");

        if (isEnabled("gui.mail-view.items.sender-head")) {
            ItemStack head = new ItemStack(Material.valueOf(config().getString("gui.mail-view.items.sender-head.material")));
            SkullMeta headMeta = (SkullMeta) head.getItemMeta();
            headMeta.setDisplayName(plugin.colorize(config().getString("gui.mail-view.items.sender-head.name")
                    .replace("%sender%", sender)));
            // Only set owning player if sender is not empty and contains valid characters
            if (sender != null && !sender.trim().isEmpty() && sender.matches("^[a-zA-Z0-9_]{1,16}$")) {
                headMeta.setOwningPlayer(Bukkit.getOfflinePlayer(sender));
            }
            head.setItemMeta(headMeta);
            inv.setItem(config().getInt("gui.mail-view.items.sender-head.slot"), head);
        }

        if (isEnabled("gui.mail-view.items.message")) {
            ItemStack messageItem = new ItemStack(Material.valueOf(config().getString("gui.mail-view.items.message.material")));
            ItemMeta messageMeta = messageItem.getItemMeta();
            messageMeta.setDisplayName(plugin.colorize(config().getString("gui.mail-view.items.message.name")));
            List<String> messageLore = Arrays.stream(message.split("\n"))
                    .map(line -> plugin.colorize("&f" + line))
                    .collect(Collectors.toList());
            messageMeta.setLore(messageLore);
            messageItem.setItemMeta(messageMeta);
            inv.setItem(config().getInt("gui.mail-view.items.message.slot"), messageItem);
        }

        List<ItemStack> items = plugin.getMailRepository().loadMailItems(mailId);

        // Get current page for this player (default to 1)
        int currentPage = plugin.getMailViewPages().getOrDefault(viewer.getUniqueId(), 1);

        // Calculate pagination
        int totalElements = items.size() + record.commandItems().size();
        List<Integer> itemSlots = config().getIntegerList("gui.mail-view.items.items-display.slots");
        int slotsPerPage = itemSlots != null && !itemSlots.isEmpty() ? itemSlots.size() : 21;
        int totalPages = (totalElements + slotsPerPage - 1) / slotsPerPage; // Ceiling division

        // Ensure current page is valid
        if (currentPage < 1) currentPage = 1;
        if (currentPage > totalPages && totalPages > 0) currentPage = totalPages;
        plugin.getMailViewPages().put(viewer.getUniqueId(), currentPage);

        placeItemsAndCommands(inv,
                items,
                record.commandItems(),
                "gui.mail-view.items.items-display",
                "gui.mail-view.items.command-item",
                currentPage,
                slotsPerPage);

        int claimSlotConfig = config().getInt("gui.mail-view.items.claim-button.slot");
        Integer claimSlot = isEnabled("gui.mail-view.items.claim-button") ? claimSlotConfig : null;

        int dismissSlotConfig = config().getInt("gui.mail-view.items.dismiss-button.slot", claimSlotConfig);
        Integer dismissSlot = isEnabled("gui.mail-view.items.dismiss-button") ? dismissSlotConfig : null;

        // Add pagination buttons
        addPaginationButtons(inv, mailId, currentPage, totalPages, claimSlot, dismissSlot);

        if (claimSlot != null) {
            ItemStack claimButton = new ItemStack(Material.valueOf(config().getString("gui.mail-view.items.claim-button.material")));
            ItemMeta claimMeta = claimButton.getItemMeta();
            claimMeta.setDisplayName(plugin.colorize(config().getString("gui.mail-view.items.claim-button.name")));
            claimMeta.setLore(config().getStringList("gui.mail-view.items.claim-button.lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList()));
            claimMeta.getPersistentDataContainer().set(mailIdKey,
                    PersistentDataType.STRING, mailId);
            claimButton.setItemMeta(claimMeta);
            inv.setItem(claimSlot, claimButton);
        }

        if (dismissSlot != null) {
            ItemStack dismissButton = new ItemStack(Material.valueOf(config().getString("gui.mail-view.items.dismiss-button.material")));
            ItemMeta dismissMeta = dismissButton.getItemMeta();
            dismissMeta.setDisplayName(plugin.colorize(config().getString("gui.mail-view.items.dismiss-button.name")));
            dismissMeta.setLore(config().getStringList("gui.mail-view.items.dismiss-button.lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList()));
            dismissMeta.getPersistentDataContainer().set(mailIdKey,
                    PersistentDataType.STRING, mailId);
            applyItemMetaCustomizations(dismissMeta, "gui.mail-view.items.dismiss-button");
            dismissButton.setItemMeta(dismissMeta);
            inv.setItem(dismissSlot, dismissButton);
        }

        placeBackButton(inv, "gui.mail-view.items.back-button", "mail-view-back");

        return inv;
    }


    @Override
    public Inventory createMailCreation(Player viewer) {
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(viewer.getUniqueId(), key -> new MailCreationSession());
        ensureSessionDefaults(session);

        int size = config().getInt("gui.create-mail.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString("gui.create-mail.title")));
        addDecorations(inv, "gui.create-mail");

        if (isEnabled("gui.create-mail.items.receiver-head")) {
            ItemStack head = new ItemStack(Material.valueOf(config().getString("gui.create-mail.items.receiver-head.material")));
            SkullMeta headMeta = (SkullMeta) head.getItemMeta();
            headMeta.setDisplayName(plugin.colorize(config().getString("gui.create-mail.items.receiver-head.name")));
            List<String> headLore = viewer.hasPermission(config().getString("settings.admin-permission")) ?
                    config().getStringList("gui.create-mail.items.receiver-head.adminlore") :
                    config().getStringList("gui.create-mail.items.receiver-head.lore");
            List<String> lore = headLore.stream().map(plugin::colorize).collect(Collectors.toList());
            if (session.getReceiver() != null) {
                lore.add(plugin.colorize(config().getString("gui.create-mail.items.receiver-head.current-receiver-format")
                        .replace("%receiver%", session.getReceiver())));
            }
            headMeta.setLore(lore);
            head.setItemMeta(headMeta);
            inv.setItem(config().getInt("gui.create-mail.items.receiver-head.slot"), head);
        }

        if (isEnabled("gui.create-mail.items.message-paper")) {
            ItemStack paper = new ItemStack(Material.valueOf(config().getString("gui.create-mail.items.message-paper.material")));
            ItemMeta paperMeta = paper.getItemMeta();
            paperMeta.setDisplayName(plugin.colorize(config().getString("gui.create-mail.items.message-paper.name")));
            List<String> paperLore = new ArrayList<>(config().getStringList("gui.create-mail.items.message-paper.lore"));
            paperLore = paperLore.stream().map(plugin::colorize).collect(Collectors.toList());
            if (session.getMessage() != null) {
                paperLore.add(plugin.colorize(config().getString("gui.create-mail.items.message-paper.current-message-prefix")));
                paperLore.addAll(Arrays.stream(session.getMessage().split("\n"))
                        .map(line -> plugin.colorize(config().getString("gui.create-mail.items.message-paper.message-line-format") + line))
                        .collect(Collectors.toList()));
            }
            paperMeta.setLore(paperLore);
            paper.setItemMeta(paperMeta);
            inv.setItem(config().getInt("gui.create-mail.items.message-paper.slot"), paper);
        }

        if (isEnabled("gui.create-mail.items.items-chest")) {
            int chestSlot = config().getInt("gui.create-mail.items.items-chest.slot");
            if (viewer.hasPermission(config().getString("settings.permissions.add-items"))) {
                ItemStack chest = new ItemStack(Material.valueOf(config().getString("gui.create-mail.items.items-chest.material")));
                ItemMeta chestMeta = chest.getItemMeta();
                chestMeta.setDisplayName(plugin.colorize(config().getString("gui.create-mail.items.items-chest.name")));
                chestMeta.setLore(config().getStringList("gui.create-mail.items.items-chest.lore").stream()
                        .map(plugin::colorize)
                        .collect(Collectors.toList()));
                chest.setItemMeta(chestMeta);
                inv.setItem(chestSlot, chest);
            } else {
                inv.setItem(chestSlot, createDisabledFiller());
            }
        }

        if (isEnabled("gui.create-mail.items.send-button")) {
            ItemStack sendButton = new ItemStack(Material.valueOf(config().getString("gui.create-mail.items.send-button.material")));
            ItemMeta sendMeta = sendButton.getItemMeta();
            sendMeta.setDisplayName(plugin.colorize(config().getString("gui.create-mail.items.send-button.name")));
            sendMeta.setLore(config().getStringList("gui.create-mail.items.send-button.lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList()));
            sendButton.setItemMeta(sendMeta);
            inv.setItem(config().getInt("gui.create-mail.items.send-button.slot"), sendButton);
        }

        int commandSlot = config().getInt("gui.create-mail.items.command-block.slot");
        int clockSlot = config().getInt("gui.create-mail.items.schedule-clock.slot");
        boolean commandEnabled = isEnabled("gui.create-mail.items.command-block");
        boolean clockEnabled = isEnabled("gui.create-mail.items.schedule-clock");
        if (viewer.hasPermission(config().getString("settings.admin-permission"))) {
            if (commandEnabled) {
                ItemStack commandButton = createCommandItemsButton(session);
                inv.setItem(commandSlot, commandButton);
            }

            if (clockEnabled) {
                ItemStack clock = new ItemStack(Material.valueOf(config().getString("gui.create-mail.items.schedule-clock.material")));
                ItemMeta clockMeta = clock.getItemMeta();
                clockMeta.setDisplayName(plugin.colorize(config().getString("gui.create-mail.items.schedule-clock.name")));

                // Replace schedule and expire time placeholders
                String scheduleTime = session.getScheduleDate() != null ?
                        new java.text.SimpleDateFormat("yyyy:MM:dd:HH:mm").format(new java.util.Date(session.getScheduleDate())) :
                        "Not set";
                String expireTime = session.getExpireDate() != null ?
                        new java.text.SimpleDateFormat("yyyy:MM:dd:HH:mm").format(new java.util.Date(session.getExpireDate())) :
                        "Not set";

                clockMeta.setLore(config().getStringList("gui.create-mail.items.schedule-clock.lore").stream()
                        .map(line -> line.replace("%schedule_time%", scheduleTime).replace("%expire_time%", expireTime))
                        .map(plugin::colorize)
                        .collect(Collectors.toList()));
                applyItemMetaCustomizations(clockMeta, "gui.create-mail.items.schedule-clock");
                clock.setItemMeta(clockMeta);
                inv.setItem(clockSlot, clock);
            }
        } else {
            if (commandEnabled) {
                inv.setItem(commandSlot, createDisabledFiller());
            }
            if (clockEnabled) {
                inv.setItem(clockSlot, createDisabledFiller());
            }
        }

        placeBackButton(inv, "gui.create-mail.items.back-button", "create-back");

        return inv;
    }

    @Override
    public Inventory createItemsEditor(Player viewer) {
        int size = config().getInt("gui.items.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString("gui.items.title")));

        MailCreationSession session = plugin.getMailSessions().get(viewer.getUniqueId());
        if (session != null) {
            session.getItems().forEach(item -> inv.addItem(item.clone()));
        }

        if (isEnabled("gui.items.items.save-button")) {
            ItemStack saveButton = new ItemStack(Material.valueOf(config().getString("gui.items.items.save-button.material")));
            ItemMeta saveMeta = saveButton.getItemMeta();
            saveMeta.setDisplayName(plugin.colorize(config().getString("gui.items.items.save-button.name")));
            saveMeta.setLore(config().getStringList("gui.items.items.save-button.lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList()));
            applyItemMetaCustomizations(saveMeta, "gui.items.items.save-button");
            saveButton.setItemMeta(saveMeta);
            inv.setItem(config().getInt("gui.items.items.save-button.slot"), saveButton);
        }

        placeBackButton(inv, "gui.items.items.back-button", "items-back");
        return inv;
    }

    @Override
    public Inventory createCommandItemsEditor(Player viewer) {
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(viewer.getUniqueId(), key -> new MailCreationSession());
        ensureSessionDefaults(session);

        String base = "gui.command-items-editor";
        int size = config().getInt(base + ".size", 45);
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString(base + ".title", "Command Items")));
        addDecorations(inv, base);

        List<CommandItem> commandItems = session.getCommandItems();
        List<Integer> slots = config().getIntegerList(base + ".items.command-item.slots");
        for (int i = 0; i < commandItems.size() && i < slots.size(); i++) {
            ItemStack stack = buildCommandItemEditorEntry(commandItems.get(i), i);
            inv.setItem(slots.get(i), stack);
        }

        if (isEnabled(base + ".items.add-button")) {
            ItemStack addButton = buildEditorStaticButton(base + ".items.add-button", "add");
            inv.setItem(config().getInt(base + ".items.add-button.slot", size - 5), addButton);
        }

        if (isEnabled(base + ".items.back-button")) {
            ItemStack backButton = buildEditorStaticButton(base + ".items.back-button", "back");
            inv.setItem(config().getInt(base + ".items.back-button.slot", size - 1), backButton);
        }

        return inv;
    }

    @Override
    public Inventory createCommandItemCreator(Player viewer) {
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(viewer.getUniqueId(), key -> new MailCreationSession());
        ensureSessionDefaults(session);
        if (session.getCommandItemDraft() == null) {
            session.setCommandItemDraft(new CommandItem.Builder());
        }

        CommandItem.Builder draft = session.getCommandItemDraft();
        String base = "gui.command-item-creator";
        int size = config().getInt(base + ".size", 54);
        Inventory inv = Bukkit.createInventory(null, size, plugin.colorize(config().getString(base + ".title", "Create Command Item")));
        addDecorations(inv, base);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%material%", draft.materialKey());
        placeholders.put("%name%", draft.displayName());
        placeholders.put("%lore_count%", String.valueOf(draft.lore().size()));
        placeholders.put("%command_count%", String.valueOf(draft.commands().size()));
        placeholders.put("%custom_model_data%", draft.customModelData() != null
                ? String.valueOf(draft.customModelData())
                : "None");

        placeCreatorButton(inv, base + ".items.material-selector", "material", placeholders, draft);
        placeCreatorButton(inv, base + ".items.name-editor", "name", placeholders, draft);
        placeCreatorButton(inv, base + ".items.lore-editor", "lore", placeholders, draft);
        placeCreatorButton(inv, base + ".items.commands-editor", "command", placeholders, draft);
        placeCreatorButton(inv, base + ".items.custom-model-editor", "custom-model", placeholders, draft);

        // Preview item
        int previewSlot = config().getInt(base + ".items.preview.slot", size / 2);
        ItemStack preview = draft.buildPreviewItem(plugin);
        inv.setItem(previewSlot, preview);

        if (isEnabled(base + ".items.save-button")) {
            ItemStack save = buildEditorStaticButton(base + ".items.save-button", "save");
            inv.setItem(config().getInt(base + ".items.save-button.slot", size - 6), save);
        }

        placeBackButton(inv, base + ".items.back-button", "command-creator-back");

        return inv;
    }

    private void addCreateMailButton(Inventory inv, Player viewer, Player target) {
        if (!isEnabled("gui.main.items.create-mail")) {
            return;
        }
        ItemStack createBook = new ItemStack(Material.valueOf(config().getString("gui.main.items.create-mail.material")));
        ItemMeta bookMeta = createBook.getItemMeta();
        String baseName = config().getString("gui.main.items.create-mail.name", "");
        bookMeta.setDisplayName(plugin.colorize(baseName));
        List<String> bookLore = new ArrayList<>(config().getStringList("gui.main.items.create-mail.lore"));

        String viewingAs = plugin.getViewingAsPlayer().get(viewer.getUniqueId());
        if (viewingAs != null && !viewer.getUniqueId().equals(target.getUniqueId())) {
            String disabledNameFormat = config().getString("gui.main.items.create-mail.disabled.name-format", "&c&l%name%");
            bookMeta.setDisplayName(plugin.colorize(disabledNameFormat.replace("%name%", baseName)));
            List<String> disabledLore = config().getStringList("gui.main.items.create-mail.disabled.lore");
            if (!disabledLore.isEmpty()) {
                bookLore.addAll(disabledLore);
            } else {
                bookLore.add("&c&l⚠ DISABLED");
                bookLore.add("&7Cannot create mail as another player");
            }
        }

        bookMeta.setLore(bookLore.stream().map(plugin::colorize).collect(Collectors.toList()));
        createBook.setItemMeta(bookMeta);
        inv.setItem(config().getInt("gui.main.items.create-mail.slot"), createBook);
    }

    private void addSentMailButton(Inventory inv) {
        if (!isEnabled("gui.main.items.sent-mail")) {
            return;
        }
        ItemStack sentMailButton = new ItemStack(Material.valueOf(config().getString("gui.main.items.sent-mail.material")));
        ItemMeta sentMailMeta = sentMailButton.getItemMeta();
        sentMailMeta.setDisplayName(plugin.colorize(config().getString("gui.main.items.sent-mail.name")));
        sentMailMeta.setLore(config().getStringList("gui.main.items.sent-mail.lore").stream()
                .map(plugin::colorize)
                .collect(Collectors.toList()));
        sentMailButton.setItemMeta(sentMailMeta);
        inv.setItem(config().getInt("gui.main.items.sent-mail.slot"), sentMailButton);
    }

    private void addBackButton(Inventory inv) {
        if (!isEnabled("gui.sent-mail.items.back-button")) {
            return;
        }
        ItemStack backButton = new ItemStack(Material.valueOf(config().getString("gui.sent-mail.items.back-button.material")));
        ItemMeta backMeta = backButton.getItemMeta();
        backMeta.setDisplayName(plugin.colorize(config().getString("gui.sent-mail.items.back-button.name")));
        backMeta.setLore(config().getStringList("gui.sent-mail.items.back-button.lore").stream()
                .map(plugin::colorize)
                .collect(Collectors.toList()));
        backButton.setItemMeta(backMeta);
        inv.setItem(config().getInt("gui.sent-mail.items.back-button.slot"), backButton);
    }

    private void loadPlayerMails(Player viewer, Player target, Inventory inv) {
        if (!isEnabled("gui.main.items.mail-display")) {
            return;
        }

        List<Integer> mailSlots = config().getIntegerList("gui.main.items.mail-display.slots");
        if (mailSlots == null || mailSlots.isEmpty()) {
            return;
        }

        String targetName = target.getName();
        List<MailRecord> records = plugin.getMailRepository().listMailIds().stream()
                .map(id -> plugin.getMailRepository().findRecord(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(MailRecord::active)
                .filter(record -> record.canBeClaimedBy(targetName))
                .sorted(Comparator.comparingLong(MailRecord::sentDate).reversed())
                .collect(Collectors.toList());

        int slotsPerPage = mailSlots.size();
        int totalPages = Math.max(1, (records.size() + slotsPerPage - 1) / slotsPerPage);
        UUID viewerId = viewer.getUniqueId();
        int currentPage = plugin.getMailboxPages().getOrDefault(viewerId, 1);
        if (currentPage < 1) {
            currentPage = 1;
        }
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }
        plugin.getMailboxPages().put(viewerId, currentPage);

        int startIndex = (currentPage - 1) * slotsPerPage;
        int endIndex = Math.min(records.size(), startIndex + slotsPerPage);

        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < mailSlots.size(); i++) {
            MailRecord record = records.get(i);
            ItemStack mailItem = createMailItem(record);
            if (mailItem != null) {
                inv.setItem(mailSlots.get(slotIndex), mailItem);
                slotIndex++;
            }
        }

        Set<Integer> reservedSlots = new HashSet<>();
        if (isEnabled("gui.main.items.create-mail")) {
            reservedSlots.add(config().getInt("gui.main.items.create-mail.slot"));
        }
        if (isEnabled("gui.main.items.sent-mail")) {
            reservedSlots.add(config().getInt("gui.main.items.sent-mail.slot"));
        }

        PaginationSettings settings = new PaginationSettings(
                "gui.main.items.pagination",
                reservedSlots,
                "mailbox-page-prev",
                "mailbox-page-next",
                (meta, type) -> {
                    if (type != PaginationButtonType.INDICATOR) {
                        meta.getPersistentDataContainer().set(paginationTargetKey, PersistentDataType.STRING, targetName);
                    }
                }
        );

        addPaginationButtons(inv, settings, currentPage, totalPages);
    }

    private void loadSentMails(Player viewer, Inventory inv) {
        if (!isEnabled("gui.sent-mail.items.sent-mail-display")) {
            return;
        }

        List<Integer> mailSlots = config().getIntegerList("gui.sent-mail.items.sent-mail-display.slots");
        if (mailSlots == null || mailSlots.isEmpty()) {
            return;
        }

        String viewingAs = plugin.getViewingAsPlayer().get(viewer.getUniqueId());
        String targetPlayerName = viewingAs != null ? viewingAs : viewer.getName();

        List<MailRecord> records = plugin.getMailRepository().listMailIdsBySender(targetPlayerName).stream()
                .map(id -> plugin.getMailRepository().findRecord(id).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(MailRecord::sentDate).reversed())
                .collect(Collectors.toList());

        int slotsPerPage = mailSlots.size();
        int totalPages = Math.max(1, (records.size() + slotsPerPage - 1) / slotsPerPage);
        UUID viewerId = viewer.getUniqueId();
        int currentPage = plugin.getSentMailboxPages().getOrDefault(viewerId, 1);
        if (currentPage < 1) {
            currentPage = 1;
        }
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }
        plugin.getSentMailboxPages().put(viewerId, currentPage);

        int startIndex = (currentPage - 1) * slotsPerPage;
        int endIndex = Math.min(records.size(), startIndex + slotsPerPage);

        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < mailSlots.size(); i++) {
            MailRecord record = records.get(i);
            ItemStack mailItem = createSentMailItem(record);
            if (mailItem != null) {
                inv.setItem(mailSlots.get(slotIndex), mailItem);
                slotIndex++;
            }
        }

        Set<Integer> reservedSlots = new HashSet<>();
        if (isEnabled("gui.sent-mail.items.back-button")) {
            reservedSlots.add(config().getInt("gui.sent-mail.items.back-button.slot"));
        }

        PaginationSettings settings = new PaginationSettings(
                "gui.sent-mail.items.pagination",
                reservedSlots,
                "sent-page-prev",
                "sent-page-next",
                (meta, type) -> {
                    if (type != PaginationButtonType.INDICATOR) {
                        meta.getPersistentDataContainer().set(paginationTargetKey, PersistentDataType.STRING, targetPlayerName);
                    }
                }
        );

        addPaginationButtons(inv, settings, currentPage, totalPages);
    }

    private ItemStack createMailItem(MailRecord record) {
        String itemPath = record.isAdminMail() ? "gui.main.items.admin-mail-display" : "gui.main.items.mail-display";

        if (!isEnabled(itemPath)) {
            return null;
        }

        ItemStack mailItem = new ItemStack(Material.valueOf(config().getString(itemPath + ".material")));
        ItemMeta meta = mailItem.getItemMeta();
        String displayName = config().getString(itemPath + ".name", "");
        String sender = Optional.ofNullable(record.sender()).orElse("");
        String message = record.message().replace("\\n", "\n");
        long sentAt = record.sentDate();
        long expireAt = record.expireDate() != null ? record.expireDate() : 0L;

        meta.setDisplayName(plugin.colorize(applyMailPlaceholders(displayName, sender, message, sentAt, expireAt)));

        List<String> loreTemplate = config().getStringList(itemPath + ".lore");
        List<String> lore = new ArrayList<>();
        for (String line : loreTemplate) {
            lore.add(plugin.colorize(applyMailPlaceholders(line, sender, message, sentAt, expireAt)));
        }

        String messagePrefix = config().getString(itemPath + ".message-prefix");
        if (messagePrefix != null && !messagePrefix.isEmpty() && !message.isEmpty()) {
            for (String line : message.split("\n")) {
                lore.add(plugin.colorize(messagePrefix + line));
            }
        }

        meta.setLore(lore);
        meta.getPersistentDataContainer().set(mailIdKey, PersistentDataType.STRING, record.id());
        mailItem.setItemMeta(meta);
        return mailItem;
    }

    private ItemStack createSentMailItem(MailRecord record) {
        if (!isEnabled("gui.sent-mail.items.sent-mail-display")) {
            return null;
        }
        ItemStack item = new ItemStack(Material.valueOf(config().getString("gui.sent-mail.items.sent-mail-display.material")));
        ItemMeta meta = item.getItemMeta();
        String receiver = Optional.ofNullable(record.receiver()).orElse("");
        long sentAt = record.sentDate();
        long expireAt = record.expireDate() != null ? record.expireDate() : 0L;

        String displayName = config().getString("gui.sent-mail.items.sent-mail-display.name", "");
        meta.setDisplayName(plugin.colorize(displayName
                .replace("%receiver%", receiver)
                .replace("%sent_date%", formatDate(sentAt))
                .replace("%expire_date%", formatDate(expireAt))));

        List<String> lore = config().getStringList("gui.sent-mail.items.sent-mail-display.lore").stream()
                .map(line -> plugin.colorize(line
                        .replace("%receiver%", receiver)
                        .replace("%sent_date%", formatDate(sentAt))
                        .replace("%expire_date%", formatDate(expireAt))
                        .replace("%sent%", formatDate(sentAt))
                        .replace("%expire%", formatDate(expireAt))))
                .collect(Collectors.toList());
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(mailIdKey, PersistentDataType.STRING, record.id());
        item.setItemMeta(meta);
        return item;
    }

    private String applyMailPlaceholders(String input,
                                         String sender,
                                         String message,
                                         long sentAt,
                                         long expireAt) {
        return input
                .replace("%sender%", sender)
                .replace("%message%", message.replace("\n", " "))
                .replace("%sent%", formatDate(sentAt))
                .replace("%sent_date%", formatDate(sentAt))
                .replace("%expire%", formatDate(expireAt))
                .replace("%expire_date%", formatDate(expireAt));
    }

    private String formatDate(long millis) {
        if (millis <= 0) {
            return plugin.colorize(config().getString("messages.never-expire", "Never"));
        }
        return new Date(millis).toString();
    }

    private void addDecorations(Inventory inv, String guiPath) {
        if (!config().contains(guiPath + ".decoration")) {
            return;
        }
        ConfigurationSection decorSection = config().getConfigurationSection(guiPath + ".decoration");
        if (decorSection == null) {
            return;
        }
        for (String decorKey : decorSection.getKeys(false)) {
            String path = guiPath + ".decoration." + decorKey;
            if (!isEnabled(path)) {
                continue;
            }
            Material material = Material.valueOf(config().getString(path + ".material"));
            String name = config().getString(path + ".name");
            List<Integer> slots = config().getIntegerList(path + ".slots");

            ItemStack decorItem = new ItemStack(material);
            ItemMeta meta = decorItem.getItemMeta();
            meta.setDisplayName(plugin.colorize(name));
            List<String> loreLines = config().getStringList(path + ".lore");
            if (!loreLines.isEmpty()) {
                meta.setLore(loreLines.stream()
                        .map(plugin::colorize)
                        .collect(Collectors.toList()));
            }
            // Always mark decorations with the decoration key so they can be replaced by pagination buttons
            meta.getPersistentDataContainer().set(decorationKey, PersistentDataType.STRING, path);

            List<String> commands = config().getStringList(path + ".commands").stream()
                    .filter(line -> line != null && !line.trim().isEmpty())
                    .collect(Collectors.toList());
            if (!commands.isEmpty()) {
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "decorationPath"),
                        PersistentDataType.STRING, path);
            }
            decorItem.setItemMeta(meta);
            for (int slot : slots) {
                inv.setItem(slot, decorItem.clone());
            }
        }
    }

    private void ensureSessionDefaults(MailCreationSession session) {
        if (session.getCommands() == null) {
            session.setCommands(new ArrayList<>());
        }
        if (session.getItems() == null) {
            session.setItems(new ArrayList<>());
        }
        if (session.getCommandItems() == null) {
            session.setCommandItems(new ArrayList<>());
        }
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }

    private ItemStack createDisabledFiller() {
        ConfigurationSection decorSection = config().getConfigurationSection("gui.create-mail.decoration");
        if (decorSection != null) {
            for (String key : decorSection.getKeys(false)) {
                String base = "gui.create-mail.decoration." + key;
                String materialName = config().getString(base + ".material");
                if (materialName == null) {
                    continue;
                }
                try {
                    Material material = Material.valueOf(materialName);
                    ItemStack filler = new ItemStack(material);
                    ItemMeta meta = filler.getItemMeta();
                    meta.setDisplayName(plugin.colorize(config().getString(base + ".name", " ")));
                    List<String> lore = config().getStringList(base + ".lore").stream()
                            .map(plugin::colorize)
                            .collect(Collectors.toList());
                    meta.setLore(lore);
                    filler.setItemMeta(meta);
                    return filler;
                } catch (IllegalArgumentException ignored) {
                    // try next decoration entry
                }
            }
        }

        ItemStack fallback = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = fallback.getItemMeta();
        meta.setDisplayName(" ");
        fallback.setItemMeta(meta);
        return fallback;
    }

    private ItemStack createCommandItemsButton(MailCreationSession session) {
        String basePath = "gui.create-mail.items.command-block";
        Material material = Material.COMMAND_BLOCK;
        String materialKey = config().getString(basePath + ".material");
        if (materialKey != null) {
            Material match = Material.matchMaterial(materialKey);
            if (match != null) {
                material = match;
            }
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = config().getString(basePath + ".name", "&6Command Items");
            meta.setDisplayName(plugin.colorize(name
                    .replace("%count%", String.valueOf(session.getCommandItems().size()))));

            List<String> loreTemplate = config().getStringList(basePath + ".lore");
            List<String> lore = loreTemplate.stream()
                    .map(line -> line
                            .replace("%count%", String.valueOf(session.getCommandItems().size())))
                    .map(plugin::colorize)
                    .collect(Collectors.toList());

            if (!session.getCommandItems().isEmpty()) {
                List<String> summaries = session.getCommandItems().stream()
                        .map(commandItem -> {
                            String first = commandItem.commands().isEmpty() ? "" : commandItem.commands().get(0);
                            String summary = first.isEmpty() ? "No command" : Optional.ofNullable(summarizeCommand(first)).orElse(first);
                            String display = commandItem.displayName() != null ? commandItem.displayName() : "Action";
                            return display + " → " + summary;
                        })
                        .collect(Collectors.toList());
                appendDetailLines(lore, "&7Configured actions:", summaries, 4, false);
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildCommandItemEditorEntry(CommandItem commandItem, int index) {
        String base = "gui.command-items-editor.items.command-item";
        ItemStack stack = commandItem.toPreviewItem(plugin);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            // Keep the actual item name - don't overwrite with template

            // Start with existing lore from the item (user's configured lore)
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }

            // Append greyed out command information
            String commandHeader = config().getString(base + ".command-header", "&7Commands:");
            appendDetailLines(lore, commandHeader, commandItem.commands(), 5, true);

            // Append action instructions from config
            List<String> actionLore = config().getStringList(base + ".lore");
            if (!actionLore.isEmpty()) {
                if (!lore.isEmpty()) {
                    lore.add(plugin.colorize("&7"));
                }
                lore.addAll(actionLore.stream()
                        .map(plugin::colorize)
                        .collect(Collectors.toList()));
            }

            meta.setLore(lore);
            meta.getPersistentDataContainer().set(commandItemIndexKey, PersistentDataType.INTEGER, index);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildEditorStaticButton(String path, String action) {
        String materialName = config().getString(path + ".material", "BARRIER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.BARRIER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.colorize(config().getString(path + ".name", "&c" + action)));
            List<String> lore = config().getStringList(path + ".lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList());
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(commandItemActionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void placeCreatorButton(Inventory inv,
                                    String path,
                                    String action,
                                    Map<String, String> placeholders,
                                    CommandItem.Builder draft) {
        if (!isEnabled(path)) {
            return;
        }
        int slot = config().getInt(path + ".slot", 0);
        String materialName = config().getString(path + ".material", "BOOK");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.BOOK;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = config().getString(path + ".name", "&eEdit");
            meta.setDisplayName(plugin.colorize(applyPlaceholders(name, placeholders)));

            List<String> loreTemplate = config().getStringList(path + ".lore");
            List<String> lore = loreTemplate.stream()
                    .map(line -> applyDraftPlaceholders(line, placeholders, draft))
                    .map(plugin::colorize)
                    .collect(Collectors.toList());

            if ("lore".equals(action) && !draft.lore().isEmpty()) {
                appendDetailLines(lore, "&7Lore:", draft.lore(), 10, false);
            } else if ("command".equals(action) && !draft.commands().isEmpty()) {
                appendDetailLines(lore, "&7Commands:", draft.commands(), 10, true);
            } else if ("custom-model".equals(action)) {
                String value = draft.customModelData() != null ? String.valueOf(draft.customModelData()) : "None";
                lore.add(plugin.colorize("&7Current: &f" + value));
            }

            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }

            meta.getPersistentDataContainer().set(commandItemActionKey, PersistentDataType.STRING, action);
            applyItemMetaCustomizations(meta, path);
            item.setItemMeta(meta);
        }
        inv.setItem(slot, item);
    }

    private String applyDraftPlaceholders(String input,
                                          Map<String, String> generic,
                                          CommandItem.Builder draft) {
        String result = applyPlaceholders(input, generic);
        result = result.replace("%material%", draft.materialKey());
        result = result.replace("%name%", draft.displayName());
        result = result.replace("%lore_count%", String.valueOf(draft.lore().size()));
        result = result.replace("%command_count%", String.valueOf(draft.commands().size()));
        result = result.replace("%custom_model_data%", draft.customModelData() != null
                ? String.valueOf(draft.customModelData())
                : "None");
        if (!draft.commands().isEmpty()) {
            result = result.replace("%first_command%", draft.commands().get(0));
            result = result.replace("%summary%", summarizeCommand(draft.commands().get(0)));
        } else {
            result = result.replace("%first_command%", "");
            result = result.replace("%summary%", "");
        }
        return result;
    }

    private String applyPlaceholders(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Map<String, String> createCommandItemPlaceholders(CommandItem commandItem) {
        Map<String, String> placeholders = new HashMap<>();

        int commandCount = commandItem.commands().size();
        int loreCount = commandItem.lore().size();
        String firstCommand = commandCount > 0 ? commandItem.commands().get(0) : "";
        String summary = firstCommand.isEmpty() ? "" : Optional.ofNullable(summarizeCommand(firstCommand)).orElse(firstCommand);

        placeholders.put("%commands%", formatList(commandItem.commands(), 3, true));
        placeholders.put("%command_count%", String.valueOf(commandCount));
        placeholders.put("%total%", String.valueOf(commandCount));
        placeholders.put("%first_command%", firstCommand);
        placeholders.put("%summary%", summary);
        placeholders.put("%lore_count%", String.valueOf(loreCount));
        placeholders.put("%lore_values%", formatList(commandItem.lore(), 3, false));
        placeholders.put("%first_lore%", loreCount > 0 ? commandItem.lore().get(0) : "");
        placeholders.put("%custom_model_data%", commandItem.customModelData() != null
                ? String.valueOf(commandItem.customModelData())
                : "None");
        return placeholders;
    }

    private String formatList(List<String> values, int limit, boolean summarizeCommands) {
        if (values == null || values.isEmpty()) {
            return "None";
        }
        String joined = values.stream()
                .limit(limit)
                .map(value -> summarizeCommands ? Optional.ofNullable(summarizeCommand(value)).orElse(value) : value)
                .collect(Collectors.joining(", "));
        return values.size() > limit ? joined + ", …" : joined;
    }

    private void appendDetailLines(List<String> target,
                                   String header,
                                   List<String> values,
                                   int limit,
                                   boolean summarizeCommands) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.add(plugin.colorize("&7"));
        }
        target.add(plugin.colorize(header));
        for (String value : values.stream().limit(limit).toList()) {
            String text = summarizeCommands ? Optional.ofNullable(summarizeCommand(value)).orElse(value) : value;
            target.add(plugin.colorize("&f• " + text));
        }
        if (values.size() > limit) {
            target.add(plugin.colorize("&7…"));
        }
    }

}

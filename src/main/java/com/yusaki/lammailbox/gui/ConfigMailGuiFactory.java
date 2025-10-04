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

    private final GuiItemStyler itemStyler;
    private final GuiNavigationHelper navigationHelper;
    private final PaginationBuilder paginationBuilder;
    private final CommandItemUiComposer commandItemUi;
    private final MailCreationViewBuilder mailCreationBuilder;

    public ConfigMailGuiFactory(LamMailBox plugin) {
        this.plugin = plugin;
        this.decorationKey = new NamespacedKey(plugin, "decorationPath");
        this.commandItemIndexKey = new NamespacedKey(plugin, "commandItemIndex");
        this.commandItemActionKey = new NamespacedKey(plugin, "commandItemAction");
        this.actionKey = new NamespacedKey(plugin, "action");
        this.mailIdKey = new NamespacedKey(plugin, "mailId");
        this.paginationTargetKey = new NamespacedKey(plugin, "paginationTarget");

        this.itemStyler = new GuiItemStyler(plugin);
        this.navigationHelper = new GuiNavigationHelper(plugin, actionKey, itemStyler);
        this.paginationBuilder = new PaginationBuilder(plugin, actionKey, decorationKey, itemStyler);
        this.commandItemUi = new CommandItemUiComposer(plugin, itemStyler, commandItemIndexKey, commandItemActionKey);
        this.mailCreationBuilder = new MailCreationViewBuilder(plugin, itemStyler, navigationHelper, commandItemUi, this::addDecorations, this::isEnabled);
    }

    private ItemStack styledCommandIcon(String basePath, ItemStack existing, int commandCount, String summary) {
        ItemStack stack = resolveCommandIconStack(basePath, existing);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        applyCommandIconText(meta, basePath, commandCount, summary);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack resolveCommandIconStack(String basePath, ItemStack existing) {
        ItemStack stack = existing != null ? existing.clone() : null;
        String materialName = config().getString(basePath + ".material");
        if (materialName != null && !materialName.isBlank()) {
            try {
                Material material = Material.valueOf(materialName.trim().toUpperCase(Locale.ROOT));
                if (stack == null || stack.getType() != material) {
                    stack = new ItemStack(material);
                }
            } catch (IllegalArgumentException ignored) {
                // Fall back to default below
            }
        }
        if (stack == null) {
            stack = new ItemStack(Material.COMMAND_BLOCK);
        }
        return stack;
    }

    private void applyCommandIconText(ItemMeta meta, String basePath, int commandCount, String summary) {
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
        itemStyler.apply(meta, basePath);
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
                ItemStack commandIcon = commandItemUi.createCommandPlaceholder(commandPath, commandItem);
                inv.setItem(itemSlots.get(slotIndex++), commandIcon);
            }
        }
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

        PaginationBuilder.Settings settings = new PaginationBuilder.Settings(
                "gui.mail-view.items.pagination",
                reservedSlots,
                "page-prev",
                "page-next",
                (meta, type) -> {
                    if (type != PaginationBuilder.PaginationButtonType.INDICATOR) {
                        meta.getPersistentDataContainer().set(mailIdKey, PersistentDataType.STRING, mailId);
                    }
                }
        );

        paginationBuilder.addPaginationButtons(inv, settings, currentPage, totalPages);
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
            itemStyler.apply(headMeta, "gui.sent-mail-view.items.receiver-head", false);
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
            itemStyler.apply(messageMeta, "gui.sent-mail-view.items.message", false);
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

        navigationHelper.placeBackButton(inv, "gui.sent-mail-view.items.back-button", "sent-mail-view-back");

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
            itemStyler.apply(deleteMeta, "gui.sent-mail-view.items.delete-button");
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
            itemStyler.apply(headMeta, "gui.mail-view.items.sender-head", false);
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
            itemStyler.apply(messageMeta, "gui.mail-view.items.message", false);
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
            itemStyler.apply(claimMeta, "gui.mail-view.items.claim-button");
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
            itemStyler.apply(dismissMeta, "gui.mail-view.items.dismiss-button");
            dismissButton.setItemMeta(dismissMeta);
            inv.setItem(dismissSlot, dismissButton);
        }

        navigationHelper.placeBackButton(inv, "gui.mail-view.items.back-button", "mail-view-back");

        return inv;
    }


    @Override
    public Inventory createMailCreation(Player viewer) {
        return mailCreationBuilder.createMailCreation(viewer);
    }

@Override
    public Inventory createItemsEditor(Player viewer) {
        return mailCreationBuilder.createItemsEditor(viewer);
    }

@Override
    public Inventory createCommandItemsEditor(Player viewer) {
        return mailCreationBuilder.createCommandItemsEditor(viewer);
    }

@Override
    public Inventory createCommandItemCreator(Player viewer) {
        return mailCreationBuilder.createCommandItemCreator(viewer);
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
        itemStyler.apply(bookMeta, "gui.main.items.create-mail");
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
        itemStyler.apply(sentMailMeta, "gui.main.items.sent-mail");
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
        itemStyler.apply(backMeta, "gui.sent-mail.items.back-button");
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

        PaginationBuilder.Settings settings = new PaginationBuilder.Settings(
                "gui.main.items.pagination",
                reservedSlots,
                "mailbox-page-prev",
                "mailbox-page-next",
                (meta, type) -> {
                    if (type != PaginationBuilder.PaginationButtonType.INDICATOR) {
                        meta.getPersistentDataContainer().set(paginationTargetKey, PersistentDataType.STRING, targetName);
                    }
                }
        );

        paginationBuilder.addPaginationButtons(inv, settings, currentPage, totalPages);
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

        PaginationBuilder.Settings settings = new PaginationBuilder.Settings(
                "gui.sent-mail.items.pagination",
                reservedSlots,
                "sent-page-prev",
                "sent-page-next",
                (meta, type) -> {
                    if (type != PaginationBuilder.PaginationButtonType.INDICATOR) {
                        meta.getPersistentDataContainer().set(paginationTargetKey, PersistentDataType.STRING, targetPlayerName);
                    }
                }
        );

        paginationBuilder.addPaginationButtons(inv, settings, currentPage, totalPages);
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
        itemStyler.apply(meta, itemPath, false);
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
        itemStyler.apply(meta, "gui.sent-mail.items.sent-mail-display", false);
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
            itemStyler.apply(meta, path);
            decorItem.setItemMeta(meta);
            for (int slot : slots) {
                inv.setItem(slot, decorItem.clone());
            }
        }
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean isEnabled(String path) {
        return config().getBoolean(path + ".enabled", true);
    }

}

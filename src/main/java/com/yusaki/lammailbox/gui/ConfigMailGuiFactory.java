package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
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

    public ConfigMailGuiFactory(LamMailBox plugin) {
        this.plugin = plugin;
        this.decorationKey = new NamespacedKey(plugin, "decorationPath");
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
                                       List<String> commands,
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
        if (commands != null && !commands.isEmpty() && isEnabled(commandPath)) {
            allElements.addAll(commands);
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
            } else if (element instanceof String) {
                // Place command placeholder
                String command = (String) element;
                ItemStack commandIcon = createCommandPlaceholder(commandPath, command);
                inv.setItem(itemSlots.get(slotIndex++), commandIcon);
            }
        }
    }

    private ItemStack createCommandPlaceholder(String commandPath, String command) {
        Material material = Material.COMMAND_BLOCK;
        String materialName = config().getString(commandPath + ".material");
        if (materialName != null) {
            Material configMaterial = Material.matchMaterial(materialName);
            if (configMaterial != null) {
                material = configMaterial;
            }
        }

        ItemStack commandIcon = new ItemStack(material);
        ItemMeta meta = commandIcon.getItemMeta();
        if (meta != null) {
            String name = config().getString(commandPath + ".name", "&6Command");
            meta.setDisplayName(plugin.colorize(name));

            List<String> lore = config().getStringList(commandPath + ".lore");
            if (!lore.isEmpty()) {
                meta.setLore(lore.stream()
                        .map(plugin::colorize)
                        .collect(Collectors.toList()));
            }
            commandIcon.setItemMeta(meta);
        }
        return commandIcon;
    }

    private void addPaginationButtons(Inventory inv,
                                      String mailId,
                                      int currentPage,
                                      int totalPages,
                                      Integer claimSlot,
                                      Integer dismissSlot) {
        if (totalPages <= 1) {
            // No pagination needed
            return;
        }

        String basePath = "gui.mail-view.items.pagination";

        Set<Integer> reservedSlots = new HashSet<>();
        if (claimSlot != null) {
            reservedSlots.add(claimSlot);
        }
        if (dismissSlot != null) {
            reservedSlots.add(dismissSlot);
        }

        // Previous button
        if (isEnabled(basePath + ".previous-button") && currentPage > 1) {
            int preferred = config().getInt(basePath + ".previous-button.slot", 36);
            String materialName = config().getString(basePath + ".previous-button.material", "ARROW");
            Material material = Material.matchMaterial(materialName);
            if (material != null) {
                ItemStack prevButton = new ItemStack(material);
                ItemMeta meta = prevButton.getItemMeta();
                if (meta != null) {
                    String name = config().getString(basePath + ".previous-button.name", "&e← Previous");
                    meta.setDisplayName(plugin.colorize(name));
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"),
                            PersistentDataType.STRING, mailId);
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "action"),
                            PersistentDataType.STRING, "page-prev");
                    prevButton.setItemMeta(meta);
                    Integer targetSlot = findAvailableSlot(inv, preferred, reservedSlots);
                    if (targetSlot != null) {
                        inv.setItem(targetSlot, prevButton);
                    }
                }
            }
        }

        // Next button
        if (isEnabled(basePath + ".next-button") && currentPage < totalPages) {
            int preferred = config().getInt(basePath + ".next-button.slot", 44);
            String materialName = config().getString(basePath + ".next-button.material", "ARROW");
            Material material = Material.matchMaterial(materialName);
            if (material != null) {
                ItemStack nextButton = new ItemStack(material);
                ItemMeta meta = nextButton.getItemMeta();
                if (meta != null) {
                    String name = config().getString(basePath + ".next-button.name", "&eNext →");
                    meta.setDisplayName(plugin.colorize(name));
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"),
                            PersistentDataType.STRING, mailId);
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "action"),
                            PersistentDataType.STRING, "page-next");
                    nextButton.setItemMeta(meta);
                    Integer targetSlot = findAvailableSlot(inv, preferred, reservedSlots);
                    if (targetSlot != null) {
                        inv.setItem(targetSlot, nextButton);
                    }
                }
            }
        }

        // Page indicator
        if (isEnabled(basePath + ".page-indicator")) {
            int preferred = config().getInt(basePath + ".page-indicator.slot", 40);
            String materialName = config().getString(basePath + ".page-indicator.material", "BOOK");
            Material material = Material.matchMaterial(materialName);
            if (material != null) {
                ItemStack indicator = new ItemStack(material);
                ItemMeta meta = indicator.getItemMeta();
                if (meta != null) {
                    String name = config().getString(basePath + ".page-indicator.name", "&6Page %current%/%total%");
                    name = name.replace("%current%", String.valueOf(currentPage))
                            .replace("%total%", String.valueOf(totalPages));
                    meta.setDisplayName(plugin.colorize(name));
                    indicator.setItemMeta(meta);
                    Integer targetSlot = findAvailableSlot(inv, preferred, reservedSlots);
                    if (targetSlot != null) {
                        inv.setItem(targetSlot, indicator);
                    }
                }
            }
        }
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
                record.commands(),
                "gui.sent-mail-view.items.items-display",
                "gui.sent-mail-view.items.command-item",
                1,
                slotsPerPage);

        if (viewer.hasPermission(config().getString("settings.permissions.delete"))
                && isEnabled("gui.sent-mail-view.items.delete-button")) {
            ItemStack deleteButton = new ItemStack(Material.valueOf(config().getString("gui.sent-mail-view.items.delete-button.material")));
            ItemMeta deleteMeta = deleteButton.getItemMeta();
            deleteMeta.setDisplayName(plugin.colorize(config().getString("gui.sent-mail-view.items.delete-button.name")));
            deleteMeta.setLore(config().getStringList("gui.sent-mail-view.items.delete-button.lore").stream()
                    .map(plugin::colorize)
                    .collect(Collectors.toList()));
            deleteMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"),
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
        int totalElements = items.size() + (record.commands() != null ? record.commands().size() : 0);
        List<Integer> itemSlots = config().getIntegerList("gui.mail-view.items.items-display.slots");
        int slotsPerPage = itemSlots != null && !itemSlots.isEmpty() ? itemSlots.size() : 21;
        int totalPages = (totalElements + slotsPerPage - 1) / slotsPerPage; // Ceiling division

        // Ensure current page is valid
        if (currentPage < 1) currentPage = 1;
        if (currentPage > totalPages && totalPages > 0) currentPage = totalPages;
        plugin.getMailViewPages().put(viewer.getUniqueId(), currentPage);

        placeItemsAndCommands(inv,
                items,
                record.commands(),
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
            claimMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"),
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
            dismissMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"),
                    PersistentDataType.STRING, mailId);
            dismissButton.setItemMeta(dismissMeta);
            inv.setItem(dismissSlot, dismissButton);
        }

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
                ItemStack commandBlock = session.getDisplayCommandBlock() != null ?
                        session.getDisplayCommandBlock().clone() : createDefaultCommandBlock();
                inv.setItem(commandSlot, commandBlock);
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
            saveButton.setItemMeta(saveMeta);
            inv.setItem(config().getInt("gui.items.items.save-button.slot"), saveButton);
        }
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
        List<MailRecord> records = plugin.getMailRepository().listMailIds().stream()
                .map(id -> plugin.getMailRepository().findRecord(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(MailRecord::active)
                .sorted(Comparator.comparingLong(MailRecord::sentDate).reversed())
                .collect(Collectors.toList());

        String targetName = target.getName();
        int slotIndex = 0;
        for (MailRecord record : records) {
            if (slotIndex >= mailSlots.size()) {
                break;
            }
            if (!record.canBeClaimedBy(targetName)) {
                continue;
            }
            ItemStack mailItem = createMailItem(record);
            if (mailItem != null) {
                inv.setItem(mailSlots.get(slotIndex), mailItem);
                slotIndex++;
            }
        }
    }

    private void loadSentMails(Player viewer, Inventory inv) {
        if (!isEnabled("gui.sent-mail.items.sent-mail-display")) {
            return;
        }

        List<Integer> mailSlots = config().getIntegerList("gui.sent-mail.items.sent-mail-display.slots");
        String viewingAs = plugin.getViewingAsPlayer().get(viewer.getUniqueId());
        String targetPlayerName = viewingAs != null ? viewingAs : viewer.getName();

        List<MailRecord> records = plugin.getMailRepository().listMailIdsBySender(targetPlayerName).stream()
                .map(id -> plugin.getMailRepository().findRecord(id).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(MailRecord::sentDate).reversed())
                .collect(Collectors.toList());

        int slotIndex = 0;
        for (MailRecord record : records) {
            if (slotIndex >= mailSlots.size()) {
                break;
            }
            ItemStack mailItem = createSentMailItem(record);
            if (mailItem != null) {
                inv.setItem(mailSlots.get(slotIndex), mailItem);
                slotIndex++;
            }
        }
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
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"), PersistentDataType.STRING, record.id());
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
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mailId"), PersistentDataType.STRING, record.id());
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
        if (session.getCommandBlock() == null) {
            ItemStack defaultBlock = createDefaultCommandBlock();
            session.setCommandBlock(defaultBlock);
            session.setDisplayCommandBlock(defaultBlock.clone());
        } else if (session.getDisplayCommandBlock() == null) {
            session.setDisplayCommandBlock(session.getCommandBlock().clone());
        }
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private ItemStack createDefaultCommandBlock() {
        ItemStack commandBlock = new ItemStack(Material.valueOf(config().getString("gui.create-mail.items.command-block.material")));
        ItemMeta meta = commandBlock.getItemMeta();
        meta.setDisplayName(plugin.colorize(config().getString("gui.create-mail.items.command-block.name")));
        meta.setLore(config().getStringList("gui.create-mail.items.command-block.lore").stream()
                .map(plugin::colorize)
                .collect(Collectors.toList()));
        commandBlock.setItemMeta(meta);
        return commandBlock;
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

}

package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds the create-mail workflow inventories and related editors.
 */
final class MailCreationViewBuilder {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy:MM:dd:HH:mm");

    private final LamMailBox plugin;
    private final GuiItemStyler itemStyler;
    private final GuiNavigationHelper navigationHelper;
    private final CommandItemUiComposer commandItemUi;
    private final BiConsumer<Inventory, String> decorationApplier;
    private final Predicate<String> enabledChecker;

    MailCreationViewBuilder(LamMailBox plugin,
                            GuiItemStyler itemStyler,
                            GuiNavigationHelper navigationHelper,
                            CommandItemUiComposer commandItemUi,
                            BiConsumer<Inventory, String> decorationApplier,
                            Predicate<String> enabledChecker) {
        this.plugin = plugin;
        this.itemStyler = itemStyler;
        this.navigationHelper = navigationHelper;
        this.commandItemUi = commandItemUi;
        this.decorationApplier = decorationApplier;
        this.enabledChecker = enabledChecker;
    }

    Inventory createMailCreation(Player viewer) {
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(viewer.getUniqueId(), key -> new MailCreationSession());
        ensureSessionDefaults(session);

        int size = config().getInt("gui.create-mail.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.legacy(config().getString("gui.create-mail.title")));
        decorationApplier.accept(inv, "gui.create-mail");

        addReceiverHead(inv, viewer, session);
        addMessagePaper(inv, session);
        addItemsChest(inv, viewer);
        addSendButton(inv);
        addAdminControls(inv, viewer, session);

        navigationHelper.placeBackButton(inv, "gui.create-mail.items.back-button", "create-back");
        return inv;
    }

    Inventory createItemsEditor(Player viewer) {
        int size = config().getInt("gui.items.size");
        Inventory inv = Bukkit.createInventory(null, size, plugin.legacy(config().getString("gui.items.title")));

        MailCreationSession session = plugin.getMailSessions().get(viewer.getUniqueId());
        if (session != null) {
            session.getItems().forEach(item -> inv.addItem(item.clone()));
        }

        if (enabledChecker.test("gui.items.items.save-button")) {
            ItemStack saveButton = new ItemStack(Material.valueOf(config().getString("gui.items.items.save-button.material")));
            ItemMeta saveMeta = saveButton.getItemMeta();
            if (saveMeta != null) {
                saveMeta.setDisplayName(plugin.legacy(config().getString("gui.items.items.save-button.name")));
                saveMeta.setLore(config().getStringList("gui.items.items.save-button.lore").stream()
                        .map(plugin::legacy)
                        .collect(Collectors.toList()));
                itemStyler.apply(saveMeta, "gui.items.items.save-button");
                saveButton.setItemMeta(saveMeta);
            }
            inv.setItem(config().getInt("gui.items.items.save-button.slot"), saveButton);
        }

        navigationHelper.placeBackButton(inv, "gui.items.items.back-button", "items-back");
        return inv;
    }

    Inventory createCommandItemsEditor(Player viewer) {
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(viewer.getUniqueId(), key -> new MailCreationSession());
        ensureSessionDefaults(session);

        String base = "gui.command-items-editor";
        int size = config().getInt(base + ".size", 45);
        Inventory inv = Bukkit.createInventory(null, size, plugin.legacy(config().getString(base + ".title", "Command Items")));
        decorationApplier.accept(inv, base);

        List<Integer> slots = config().getIntegerList(base + ".items.command-item.slots");
        commandItemUi.openCommandItemsEditor(inv, session, slots);

        if (enabledChecker.test(base + ".items.add-button")) {
            ItemStack addButton = commandItemUi.buildEditorStaticButton(base + ".items.add-button", "add");
            inv.setItem(config().getInt(base + ".items.add-button.slot", size - 5), addButton);
        }

        if (enabledChecker.test(base + ".items.back-button")) {
            ItemStack backButton = commandItemUi.buildEditorStaticButton(base + ".items.back-button", "back");
            inv.setItem(config().getInt(base + ".items.back-button.slot", size - 1), backButton);
        }

        return inv;
    }

    Inventory createCommandItemCreator(Player viewer) {
        MailCreationSession session = plugin.getMailSessions()
                .computeIfAbsent(viewer.getUniqueId(), key -> new MailCreationSession());
        ensureSessionDefaults(session);
        if (session.getCommandItemDraft() == null) {
            session.setCommandItemDraft(new CommandItem.Builder());
        }

        CommandItem.Builder draft = session.getCommandItemDraft();
        String base = "gui.command-item-creator";
        int size = config().getInt(base + ".size", 54);
        Inventory inv = Bukkit.createInventory(null, size, plugin.legacy(config().getString(base + ".title", "Create Command Item")));
        decorationApplier.accept(inv, base);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%material%", draft.materialKey());
        placeholders.put("%name%", draft.displayName());
        placeholders.put("%lore_count%", String.valueOf(draft.lore().size()));
        placeholders.put("%command_count%", String.valueOf(draft.commands().size()));
        placeholders.put("%custom_model_data%", draft.customModelData() != null
                ? String.valueOf(draft.customModelData())
                : "None");

        commandItemUi.placeCreatorButton(inv, base + ".items.material-selector", "material", placeholders, draft);
        commandItemUi.placeCreatorButton(inv, base + ".items.name-editor", "name", placeholders, draft);
        commandItemUi.placeCreatorButton(inv, base + ".items.lore-editor", "lore", placeholders, draft);
        commandItemUi.placeCreatorButton(inv, base + ".items.commands-editor", "command", placeholders, draft);
        commandItemUi.placeCreatorButton(inv, base + ".items.custom-model-editor", "custom-model", placeholders, draft);

        int previewSlot = config().getInt(base + ".items.preview.slot", size / 2);
        ItemStack preview = draft.buildPreviewItem(plugin);
        inv.setItem(previewSlot, preview);

        if (enabledChecker.test(base + ".items.save-button")) {
            ItemStack save = commandItemUi.buildEditorStaticButton(base + ".items.save-button", "save");
            inv.setItem(config().getInt(base + ".items.save-button.slot", size - 6), save);
        }

        navigationHelper.placeBackButton(inv, base + ".items.back-button", "command-creator-back");
        return inv;
    }

    private void addReceiverHead(Inventory inv, Player viewer, MailCreationSession session) {
        String path = "gui.create-mail.items.receiver-head";
        if (!enabledChecker.test(path)) {
            return;
        }
        ItemStack head = new ItemStack(Material.valueOf(config().getString(path + ".material")));
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        if (headMeta == null) {
            return;
        }
        headMeta.setDisplayName(plugin.legacy(config().getString(path + ".name")));
        List<String> headLore = viewer.hasPermission(config().getString("settings.admin-permission")) ?
                config().getStringList(path + ".adminlore") :
                config().getStringList(path + ".lore");
        List<String> lore = headLore.stream().map(plugin::legacy).collect(Collectors.toList());
        if (session.getReceiver() != null) {
            lore.add(plugin.getMessage(path + ".current-receiver-format",
                    plugin.placeholders("receiver", session.getReceiver())));
        }
        headMeta.setLore(lore);
        itemStyler.apply(headMeta, path, false);
        head.setItemMeta(headMeta);
        inv.setItem(config().getInt(path + ".slot"), head);
    }

    private void addMessagePaper(Inventory inv, MailCreationSession session) {
        String path = "gui.create-mail.items.message-paper";
        if (!enabledChecker.test(path)) {
            return;
        }
        ItemStack paper = new ItemStack(Material.valueOf(config().getString(path + ".material")));
        ItemMeta paperMeta = paper.getItemMeta();
        if (paperMeta == null) {
            return;
        }
        paperMeta.setDisplayName(plugin.legacy(config().getString(path + ".name")));
        List<String> paperLore = config().getStringList(path + ".lore").stream()
                .map(plugin::legacy)
                .collect(Collectors.toList());
        if (session.getMessage() != null) {
            paperLore.add(plugin.legacy(config().getString(path + ".current-message-prefix")));
            paperLore.addAll(Arrays.stream(session.getMessage().split("\n"))
                    .map(line -> plugin.legacy(config().getString(path + ".message-line-format") + line))
                    .collect(Collectors.toList()));
        }
        paperMeta.setLore(paperLore);
        itemStyler.apply(paperMeta, path);
        paper.setItemMeta(paperMeta);
        inv.setItem(config().getInt(path + ".slot"), paper);
    }

    private void addItemsChest(Inventory inv, Player viewer) {
        String path = "gui.create-mail.items.items-chest";
        if (!enabledChecker.test(path)) {
            return;
        }
        int chestSlot = config().getInt(path + ".slot");
        if (!viewer.hasPermission(config().getString("settings.permissions.add-items"))) {
            inv.setItem(chestSlot, createDisabledFiller());
            return;
        }

        ItemStack chest = new ItemStack(Material.valueOf(config().getString(path + ".material")));
        ItemMeta chestMeta = chest.getItemMeta();
        if (chestMeta == null) {
            return;
        }
        chestMeta.setDisplayName(plugin.legacy(config().getString(path + ".name")));
        chestMeta.setLore(config().getStringList(path + ".lore").stream()
                .map(plugin::legacy)
                .collect(Collectors.toList()));
        itemStyler.apply(chestMeta, path);
        chest.setItemMeta(chestMeta);
        inv.setItem(chestSlot, chest);
    }

    private void addSendButton(Inventory inv) {
        String path = "gui.create-mail.items.send-button";
        if (!enabledChecker.test(path)) {
            return;
        }
        ItemStack sendButton = new ItemStack(Material.valueOf(config().getString(path + ".material")));
        ItemMeta sendMeta = sendButton.getItemMeta();
        if (sendMeta == null) {
            return;
        }
        sendMeta.setDisplayName(plugin.legacy(config().getString(path + ".name")));
        sendMeta.setLore(config().getStringList(path + ".lore").stream()
                .map(plugin::legacy)
                .collect(Collectors.toList()));
        itemStyler.apply(sendMeta, path);
        sendButton.setItemMeta(sendMeta);
        inv.setItem(config().getInt(path + ".slot"), sendButton);
    }

    private void addAdminControls(Inventory inv, Player viewer, MailCreationSession session) {
        int commandSlot = config().getInt("gui.create-mail.items.command-block.slot");
        int clockSlot = config().getInt("gui.create-mail.items.schedule-clock.slot");
        boolean commandEnabled = enabledChecker.test("gui.create-mail.items.command-block");
        boolean clockEnabled = enabledChecker.test("gui.create-mail.items.schedule-clock");
        boolean isAdmin = viewer.hasPermission(config().getString("settings.admin-permission"));

        if (isAdmin) {
            if (commandEnabled) {
                inv.setItem(commandSlot, commandItemUi.createCommandItemsButton(session));
            }
            if (clockEnabled) {
                inv.setItem(clockSlot, buildScheduleClock(session));
            }
            return;
        }

        if (commandEnabled) {
            inv.setItem(commandSlot, createDisabledFiller());
        }
        if (clockEnabled) {
            inv.setItem(clockSlot, createDisabledFiller());
        }
    }

    private ItemStack buildScheduleClock(MailCreationSession session) {
        String basePath = "gui.create-mail.items.schedule-clock";
        ItemStack clock = new ItemStack(Material.valueOf(config().getString(basePath + ".material")));
        ItemMeta clockMeta = clock.getItemMeta();
        if (clockMeta == null) {
            return clock;
        }

        clockMeta.setDisplayName(plugin.legacy(config().getString(basePath + ".name")));

        String scheduleTime = session.getScheduleDate() != null
                ? DATE_FORMAT.format(new Date(session.getScheduleDate()))
                : "Not set";
        String expireTime = session.getExpireDate() != null
                ? DATE_FORMAT.format(new Date(session.getExpireDate()))
                : "Not set";

        List<String> lore = config().getStringList(basePath + ".lore").stream()
                .map(line -> plugin.applyPlaceholderVariants(line, Map.of(
                        "schedule_time", scheduleTime,
                        "expire_time", expireTime)))
                .map(plugin::legacy)
                .collect(Collectors.toList());
        clockMeta.setLore(lore);
        itemStyler.apply(clockMeta, basePath);
        clock.setItemMeta(clockMeta);
        return clock;
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

    private ItemStack createDisabledFiller() {
        FileConfiguration config = config();
        ConfigurationSection decorSection = config.getConfigurationSection("gui.create-mail.decoration");
        if (decorSection != null) {
            for (String key : decorSection.getKeys(false)) {
                String base = "gui.create-mail.decoration." + key;
                String materialName = config.getString(base + ".material");
                if (materialName == null) {
                    continue;
                }
                try {
                    Material material = Material.valueOf(materialName);
                    ItemStack filler = new ItemStack(material);
                    ItemMeta meta = filler.getItemMeta();
                    if (meta == null) {
                        continue;
                    }
                    meta.setDisplayName(plugin.legacy(config.getString(base + ".name", " ")));
                    meta.setLore(config.getStringList(base + ".lore").stream()
                            .map(plugin::legacy)
                            .collect(Collectors.toList()));
                    filler.setItemMeta(meta);
                    return filler;
                } catch (IllegalArgumentException ignored) {
                    // try next decoration entry
                }
            }
        }

        ItemStack fallback = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = fallback.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            fallback.setItemMeta(meta);
        }
        return fallback;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

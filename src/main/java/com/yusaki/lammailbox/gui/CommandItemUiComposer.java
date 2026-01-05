package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the command-related UI components used across different GUIs.
 */
final class CommandItemUiComposer {
    private final LamMailBox plugin;
    private final GuiItemStyler itemStyler;
    private final NamespacedKey commandItemIndexKey;
    private final NamespacedKey commandItemActionKey;

    CommandItemUiComposer(LamMailBox plugin,
                          GuiItemStyler itemStyler,
                          NamespacedKey commandItemIndexKey,
                          NamespacedKey commandItemActionKey) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemStyler = Objects.requireNonNull(itemStyler, "itemStyler");
        this.commandItemIndexKey = Objects.requireNonNull(commandItemIndexKey, "commandItemIndexKey");
        this.commandItemActionKey = Objects.requireNonNull(commandItemActionKey, "commandItemActionKey");
    }

    ItemStack createCommandPlaceholder(String commandPath, CommandItem commandItem) {
        ItemStack base = commandItem.toPreviewItem(plugin);

        boolean isLegacy = commandItem.displayName() != null
                && commandItem.displayName().equals("&6Console Command");
        if (isLegacy) {
            applyLegacyOverrides(base, commandPath, commandItem);
        }

        ItemMeta meta = base.getItemMeta();
        if (meta != null && !meta.hasCustomModelData()) {
            itemStyler.apply(meta, commandPath, false);
            base.setItemMeta(meta);
        }

        return base;
    }

    ItemStack createCommandItemsButton(MailCreationSession session) {
        String basePath = "gui.create-mail.items.command-block";
        Material material = resolveMaterial(basePath + ".material", Material.COMMAND_BLOCK);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String name = plugin.getMessage(basePath + ".name",
                plugin.placeholders("count", String.valueOf(session.getCommandItems().size())));
        meta.setDisplayName(name);

        List<String> lore = config().getStringList(basePath + ".lore").stream()
                .map(line -> plugin.applyPlaceholderVariants(line,
                        "count",
                        String.valueOf(session.getCommandItems().size())))
                .map(plugin::legacy)
                .collect(Collectors.toList());

        if (!session.getCommandItems().isEmpty()) {
            List<String> summaries = session.getCommandItems().stream()
                    .map(this::formatCommandItemSummary)
                    .collect(Collectors.toList());
            appendDetailLines(lore, "&7Configured actions:", summaries, 4, false);
        }

        meta.setLore(lore);
        itemStyler.apply(meta, basePath, false);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack buildCommandItemEditorEntry(CommandItem commandItem, int index) {
        String base = "gui.command-items-editor.items.command-item";
        ItemStack stack = commandItem.toPreviewItem(plugin);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        List<String> lore = new ArrayList<>();
        if (meta.hasLore() && meta.getLore() != null) {
            lore.addAll(meta.getLore());
        }
        appendDetailLines(lore, config().getString(base + ".command-header", "&7Commands:"), commandItem.commands(), 5, true);
        appendActionInstructions(lore, base);

        meta.setLore(lore);
        if (!meta.hasCustomModelData()) {
            itemStyler.apply(meta, base, false);
        }
        meta.getPersistentDataContainer().set(commandItemIndexKey, PersistentDataType.INTEGER, index);
        stack.setItemMeta(meta);
        return stack;
    }

    ItemStack buildEditorStaticButton(String path, String action) {
        String materialName = config().getString(path + ".material", "BARRIER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.BARRIER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(plugin.legacy(config().getString(path + ".name", "&c" + action)));
        meta.setLore(config().getStringList(path + ".lore").stream()
                .map(plugin::legacy)
                .collect(Collectors.toList()));
        itemStyler.apply(meta, path);
        meta.getPersistentDataContainer().set(commandItemActionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    void placeCreatorButton(Inventory inv,
                            String path,
                            String action,
                            Map<String, String> placeholders,
                            CommandItem.Builder draft) {
        if (!config().getBoolean(path + ".enabled", true)) {
            return;
        }

        int slot = config().getInt(path + ".slot", 0);
        Material material = Material.matchMaterial(config().getString(path + ".material", "BOOK"));
        if (material == null) {
            material = Material.BOOK;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = config().getString(path + ".name", "&eEdit");
        meta.setDisplayName(plugin.legacy(applyPlaceholders(name, placeholders)));

        List<String> loreTemplate = config().getStringList(path + ".lore");
        List<String> lore = loreTemplate.stream()
                .map(line -> applyDraftPlaceholders(line, placeholders, draft))
                .map(plugin::legacy)
                .collect(Collectors.toList());

        if ("lore".equals(action) && !draft.lore().isEmpty()) {
            appendDetailLines(lore, "&7Lore:", draft.lore(), 10, false);
        } else if ("command".equals(action) && !draft.commands().isEmpty()) {
            appendDetailLines(lore, "&7Commands:", draft.commands(), 10, true);
        } else if ("custom-model".equals(action)) {
            String value = draft.customModelData() != null ? String.valueOf(draft.customModelData()) : "None";
            lore.add(plugin.legacy("&7Current: &f" + value));
        }

        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }

        meta.getPersistentDataContainer().set(commandItemActionKey, PersistentDataType.STRING, action);
        itemStyler.apply(meta, path);

        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    void openCommandItemsEditor(Inventory inv, MailCreationSession session, List<Integer> slots) {
        for (int i = 0; i < session.getCommandItems().size() && i < slots.size(); i++) {
            ItemStack stack = buildCommandItemEditorEntry(session.getCommandItems().get(i), i);
            inv.setItem(slots.get(i), stack);
        }
    }

    String applyPlaceholders(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return input;
        }
        return plugin.applyPlaceholderVariants(input, placeholders);
    }

    Map<String, String> createCommandItemPlaceholders(CommandItem commandItem) {
        Map<String, String> placeholders = new HashMap<>();

        int commandCount = commandItem.commands().size();
        int loreCount = commandItem.lore().size();
        String firstCommand = commandCount > 0 ? commandItem.commands().get(0) : "";
        String summary = firstCommand.isEmpty() ? "" : Optional.ofNullable(summarizeCommand(firstCommand)).orElse(firstCommand);

        placeholders.put("{commands}", formatList(commandItem.commands(), 3, true));
        placeholders.put("{command_count}", String.valueOf(commandCount));
        placeholders.put("{total}", String.valueOf(commandCount));
        placeholders.put("{first_command}", firstCommand);
        placeholders.put("{summary}", summary);
        placeholders.put("{lore_count}", String.valueOf(loreCount));
        placeholders.put("{lore_values}", formatList(commandItem.lore(), 3, false));
        placeholders.put("{first_lore}", loreCount > 0 ? commandItem.lore().get(0) : "");
        placeholders.put("{custom_model_data}", commandItem.customModelData() != null
                ? String.valueOf(commandItem.customModelData())
                : "None");
        return placeholders;
    }

    private void applyLegacyOverrides(ItemStack base, String commandPath, CommandItem commandItem) {
        String legacyPath = commandPath.replace("command-item", "command-legacy");
        ItemMeta meta = base.getItemMeta();
        if (meta == null) {
            return;
        }

        Map<String, String> placeholders = createCommandItemPlaceholders(commandItem);
        String overrideName = config().getString(legacyPath + ".name");
        if (overrideName != null && !overrideName.isBlank()) {
            meta.setDisplayName(plugin.legacy(applyPlaceholders(overrideName, placeholders)));
        }

        List<String> configuredLore = config().getStringList(legacyPath + ".lore");
        if (!configuredLore.isEmpty()) {
            List<String> lore = configuredLore.stream()
                    .map(line -> plugin.legacy(applyPlaceholders(line, placeholders)))
                    .collect(Collectors.toList());
            meta.setLore(lore);
        }

        base.setItemMeta(meta);
    }

    private String applyDraftPlaceholders(String input,
                                          Map<String, String> generic,
                                          CommandItem.Builder draft) {
        String result = applyPlaceholders(input, generic);
        result = plugin.applyPlaceholderVariants(result, "material", draft.materialKey());
        result = plugin.applyPlaceholderVariants(result, "name", draft.displayName());
        result = plugin.applyPlaceholderVariants(result, "lore_count", String.valueOf(draft.lore().size()));
        result = plugin.applyPlaceholderVariants(result, "command_count", String.valueOf(draft.commands().size()));
        result = plugin.applyPlaceholderVariants(result, "custom_model_data",
                draft.customModelData() != null ? String.valueOf(draft.customModelData()) : "None");

        if (!draft.commands().isEmpty()) {
            String firstCommand = draft.commands().get(0);
            result = plugin.applyPlaceholderVariants(result, "first_command", firstCommand);
            result = plugin.applyPlaceholderVariants(result, "summary", summarizeCommand(firstCommand));
        } else {
            result = plugin.applyPlaceholderVariants(result, "first_command", "");
            result = plugin.applyPlaceholderVariants(result, "summary", "");
        }
        return result;
    }

    private void appendActionInstructions(List<String> lore, String base) {
        List<String> actionLore = config().getStringList(base + ".lore");
        if (actionLore.isEmpty()) {
            return;
        }
        if (!lore.isEmpty()) {
            lore.add(plugin.legacy("&7"));
        }
        lore.addAll(actionLore.stream()
                .map(plugin::legacy)
                .collect(Collectors.toList()));
    }

    void appendDetailLines(List<String> target,
                           String header,
                           List<String> values,
                           int limit,
                           boolean summarizeCommands) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.add(plugin.legacy("&7"));
        }
        target.add(plugin.legacy(header));
        for (String value : values.stream().limit(limit).toList()) {
            String text = summarizeCommands ? Optional.ofNullable(summarizeCommand(value)).orElse(value) : value;
            target.add(plugin.legacy("&f• " + text));
        }
        if (values.size() > limit) {
            target.add(plugin.legacy("&7…"));
        }
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

    private String formatCommandItemSummary(CommandItem commandItem) {
        String first = commandItem.commands().isEmpty() ? "" : commandItem.commands().get(0);
        String summary = first.isEmpty() ? "No command" : Optional.ofNullable(summarizeCommand(first)).orElse(first);
        String display = commandItem.displayName() != null ? commandItem.displayName() : "Action";
        return display + " → " + summary;
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
            String item = plugin.applyPlaceholderVariants(parts[2], "player", "@p");
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
            return main + " " + plugin.applyPlaceholderVariants(parts[1], "player", "@p");
        }
        return main;
    }

    private Material resolveMaterial(String materialPath, Material fallback) {
        String materialKey = config().getString(materialPath);
        if (materialKey == null || materialKey.isBlank()) {
            return fallback;
        }
        Material match = Material.matchMaterial(materialKey);
        return match != null ? match : fallback;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

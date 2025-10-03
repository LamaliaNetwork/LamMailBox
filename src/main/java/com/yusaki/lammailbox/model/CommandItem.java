package com.yusaki.lammailbox.model;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a virtual command item that is rendered in GUIs but never handed to players.
 */
public final class CommandItem {
    private static final String DEFAULT_MATERIAL = "COMMAND_BLOCK";

    private final String materialKey;
    private final String displayName;
    private final List<String> lore;
    private final List<String> commands;
    private final Integer customModelData;

    private CommandItem(Builder builder) {
        this.materialKey = builder.materialKey;
        this.displayName = builder.displayName;
        this.lore = Collections.unmodifiableList(new ArrayList<>(builder.lore));
        this.commands = Collections.unmodifiableList(new ArrayList<>(builder.commands));
        this.customModelData = builder.customModelData;
    }

    public String materialKey() {
        return materialKey;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> lore() {
        return lore;
    }

    public List<String> commands() {
        return commands;
    }

    public Integer customModelData() {
        return customModelData;
    }

    public ItemStack toPreviewItem(LamMailBox plugin) {
        Material material = resolveMaterial(materialKey);
        if (material == null) {
            material = Material.COMMAND_BLOCK;
        }

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = displayName == null || displayName.isBlank()
                    ? "&6Console Action"
                    : displayName;
            meta.setDisplayName(plugin.colorize(name));

            List<String> displayLore = lore.isEmpty() ? Collections.emptyList() : lore;
            List<String> loreLines = displayLore.stream()
                    .map(plugin::colorize)
                    .toList();
            meta.setLore(loreLines);
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("material", materialKey);
        map.put("name", displayName);
        map.put("lore", new ArrayList<>(lore));
        map.put("commands", new ArrayList<>(commands));
        if (customModelData != null) {
            map.put("custom-model-data", customModelData);
        }
        return map;
    }

    public static CommandItem fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Builder builder = new Builder();
        Object material = map.get("material");
        if (material instanceof String materialStr && !materialStr.isBlank()) {
            builder.material(materialStr.trim());
        }

        Object name = map.get("name");
        if (name instanceof String nameStr) {
            builder.displayName(nameStr);
        }

        Object loreRaw = map.get("lore");
        if (loreRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    builder.addLoreLine(entry.toString());
                }
            }
        }

        Object commandsRaw = map.get("commands");
        if (commandsRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    builder.addCommand(entry.toString());
                }
            }
        }

        Object customModelData = map.get("custom-model-data");
        if (customModelData instanceof Number number) {
            builder.customModelData(number.intValue());
        } else if (customModelData instanceof String value) {
            try {
                builder.customModelData(Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // ignore invalid string
            }
        }

        return builder.build();
    }

    public static CommandItem legacyFromCommand(String command) {
        Builder builder = new Builder();
        builder.addCommand(command);
        builder.displayName("&6Console Command");
        builder.addLoreLine("&7Runs:&f " + command);
        return builder.build();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.material(this.materialKey);
        builder.displayName(this.displayName);
        builder.lore.addAll(this.lore);
        builder.commands.addAll(this.commands);
        builder.customModelData(this.customModelData);
        return builder;
    }

    private static Material resolveMaterial(String materialKey) {
        if (materialKey == null || materialKey.isBlank()) {
            return Material.matchMaterial(DEFAULT_MATERIAL);
        }
        Material match = Material.matchMaterial(materialKey);
        if (match != null) {
            return match;
        }
        try {
            return Material.valueOf(materialKey.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Material.matchMaterial(DEFAULT_MATERIAL);
        }
    }

    public static final class Builder {
        private String materialKey = DEFAULT_MATERIAL;
        private String displayName = "&6Command Item";
        private final List<String> lore = new ArrayList<>();
        private final List<String> commands = new ArrayList<>();
        private Integer customModelData;

        public Builder material(String materialKey) {
            if (materialKey != null && !materialKey.isBlank()) {
                this.materialKey = materialKey.trim().toUpperCase(Locale.ROOT);
            }
            return this;
        }

        public Builder displayName(String displayName) {
            if (displayName != null) {
                this.displayName = displayName;
            }
            return this;
        }

        public Builder clearLore() {
            this.lore.clear();
            return this;
        }

        public Builder addLoreLine(String line) {
            if (line != null) {
                this.lore.add(line);
            }
            return this;
        }

        public boolean removeLastLoreLine() {
            if (this.lore.isEmpty()) {
                return false;
            }
            this.lore.remove(this.lore.size() - 1);
            return true;
        }

        public Builder setLore(List<String> lines) {
            this.lore.clear();
            if (lines != null) {
                lines.forEach(this::addLoreLine);
            }
            return this;
        }

        public Builder clearCommands() {
            this.commands.clear();
            return this;
        }

        public Builder addCommand(String command) {
            if (command != null && !command.isBlank()) {
                this.commands.add(command.trim());
            }
            return this;
        }

        public boolean removeLastCommand() {
            if (this.commands.isEmpty()) {
                return false;
            }
            this.commands.remove(this.commands.size() - 1);
            return true;
        }

        public Builder setCommands(List<String> commands) {
            this.commands.clear();
            if (commands != null) {
                commands.forEach(this::addCommand);
            }
            return this;
        }

        public String materialKey() {
            return materialKey;
        }

        public List<String> lore() {
            return lore;
        }

        public List<String> commands() {
            return commands;
        }

        public String displayName() {
            return displayName;
        }

        public Integer customModelData() {
            return customModelData;
        }

        public Builder customModelData(Integer customModelData) {
            this.customModelData = customModelData;
            return this;
        }

        public CommandItem build() {
            return new CommandItem(this);
        }

        public ItemStack buildPreviewItem(LamMailBox plugin) {
            return build().toPreviewItem(plugin);
        }
    }
}

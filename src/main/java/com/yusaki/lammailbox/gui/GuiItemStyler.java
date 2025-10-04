package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Applies configured lore and custom-model-data to item meta instances.
 */
final class GuiItemStyler {
    private final LamMailBox plugin;

    GuiItemStyler(LamMailBox plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void apply(ItemMeta meta, String basePath) {
        apply(meta, basePath, true);
    }

    void apply(ItemMeta meta, String basePath, boolean allowLoreOverride) {
        if (meta == null || basePath == null || basePath.isEmpty()) {
            return;
        }

        applyLore(meta, basePath, allowLoreOverride);
        applyCustomModelData(meta, basePath);
    }

    private void applyLore(ItemMeta meta, String basePath, boolean allowLoreOverride) {
        List<String> loreLines = collectLoreLines(basePath);
        if (loreLines.isEmpty()) {
            return;
        }

        boolean hasExistingLore = meta.hasLore() && meta.getLore() != null && !meta.getLore().isEmpty();
        if (!allowLoreOverride && hasExistingLore) {
            return;
        }

        List<String> colorized = loreLines.stream()
                .map(plugin::colorize)
                .collect(Collectors.toList());
        meta.setLore(colorized);
    }

    private List<String> collectLoreLines(String basePath) {
        Object loreValue = config().get(basePath + ".lore");
        if (loreValue instanceof String singleLine) {
            return collectSingleLoreLine(singleLine);
        }
        if (loreValue instanceof Collection<?> collection) {
            return collectLoreFromCollection(collection);
        }
        return Collections.emptyList();
    }

    private List<String> collectSingleLoreLine(String line) {
        if (line == null || line.isBlank()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(line);
    }

    private List<String> collectLoreFromCollection(Collection<?> collection) {
        List<String> lines = new ArrayList<>();
        for (Object entry : collection) {
            String line = normalizeLoreEntry(entry);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String normalizeLoreEntry(Object entry) {
        if (entry == null) {
            return null;
        }
        String line = entry.toString();
        return line.isBlank() ? null : line;
    }

    private void applyCustomModelData(ItemMeta meta, String basePath) {
        if (!config().contains(basePath + ".custom-model-data")) {
            return;
        }
        Integer customModelData = parseCustomModelData(config().get(basePath + ".custom-model-data"));
        if (customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
    }

    private Integer parseCustomModelData(Object rawValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        if (rawValue instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

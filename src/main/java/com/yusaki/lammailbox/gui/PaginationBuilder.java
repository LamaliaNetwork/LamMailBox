package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Handles placement of pagination controls across GUIs.
 */
final class PaginationBuilder {
    record Settings(String basePath,
                    Set<Integer> reservedSlots,
                    String previousAction,
                    String nextAction,
                    BiConsumer<ItemMeta, PaginationButtonType> metaCustomizer) {
    }

    enum PaginationButtonType {
        PREVIOUS,
        NEXT,
        INDICATOR
    }

    private final LamMailBox plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey decorationKey;
    private final GuiItemStyler itemStyler;

    PaginationBuilder(LamMailBox plugin,
                      NamespacedKey actionKey,
                      NamespacedKey decorationKey,
                      GuiItemStyler itemStyler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionKey = Objects.requireNonNull(actionKey, "actionKey");
        this.decorationKey = Objects.requireNonNull(decorationKey, "decorationKey");
        this.itemStyler = Objects.requireNonNull(itemStyler, "itemStyler");
    }

    void addPaginationButtons(Inventory inv,
                              Settings settings,
                              int currentPage,
                              int totalPages) {
        if (totalPages <= 1) {
            return;
        }

        FileConfiguration config = plugin.getConfig();
        Set<Integer> reserved = settings.reservedSlots() != null
                ? new HashSet<>(settings.reservedSlots())
                : new HashSet<>();

        String basePath = settings.basePath();
        if (config.getBoolean(basePath + ".previous-button.enabled", true) && currentPage > 1) {
            placePaginationButton(inv,
                    basePath + ".previous-button",
                    "ARROW",
                    "&e← Previous",
                    36,
                    PaginationButtonType.PREVIOUS,
                    currentPage,
                    totalPages,
                    settings.previousAction(),
                    reserved,
                    settings.metaCustomizer());
        }

        if (config.getBoolean(basePath + ".next-button.enabled", true) && currentPage < totalPages) {
            placePaginationButton(inv,
                    basePath + ".next-button",
                    "ARROW",
                    "&eNext →",
                    44,
                    PaginationButtonType.NEXT,
                    currentPage,
                    totalPages,
                    settings.nextAction(),
                    reserved,
                    settings.metaCustomizer());
        }

        if (config.getBoolean(basePath + ".page-indicator.enabled", true)) {
            placePaginationButton(inv,
                    basePath + ".page-indicator",
                    "BOOK",
                    "&6Page %current%/%total%",
                    40,
                    PaginationButtonType.INDICATOR,
                    currentPage,
                    totalPages,
                    null,
                    reserved,
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
        FileConfiguration config = plugin.getConfig();

        int preferred = config.getInt(path + ".slot", defaultSlot);
        String materialName = config.getString(path + ".material", defaultMaterial);
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            return;
        }

        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = config.getString(path + ".name", defaultName);
        if (name != null) {
            if (type == PaginationButtonType.INDICATOR) {
                name = plugin.getMessage(path + ".name", plugin.placeholders(
                        "current", String.valueOf(currentPage),
                        "total", String.valueOf(totalPages)
                ));
            } else {
                name = plugin.legacy(name);
            }
            meta.setDisplayName(name);
        }

        if (type != PaginationButtonType.INDICATOR && action != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }

        if (metaCustomizer != null) {
            metaCustomizer.accept(meta, type);
        }

        itemStyler.apply(meta, path);
        button.setItemMeta(meta);

        Integer targetSlot = findAvailableSlot(inv, preferred, reservedSlots);
        if (targetSlot != null) {
            inv.setItem(targetSlot, button);
        }
    }

    private Integer findAvailableSlot(Inventory inv, int preferred, Set<Integer> reservedSlots) {
        int[] offsets = {0, -1, 1, -2, 2, -3, 3, -4, 4};
        for (int offset : offsets) {
            int candidate = preferred + offset;
            if (isSlotAvailable(inv, candidate, reservedSlots)) {
                return candidate;
            }
        }
        plugin.getLogger().warning("Unable to place pagination control; no free slot near " + preferred);
        return null;
    }

    private boolean isSlotAvailable(Inventory inv, int candidate, Set<Integer> reservedSlots) {
        if (candidate < 0 || candidate >= inv.getSize()) {
            return false;
        }
        if (reservedSlots.contains(candidate)) {
            return false;
        }
        ItemStack existing = inv.getItem(candidate);
        return existing == null || isDecorationItem(existing);
    }

    private boolean isDecorationItem(ItemStack existing) {
        ItemMeta meta = existing.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(decorationKey, PersistentDataType.STRING);
    }
}

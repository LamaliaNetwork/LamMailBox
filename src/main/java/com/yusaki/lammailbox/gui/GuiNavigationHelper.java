package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;

/**
 * Provides shared navigation controls (e.g., back buttons) for GUIs.
 */
final class GuiNavigationHelper {
    private final LamMailBox plugin;
    private final NamespacedKey actionKey;
    private final GuiItemStyler itemStyler;

    GuiNavigationHelper(LamMailBox plugin, NamespacedKey actionKey, GuiItemStyler itemStyler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionKey = Objects.requireNonNull(actionKey, "actionKey");
        this.itemStyler = Objects.requireNonNull(itemStyler, "itemStyler");
    }

    void placeBackButton(Inventory inv, String path, String action) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean(path + ".enabled", true)) {
            return;
        }

        String materialName = config.getString(path + ".material", "ARROW");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.ARROW;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = config.getString(path + ".name", "&cBack");
        meta.setDisplayName(plugin.legacy(name));

        itemStyler.apply(meta, path);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);

        item.setItemMeta(meta);
        int slot = config.getInt(path + ".slot", inv.getSize() - 1);
        inv.setItem(slot, item);
    }
}

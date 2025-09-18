package com.yusaki.lammailbox.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Central place to build plugin inventories so that view logic is kept out of the plugin class.
 */
public interface MailGuiFactory {
    Inventory createMailbox(Player viewer);

    Inventory createMailboxAs(Player admin, Player target);

    Inventory createSentMailbox(Player viewer);

    Inventory createSentMailView(Player viewer, String mailId);

    Inventory createMailView(Player viewer, String mailId);

    Inventory createMailCreation(Player viewer);

    Inventory createItemsEditor(Player viewer);
}

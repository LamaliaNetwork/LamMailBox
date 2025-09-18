package com.yusaki.lammailbox.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public interface MailInventoryHandler {
    void handleClick(InventoryClickEvent event);

    void handleClose(InventoryCloseEvent event);
}

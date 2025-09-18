package com.yusaki.lammailbox.session;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state while a player is composing a mail.
 */
public class MailCreationSession {
    private String receiver;
    private String message;
    private List<ItemStack> items = new ArrayList<>();
    private ItemStack commandBlock;
    private List<String> commands = new ArrayList<>();
    private ItemStack displayCommandBlock;
    private Long scheduleDate;
    private Long expireDate;

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ItemStack> getItems() {
        return items;
    }

    public void setItems(List<ItemStack> items) {
        this.items = items;
    }

    public ItemStack getCommandBlock() {
        return commandBlock;
    }

    public void setCommandBlock(ItemStack commandBlock) {
        this.commandBlock = commandBlock;
    }

    public List<String> getCommands() {
        return commands;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands;
    }

    public ItemStack getDisplayCommandBlock() {
        return displayCommandBlock;
    }

    public void setDisplayCommandBlock(ItemStack displayCommandBlock) {
        this.displayCommandBlock = displayCommandBlock;
    }

    public Long getScheduleDate() {
        return scheduleDate;
    }

    public void setScheduleDate(Long scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public Long getExpireDate() {
        return expireDate;
    }

    public void setExpireDate(Long expireDate) {
        this.expireDate = expireDate;
    }

    public boolean isComplete() {
        return receiver != null && message != null;
    }
}

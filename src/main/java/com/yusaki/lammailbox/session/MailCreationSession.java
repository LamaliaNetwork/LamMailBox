package com.yusaki.lammailbox.session;

import com.yusaki.lammailbox.model.CommandItem;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state while a player is composing a mail.
 */

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
    private List<CommandItem> commandItems = new ArrayList<>();
    private CommandItem.Builder commandItemDraft;
    private Integer commandItemEditIndex;

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
        this.commands = commands != null ? new ArrayList<>(commands) : new ArrayList<>();
        if ((this.commandItems == null || this.commandItems.isEmpty()) && this.commands != null && !this.commands.isEmpty()) {
            this.commandItems = new ArrayList<>();
            for (String command : this.commands) {
                this.commandItems.add(CommandItem.legacyFromCommand(command));
            }
        }
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

    public List<CommandItem> getCommandItems() {
        return commandItems;
    }

    public void setCommandItems(List<CommandItem> commandItems) {
        this.commandItems = commandItems != null ? new ArrayList<>(commandItems) : new ArrayList<>();
        List<String> flattened = this.commandItems.stream()
                .flatMap(item -> item.commands().stream())
                .collect(java.util.stream.Collectors.toList());
        this.commands = flattened;
    }

    public CommandItem.Builder getCommandItemDraft() {
        return commandItemDraft;
    }

    public void setCommandItemDraft(CommandItem.Builder commandItemDraft) {
        this.commandItemDraft = commandItemDraft;
    }

    public Integer getCommandItemEditIndex() {
        return commandItemEditIndex;
    }

    public void setCommandItemEditIndex(Integer commandItemEditIndex) {
        this.commandItemEditIndex = commandItemEditIndex;
    }
}

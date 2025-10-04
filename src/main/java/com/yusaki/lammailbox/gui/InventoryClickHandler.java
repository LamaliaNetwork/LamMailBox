package com.yusaki.lammailbox.gui;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class InventoryClickHandler implements MailInventoryHandler {
    private final LamMailBox plugin;
    private final NamespacedKey decorationKey;
    private final NamespacedKey commandItemIndexKey;
    private final NamespacedKey commandItemActionKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey paginationTargetKey;
    private final NamespacedKey mailIdKey;

    private final DecorationCommandExecutor decorationExecutor;
    private final MainGuiClickActions mainGuiActions;
    private final SentMailGuiClickActions sentMailActions;
    private final MailCreationGuiClickActions mailCreationActions;
    private final ItemsGuiClickActions itemsGuiActions;
    private final CommandItemsClickActions commandItemsActions;
    private final MailViewClickActions mailViewActions;
    private final GuiCloseHandler closeHandler;

    public InventoryClickHandler(LamMailBox plugin) {
        this.plugin = plugin;
        this.decorationKey = new NamespacedKey(plugin, "decorationPath");
        this.commandItemIndexKey = new NamespacedKey(plugin, "commandItemIndex");
        this.commandItemActionKey = new NamespacedKey(plugin, "commandItemAction");
        this.actionKey = new NamespacedKey(plugin, "action");
        this.paginationTargetKey = new NamespacedKey(plugin, "paginationTarget");
        this.mailIdKey = new NamespacedKey(plugin, "mailId");

        this.decorationExecutor = new DecorationCommandExecutor(plugin, decorationKey);
        this.mainGuiActions = new MainGuiClickActions(plugin, actionKey, paginationTargetKey, decorationExecutor);
        this.sentMailActions = new SentMailGuiClickActions(plugin, actionKey, paginationTargetKey, decorationExecutor);
        this.mailCreationActions = new MailCreationGuiClickActions(plugin,
                new MailCreationGuiClickActions.NamespacedKeyProvider(actionKey), decorationExecutor);
        this.itemsGuiActions = new ItemsGuiClickActions(plugin,
                new ItemsGuiClickActions.NamespacedKeyProvider(actionKey), decorationExecutor);
        this.commandItemsActions = new CommandItemsClickActions(plugin, commandItemIndexKey, commandItemActionKey);
        this.mailViewActions = new MailViewClickActions(plugin, actionKey, mailIdKey, decorationExecutor);
        this.closeHandler = new GuiCloseHandler(plugin);
    }


    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        ItemStack clicked = event.getCurrentItem();

        String mainTitle = plugin.colorize(config().getString("gui.main.title"));
        String mainViewingPrefix = plugin.colorize(config().getString("gui.main.title") + " &7(as ");
        boolean isMainGUI = title.equals(mainTitle) || title.startsWith(mainViewingPrefix);

        if (isMainGUI && event.getClickedInventory() == player.getInventory()) {
            event.setCancelled(true);
            return;
        }
        if (clicked == null || event.getClickedInventory() == event.getWhoClicked().getInventory()) {
            return;
        }

        String sentTitle = plugin.colorize(config().getString("gui.sent-mail.title"));
        String sentViewingPrefix = plugin.colorize(config().getString("gui.sent-mail.title") + " &7(as ");
        String commandItemsTitle = plugin.colorize(config().getString("gui.command-items-editor.title", "Command Items"));
        String commandItemCreatorTitle = plugin.colorize(config().getString("gui.command-item-creator.title", "Create Command Item"));

        if (isMainGUI) {
            mainGuiActions.handle(event, player, clicked);
        } else if (title.equals(sentTitle) || title.startsWith(sentViewingPrefix)) {
            sentMailActions.handleListClick(event, player, clicked);
        } else if (title.equals(plugin.colorize(config().getString("gui.sent-mail-view.title")))) {
            sentMailActions.handleDetailClick(event, player, clicked);
        } else if (title.equals(plugin.colorize(config().getString("gui.create-mail.title")))) {
            mailCreationActions.handle(event);
        } else if (title.equals(plugin.colorize(config().getString("gui.items.title")))) {
            itemsGuiActions.handle(event);
        } else if (title.equals(plugin.colorize(config().getString("gui.mail-view.title")))) {
            mailViewActions.handle(event, player, clicked);
        } else if (title.equals(commandItemsTitle)) {
            commandItemsActions.handleEditorClick(event, player, clicked);
        } else if (title.equals(commandItemCreatorTitle)) {
            commandItemsActions.handleCreatorClick(event, player, clicked);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        closeHandler.handle(event);
    }
    private FileConfiguration config() {
        return plugin.getConfig();
    }
}

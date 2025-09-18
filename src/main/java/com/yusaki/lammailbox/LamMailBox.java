package com.yusaki.lammailbox;

import com.tcoded.folialib.FoliaLib;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

import com.yusaki.lammailbox.command.LmbCommandExecutor;
import com.yusaki.lammailbox.command.LmbTabComplete;
import com.yusaki.lammailbox.gui.ConfigMailGuiFactory;
import com.yusaki.lammailbox.gui.InventoryClickHandler;
import com.yusaki.lammailbox.gui.MailGuiFactory;
import com.yusaki.lammailbox.repository.MailRepository;
import com.yusaki.lammailbox.repository.YamlMailRepository;
import com.yusaki.lammailbox.service.DefaultMailService;
import com.yusaki.lammailbox.session.MailCreationController;
import com.yusaki.lammailbox.service.MailDelivery;
import com.yusaki.lammailbox.service.MailService;
import com.yusaki.lammailbox.session.MailCreationSession;
import com.yusaki.lammailbox.config.MailBoxConfigUpdater;

public class LamMailBox extends JavaPlugin implements Listener {
    private FileConfiguration config;
    private MailRepository mailRepository;
    private MailService mailService;
    private FileConfiguration database;
    private Map<UUID, MailCreationSession> mailSessions;
    private Map<UUID, String> awaitingInput;
    private Map<UUID, Boolean> inMailCreation;
    private Map<UUID, String> deleteConfirmations;
    private Map<UUID, String> viewingAsPlayer;
    private FoliaLib foliaLib;
    private InventoryClickHandler inventoryClickHandler;
    private MailGuiFactory mailGuiFactory;
    private MailCreationController mailCreationController;
    private MailBoxConfigUpdater configUpdater;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);

        // Initialize and run config updater
        this.configUpdater = new MailBoxConfigUpdater(this);
        configUpdater.updateConfigs();

        saveDefaultConfig();
        config = getConfig();
        mailRepository = new YamlMailRepository(this);
        database = mailRepository.getBackingConfiguration();
        mailService = new DefaultMailService(this, mailRepository, foliaLib);
        mailSessions = new HashMap<>();
        awaitingInput = new HashMap<>();
        inMailCreation = new HashMap<>();
        deleteConfirmations = new HashMap<>();
        viewingAsPlayer = new HashMap<>();
        inventoryClickHandler = new InventoryClickHandler(this);
        mailGuiFactory = new ConfigMailGuiFactory(this);
        mailCreationController = new MailCreationController(this);

        LmbCommandExecutor lmbCommandExecutor = new LmbCommandExecutor(this);
        getCommand("lmb").setExecutor(lmbCommandExecutor);
        getCommand("lmb").setTabCompleter(new LmbTabComplete(this));

        getCommand("lmbreload").setExecutor((sender, cmd, label, args) -> {
            if (!sender.hasPermission(config.getString("settings.permissions.reload"))) {
                sender.sendMessage(colorize(config.getString("messages.no-permission")));
                return true;
            }

            // Update configs before reloading
            configUpdater.updateConfigs();
            reloadConfig();
            config = getConfig();
            sender.sendMessage(colorize(config.getString("messages.reload-success")));
            return true;
        });

        foliaLib.getScheduler().runTimer(() -> {
            checkScheduledMails();
        }, 1200L, 1200L); // Every minute (1200 ticks)

        Bukkit.getPluginManager().registerEvents(this, this);
        startCleanupTask();
    }


    private void checkScheduledMails() {
        List<MailDelivery> toNotify = mailService.schedulePendingMails();
        toNotify.forEach(delivery ->
                notifyRecipients(delivery.getReceiverSpec(), delivery.getMailId(), delivery.getSenderName()));
    }


    private void startCleanupTask() {
        foliaLib.getScheduler().runTimer(() -> {
            cleanupExpiredMails();
        }, 20L * 3600, 20L * 3600); // Every hour
    }

    private void cleanupExpiredMails() {
        mailService.removeExpiredMails();
    }

    public void openMainGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.open"))) {
            player.sendMessage(colorize(config.getString("messages.no-permission")));
            return;
        }
        player.openInventory(mailGuiFactory.createMailbox(player));
    }

    public void openMailboxAsPlayer(Player admin, Player targetPlayer) {
        if (!admin.hasPermission(config.getString("settings.permissions.view-as"))) {
            admin.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.no-permission")));
            return;
        }
        viewingAsPlayer.put(admin.getUniqueId(), targetPlayer.getName());
        admin.openInventory(mailGuiFactory.createMailboxAs(admin, targetPlayer));
    }

    public void openSentMailGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.open"))) {
            player.sendMessage(colorize(config.getString("messages.no-permission")));
            return;
        }
        player.openInventory(mailGuiFactory.createSentMailbox(player));
    }

    public void openSentMailView(Player player, String mailId) {
        player.openInventory(mailGuiFactory.createSentMailView(player, mailId));
    }

    public void handleSentMailDelete(Player player, String mailId) {
        // Check if player can delete this mail (only admins with delete permission)
        if (!player.hasPermission(config.getString("settings.permissions.delete"))) {
            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.no-permission")));
            return;
        }

        // Check if player is confirming deletion
        String confirmingMailId = deleteConfirmations.get(player.getUniqueId());
        if (confirmingMailId != null && confirmingMailId.equals(mailId)) {
            mailService.deleteMail(mailId);
            deleteConfirmations.remove(player.getUniqueId());
            
            player.closeInventory();
            openSentMailGUI(player);
            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.mail-deleted")));
        } else {
            // First click - ask for confirmation
            deleteConfirmations.put(player.getUniqueId(), mailId);
            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.delete-confirmation")));
        }
    }

    public void openCreateMailGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.compose"))) {
            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.no-permission")));
            return;
        }
        player.openInventory(mailGuiFactory.createMailCreation(player));
    }

    public void openItemsGUI(Player player) {
        inMailCreation.put(player.getUniqueId(), true);
        player.openInventory(mailGuiFactory.createItemsEditor(player));
    }

    private void checkPlayerMails(Player player) {
        if (!database.contains("mails")) return;

        ConfigurationSection mailsSection = database.getConfigurationSection("mails");
        for (String mailId : mailsSection.getKeys(false)) {
            String receiver = database.getString("mails." + mailId + ".receiver");
            boolean isActive = database.getBoolean("mails." + mailId + ".active", true);
            List<String> claimedPlayers = database.getStringList("mails." + mailId + ".claimed-players");

            if (isActive && shouldNotifyPlayer(player, receiver, claimedPlayers)) {
                String sender = database.getString("mails." + mailId + ".sender");
                sendMailNotification(player, mailId, sender);
            }
        }
    }

    private boolean shouldNotifyPlayer(Player player, String receiver, List<String> claimedPlayers) {
        if (receiver.equals("all")) {
            return !claimedPlayers.contains(player.getName());
        } else if (receiver.contains(";")) {
            return Arrays.asList(receiver.split(";")).contains(player.getName());
        } else {
            return receiver.equals(player.getName());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.getBoolean("settings.join-notification")) return;

        Player player = event.getPlayer();
        checkPlayerMails(player);
    }


    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        inventoryClickHandler.handleClick(event);
    }




    public void openMailView(Player player, String mailId) {
        player.openInventory(mailGuiFactory.createMailView(player, mailId));
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String inputType = awaitingInput.remove(player.getUniqueId());
        if (inputType == null) {
            return;
        }

        event.setCancelled(true);
        MailCreationSession session = mailSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        boolean success = true;
        String customSubtitle = null;
        String message = event.getMessage();

        switch (inputType) {
            case "receiver":
                success = mailCreationController.handleReceiverInput(player, message, session);
                customSubtitle = success ?
                        config.getString("messages.current-receiver").replace("%receiver%", message) :
                        config.getString("messages.invalid-receiver");
                break;
            case "message":
                session.setMessage(message.replace("\\n", "\n"));
                customSubtitle = config.getString("messages.current-message");
                mailCreationController.reopenCreationAsync(player);
                break;
            case "command":
                success = mailCreationController.handleCommandInput(player, message, session);
                customSubtitle = config.getString("messages.command-format-display")
                        .replace("%command%", message);
                mailCreationController.reopenCreationAsync(player);
                break;
            case "schedule-date":
                success = mailCreationController.handleDateInput(player, message, session, true);
                customSubtitle = success && session.getScheduleDate() != null ?
                        config.getString("messages.schedule-set")
                                .replace("%date%", new Date(session.getScheduleDate()).toString()) :
                        config.getString("messages.invalid-date-format");
                break;
            case "expire-date":
                success = mailCreationController.handleDateInput(player, message, session, false);
                customSubtitle = success && session.getExpireDate() != null ?
                        config.getString("messages.expire-set")
                                .replace("%date%", new Date(session.getExpireDate()).toString()) :
                        config.getString("messages.invalid-date-format");
                break;
            default:
                success = false;
        }

        mailCreationController.showResponseTitle(player, success, customSubtitle);
        if (!"message".equals(inputType) && !"command".equals(inputType)) {
            mailCreationController.reopenCreationAsync(player);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (awaitingInput.containsKey(player.getUniqueId())) {
                awaitingInput.remove(player.getUniqueId());

                foliaLib.getScheduler().runNextTick((Task) -> {
                    openCreateMailGUI(player);
                });
            }
            // Clear delete confirmations on any interaction
            deleteConfirmations.remove(player.getUniqueId());
        }
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        inventoryClickHandler.handleClose(event);
    }

    public void handleMailSend(Player sender) {
        MailCreationSession session = mailSessions.get(sender.getUniqueId());
        if (session == null || !session.isComplete()) {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.incomplete-mail")));
            return;
        }

        inMailCreation.remove(sender.getUniqueId());

        MailDelivery delivery;
        try {
            delivery = mailService.sendMail(sender, session);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.incomplete-mail")));
            return;
        }

        if (delivery.shouldNotifyNow()) {
            notifyRecipients(delivery.getReceiverSpec(), delivery.getMailId(), delivery.getSenderName());
        }

        mailSessions.remove(sender.getUniqueId());
        sender.closeInventory();

        Long scheduleDate = session.getScheduleDate();
        if (scheduleDate != null && scheduleDate > System.currentTimeMillis()) {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.schedule-set")
                            .replace("%date%", new Date(scheduleDate).toString())));
        } else {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.mail-sent")));
        }
    }

    public String colorize(String text) {
        return text.replace("&", "§");
    }

    private void sendMailNotification(Player receiver, String mailId, String sender) {
        // Chat notification
        if (config.getBoolean("settings.notification.chat-enabled")) {
            String chatMessage = config.getString("messages.new-mail-clickable")
                    .replace("%sender%", sender);

            TextComponent message = new TextComponent(colorize(config.getString("messages.prefix") + chatMessage));
            message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lmb view " + mailId));
            receiver.spigot().sendMessage(message);
        }

        // Title notification
        if (config.getBoolean("settings.notification.title-enabled")) {
            String title = colorize(config.getString("titles.notification.title")
                    .replace("%sender%", sender));
            String subtitle = colorize(config.getString("titles.notification.subtitle")
                    .replace("%sender%", sender));

            int fadeIn = config.getInt("titles.notification.fadein");
            int stay = config.getInt("titles.notification.stay");
            int fadeOut = config.getInt("titles.notification.fadeout");

            receiver.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }

        // Sound notification
        String sound = config.getString("settings.notification.sound");
        if (sound != null && !sound.isEmpty()) {
            float volume = (float) config.getDouble("settings.notification.volume");
            float pitch = (float) config.getDouble("settings.notification.pitch");
            receiver.playSound(receiver.getLocation(), Sound.valueOf(sound), volume, pitch);
        }
    }

    private void notifyRecipients(String receiverSpec, String mailId, String senderName) {
        if (receiverSpec == null || receiverSpec.isEmpty()) {
            return;
        }

        if (receiverSpec.equalsIgnoreCase("all")) {
            Bukkit.getOnlinePlayers().forEach(player ->
                    sendMailNotification(player, mailId, senderName));
            return;
        }

        if (receiverSpec.contains(";")) {
            Arrays.stream(receiverSpec.split(";"))
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .forEach(player -> sendMailNotification(player, mailId, senderName));
            return;
        }

        Player receiver = Bukkit.getPlayer(receiverSpec);
        if (receiver != null) {
            sendMailNotification(receiver, mailId, senderName);
        }
    }

    public void dispatchMailNotifications(String receiverSpec, String mailId, String senderName) {
        notifyRecipients(receiverSpec, mailId, senderName);
    }

    public MailCreationController getMailCreationController() {
        return mailCreationController;
    }

    public MailService getMailService() {
        return mailService;
    }

    public MailRepository getMailRepository() {
        return mailRepository;
    }

    public Map<UUID, MailCreationSession> getMailSessions() {
        return mailSessions;
    }

    public Map<UUID, String> getAwaitingInput() {
        return awaitingInput;
    }

    public Map<UUID, Boolean> getInMailCreation() {
        return inMailCreation;
    }

    public Map<UUID, String> getDeleteConfirmations() {
        return deleteConfirmations;
    }

    public Map<UUID, String> getViewingAsPlayer() {
        return viewingAsPlayer;
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }

    public MailBoxConfigUpdater getConfigUpdater() {
        return configUpdater;
    }
}

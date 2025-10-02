package com.yusaki.lammailbox;

import com.tcoded.folialib.FoliaLib;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
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
import java.util.stream.Collectors;

import com.yusaki.lammailbox.command.LmbCommandExecutor;
import com.yusaki.lammailbox.command.LmbMigrateCommand;
import com.yusaki.lammailbox.command.LmbTabComplete;
import com.yusaki.lammailbox.config.StorageSettings;
import com.yusaki.lammailbox.gui.ConfigMailGuiFactory;
import com.yusaki.lammailbox.gui.InventoryClickHandler;
import com.yusaki.lammailbox.gui.MailGuiFactory;
import com.yusaki.lammailbox.mailing.MailingConfigLoader;
import com.yusaki.lammailbox.mailing.MailingDefinition;
import com.yusaki.lammailbox.mailing.MailingScheduler;
import com.yusaki.lammailbox.mailing.status.MailingStatusRepository;
import com.yusaki.lammailbox.mailing.status.SqliteMailingStatusRepository;
import com.yusaki.lammailbox.mailing.status.YamlMailingStatusRepository;
import com.yusaki.lammailbox.repository.MailRepository;
import com.yusaki.lammailbox.repository.SqliteMailRepository;
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
    private StorageSettings.BackendType activeBackend;
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
    private MailingConfigLoader mailingConfigLoader;
    private MailingStatusRepository mailingStatusRepository;
    private MailingScheduler mailingScheduler;
    private List<MailingDefinition> mailingDefinitions;
    private boolean mailingAutoCleanup;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);

        // Initialize and run config updater
        this.configUpdater = new MailBoxConfigUpdater(this);
        configUpdater.updateConfigs();

        saveDefaultConfig();
        config = getConfig();
        StorageSettings storageSettings = StorageSettings.load(this);
        mailRepository = createRepository(storageSettings);
        mailService = new DefaultMailService(this, mailRepository, foliaLib);
        mailSessions = new HashMap<>();
        awaitingInput = new HashMap<>();
        inMailCreation = new HashMap<>();
        deleteConfirmations = new HashMap<>();
        viewingAsPlayer = new HashMap<>();
        inventoryClickHandler = new InventoryClickHandler(this);
        mailGuiFactory = new ConfigMailGuiFactory(this);
        mailCreationController = new MailCreationController(this);
        applyCommandAliases();

        mailingConfigLoader = new MailingConfigLoader(this);
        mailingConfigLoader.saveDefaultIfMissing();
        mailingStatusRepository = createMailingStatusRepository(storageSettings);
        mailingDefinitions = mailingConfigLoader.load();
        mailingAutoCleanup = getConfig().getBoolean("mailings.auto-cleanup", true);
        performMailingCleanup();
        mailingScheduler = new MailingScheduler(this, mailService, mailingStatusRepository, foliaLib);
        mailingScheduler.start(mailingDefinitions);

        LmbCommandExecutor lmbCommandExecutor = new LmbCommandExecutor(this);
        LmbTabComplete tabCompleter = new LmbTabComplete(this);
        getCommand("lmb").setExecutor(lmbCommandExecutor);
        getCommand("lmb").setTabCompleter(tabCompleter);

        getCommand("lmbmigrate").setExecutor(new LmbMigrateCommand(this));
        getCommand("lmbmigrate").setTabCompleter(tabCompleter);

        getCommand("lmbreload").setExecutor((sender, cmd, label, args) -> {
            if (!sender.hasPermission(config.getString("settings.permissions.reload"))) {
                sender.sendMessage(colorize(config.getString("messages.no-permission")));
                return true;
            }

            // Update configs before reloading
            configUpdater.updateConfigs();
            reloadConfig();
            config = getConfig();
            mailingAutoCleanup = config.getBoolean("mailings.auto-cleanup", true);
            applyCommandAliases();
            reloadMailings();
            sender.sendMessage(colorize(config.getString("messages.reload-success")));

            List<MailingDefinition> definitions = getMailingDefinitions();
            if (!definitions.isEmpty()) {
                long activeCount = definitions.stream().filter(MailingDefinition::enabled).count();
                String summary = config.getString("messages.prefix") + "&7Mailings active: &a" + activeCount + "&7/&f" + definitions.size();
                sender.sendMessage(colorize(summary));

                if (activeCount > 0) {
                    int previewLimit = 5;
                    List<String> activeIds = definitions.stream()
                            .filter(MailingDefinition::enabled)
                            .map(MailingDefinition::id)
                            .collect(Collectors.toList());
                    List<String> preview = activeIds.size() > previewLimit
                            ? activeIds.subList(0, previewLimit)
                            : activeIds;
                    String idsLine = String.join("&f, ", preview);
                    String detail = config.getString("messages.prefix") + "&7Active IDs: &f" + idsLine;
                    if (activeIds.size() > previewLimit) {
                        detail += " &7(+" + (activeIds.size() - previewLimit) + " more)";
                    }
                    sender.sendMessage(colorize(detail));
                }
            } else {
                sender.sendMessage(colorize(config.getString("messages.prefix") + "&7No mailings configured."));
            }
            return true;
        });

        foliaLib.getScheduler().runTimer(() -> {
            checkScheduledMails();
        }, 1200L, 1200L); // Every minute (1200 ticks)

        Bukkit.getPluginManager().registerEvents(this, this);
        startCleanupTask();
    }

    @Override
    public void onDisable() {
        if (mailRepository != null) {
            mailRepository.shutdown();
        }
        if (mailingScheduler != null) {
            mailingScheduler.shutdown();
        }
        if (mailingStatusRepository != null) {
            mailingStatusRepository.shutdown();
        }
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
        String playerName = player.getName();
        for (String mailId : mailRepository.listActiveMailIdsFor(playerName)) {
            mailRepository.findRecord(mailId).ifPresent(record -> {
                if (record.canBeClaimedBy(playerName)) {
                    String sender = Optional.ofNullable(record.sender()).orElse("Console");
                    sendMailNotification(player, mailId, sender);
                }
            });
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.getBoolean("settings.join-notification")) return;

        Player player = event.getPlayer();
        checkPlayerMails(player);
        if (mailingScheduler != null) {
            mailingScheduler.handlePlayerJoin(player);
        }
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
            String command = "/" + getPrimaryCommand() + " view " + mailId;
            message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
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
                    scheduleNotification(player, mailId, senderName));
            return;
        }

        if (receiverSpec.contains(";")) {
            Arrays.stream(receiverSpec.split(";"))
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .forEach(player -> scheduleNotification(player, mailId, senderName));
            return;
        }

        Player receiver = Bukkit.getPlayer(receiverSpec);
        if (receiver != null) {
            scheduleNotification(receiver, mailId, senderName);
        }
    }

    private void scheduleNotification(Player player, String mailId, String senderName) {
        // Schedule on player's region thread for Folia compatibility
        foliaLib.getScheduler().runAtEntity(player, task -> {
            if (player.isOnline()) {
                sendMailNotification(player, mailId, senderName);
            }
        });
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

    public StorageSettings.BackendType getActiveBackend() {
        return activeBackend;
    }

    public MailRepository buildRepository(StorageSettings settings,
                                          StorageSettings.BackendType backendType,
                                          boolean allowImport) {
        if (backendType == StorageSettings.BackendType.SQLITE) {
            return new SqliteMailRepository(this, settings.sqlitePath());
        }
        return new YamlMailRepository(this);
    }

    private MailRepository createRepository(StorageSettings settings) {
        MailRepository repository = buildRepository(settings, settings.backendType(), true);
        if (settings.backendType() == StorageSettings.BackendType.SQLITE) {
            getLogger().info("Loaded LamMailBox using SQLite storage");
        } else {
            getLogger().info("Loaded LamMailBox using YAML storage");
        }
        activeBackend = settings.backendType();
        return repository;
    }

    private MailingStatusRepository createMailingStatusRepository(StorageSettings settings) {
        if (settings.backendType() == StorageSettings.BackendType.SQLITE) {
            return new SqliteMailingStatusRepository(this, settings.sqlitePath());
        }
        return new YamlMailingStatusRepository(this);
    }

    public void reloadMailings() {
        mailingConfigLoader.saveDefaultIfMissing();
        mailingDefinitions = mailingConfigLoader.load();
        performMailingCleanup();
        if (mailingScheduler != null) {
            mailingScheduler.updateDefinitions(mailingDefinitions);
        }
    }

    private void performMailingCleanup() {
        if (!mailingAutoCleanup || mailingStatusRepository == null || mailingDefinitions == null) {
            return;
        }
        Set<String> activeIds = mailingDefinitions.stream()
                .map(MailingDefinition::id)
                .collect(Collectors.toSet());
        mailingStatusRepository.purgeMissingMailings(activeIds);
        getLogger().info("Mailing status cleanup executed for IDs: " + activeIds);
    }

    private void applyCommandAliases() {
        PluginCommand command = getCommand("lmb");
        if (command == null) {
            return;
        }

        FileConfiguration config = getConfig();
        ConfigurationSection aliasSection = config.getConfigurationSection("settings.command-aliases");
        List<String> aliases = aliasSection != null ? aliasSection.getStringList("base") : Collections.emptyList();
        if (aliases == null) {
            aliases = Collections.emptyList();
        }
        command.setAliases(aliases);

        SimpleCommandMap commandMap = findCommandMap();
        if (commandMap == null) {
            getLogger().warning("Unable to re-register command aliases for /lmb; command map not accessible.");
            return;
        }

        try {
            command.unregister(commandMap);
            commandMap.register(getDescription().getName().toLowerCase(Locale.ROOT), command);
        } catch (Exception ex) {
            getLogger().warning("Failed to re-register /lmb aliases: " + ex.getMessage());
        }
    }

    private SimpleCommandMap findCommandMap() {
        CommandMap map = null;
        try {
            map = (CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException ignored) {
        }

        if (map instanceof SimpleCommandMap) {
            return (SimpleCommandMap) map;
        }

        try {
            var field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            map = (CommandMap) field.get(Bukkit.getServer());
            if (map instanceof SimpleCommandMap) {
                return (SimpleCommandMap) map;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private String getPrimaryCommand() {
        List<String> aliases = config.getStringList("settings.command-aliases.base");
        return aliases.isEmpty() ? "lmb" : aliases.get(0);
    }

    public List<MailingDefinition> getMailingDefinitions() {
        if (mailingDefinitions == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(mailingDefinitions));
    }

    public MailingStatusRepository getMailingStatusRepository() {
        return mailingStatusRepository;
    }

    public MailingScheduler getMailingScheduler() {
        return mailingScheduler;
    }
}

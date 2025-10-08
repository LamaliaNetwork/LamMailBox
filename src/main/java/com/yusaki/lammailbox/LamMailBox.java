package com.yusaki.lammailbox;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.tcoded.folialib.FoliaLib;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
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
import org.yusaki.lib.YskLib;
import org.yusaki.lib.modules.CommandAliasManager;
import org.yusaki.lib.modules.MessageManager;

public class LamMailBox extends JavaPlugin implements Listener {
    private FileConfiguration config;
    private MailRepository mailRepository;
    private MailService mailService;
    private StorageSettings.BackendType activeBackend;
    private YskLib yskLib;
    private MessageManager messageManager;
    private Map<UUID, MailCreationSession> mailSessions;
    private Map<UUID, String> awaitingInput;
    private Map<UUID, Boolean> inMailCreation;
    private Map<UUID, String> deleteConfirmations;
    private Map<UUID, String> viewingAsPlayer;
    private Map<UUID, Integer> mailViewPages;
    private Map<UUID, Integer> mailboxPages;
    private Map<UUID, Integer> sentMailboxPages;
    private String primaryCommand = "lmb";
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
    private CronParser cronParser;
    private CronDescriptor cronDescriptor;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    private Component cachedPrefixComponent;
    private String cachedPrefixLegacy;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);

        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        cronParser = new CronParser(cronDefinition);
        cronDescriptor = CronDescriptor.instance(Locale.getDefault());

        // Initialize and run config updater
        this.configUpdater = new MailBoxConfigUpdater(this);
        configUpdater.updateConfigs();

        saveDefaultConfig();
        config = getConfig();

        // Initialize YskLib MessageManager
        yskLib = (YskLib) getServer().getPluginManager().getPlugin("YskLib");
        if (yskLib == null) {
            getLogger().severe("YskLib not found! Please install YskLib 1.6.7 or above.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        yskLib.loadMessages(this);
        messageManager = yskLib.getMessageManager();
        invalidatePrefixCache();

        StorageSettings storageSettings = StorageSettings.load(this);
        mailRepository = createRepository(storageSettings);
        mailService = new DefaultMailService(this, mailRepository, foliaLib);
        mailSessions = new HashMap<>();
        awaitingInput = new HashMap<>();
        inMailCreation = new HashMap<>();
        deleteConfirmations = new HashMap<>();
        viewingAsPlayer = new HashMap<>();
        mailViewPages = new HashMap<>();
        mailboxPages = new HashMap<>();
        sentMailboxPages = new HashMap<>();
        inventoryClickHandler = new InventoryClickHandler(this);
        mailGuiFactory = new ConfigMailGuiFactory(this);
        mailCreationController = new MailCreationController(this);
        updateCommandAliases();

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
                sendPrefixedMessage(sender, "messages.no-permission");
                return true;
            }

            // Update configs before reloading
            configUpdater.updateConfigs();
            reloadConfig();
            config = getConfig();
            yskLib.loadMessages(this);
            messageManager = yskLib.getMessageManager();
            invalidatePrefixCache();
            mailingAutoCleanup = config.getBoolean("mailings.auto-cleanup", true);
            updateCommandAliases();
            reloadMailings();
            sendPrefixedMessage(sender, "messages.reload-success");

            List<MailingDefinition> definitions = getMailingDefinitions();
            if (!definitions.isEmpty()) {
                long activeCount = definitions.stream().filter(MailingDefinition::enabled).count();
                sendPrefixedRaw(sender, "&7Mailings active: &a" + activeCount + "&7/&f" + definitions.size());

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
                    String detail = "&7Active IDs: &f" + idsLine;
                    if (activeIds.size() > previewLimit) {
                        detail += " &7(+" + (activeIds.size() - previewLimit) + " more)";
                    }
                    sendPrefixedRaw(sender, detail);
                }
            } else {
                String emptyMessage = getMailingsMessage("empty", "&7No mailings configured.");
                sendPrefixedRaw(sender, emptyMessage);
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
            sendPrefixedMessage(player, "messages.no-permission");
            return;
        }
        player.openInventory(mailGuiFactory.createMailbox(player));
    }

    public void openMailboxAsPlayer(Player admin, Player targetPlayer) {
        if (!admin.hasPermission(config.getString("settings.permissions.view-as"))) {
            sendPrefixedMessage(admin, "messages.no-permission");
            return;
        }
        viewingAsPlayer.put(admin.getUniqueId(), targetPlayer.getName());
        admin.openInventory(mailGuiFactory.createMailboxAs(admin, targetPlayer));
    }

    public void openSentMailGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.open"))) {
            sendPrefixedMessage(player, "messages.no-permission");
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
            sendPrefixedMessage(player, "messages.no-permission");
            return;
        }

        // Check if player is confirming deletion
        String confirmingMailId = deleteConfirmations.get(player.getUniqueId());
        if (confirmingMailId != null && confirmingMailId.equals(mailId)) {
            mailService.deleteMail(mailId);
            deleteConfirmations.remove(player.getUniqueId());
            
            player.closeInventory();
            openSentMailGUI(player);
            sendPrefixedMessage(player, "messages.mail-deleted");
        } else {
            // First click - ask for confirmation
            deleteConfirmations.put(player.getUniqueId(), mailId);
            sendPrefixedMessage(player, "messages.delete-confirmation");
        }
    }

    public void openCreateMailGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.compose"))) {
            sendPrefixedMessage(player, "messages.no-permission");
            return;
        }
        player.openInventory(mailGuiFactory.createMailCreation(player));
    }

    public void openItemsGUI(Player player) {
        inMailCreation.put(player.getUniqueId(), true);
        player.openInventory(mailGuiFactory.createItemsEditor(player));
    }

    public void openCommandItemsEditor(Player player) {
        if (!player.hasPermission(config.getString("settings.admin-permission"))) {
            sendPrefixedMessage(player, "messages.no-permission");
            return;
        }
        inMailCreation.put(player.getUniqueId(), true);
        player.openInventory(mailGuiFactory.createCommandItemsEditor(player));
    }

    public void openCommandItemCreator(Player player) {
        if (!player.hasPermission(config.getString("settings.admin-permission"))) {
            sendPrefixedMessage(player, "messages.no-permission");
            return;
        }
        inMailCreation.put(player.getUniqueId(), true);
        player.openInventory(mailGuiFactory.createCommandItemCreator(player));
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
        boolean reopenMailCreation = false;
        boolean reopenCommandCreator = false;

        switch (inputType) {
            case "receiver":
                success = mailCreationController.handleReceiverInput(player, message, session);
                customSubtitle = success
                        ? applyPlaceholderVariants(config.getString("messages.current-receiver"), "receiver", message)
                        : config.getString("messages.invalid-receiver");
                reopenMailCreation = true;
                break;
            case "message":
                session.setMessage(message.replace("\\n", "\n"));
                customSubtitle = config.getString("messages.current-message");
                mailCreationController.reopenCreationAsync(player);
                break;
            case "command":
                success = mailCreationController.handleCommandInput(player, message, session);
                customSubtitle = applyPlaceholderVariants(
                        config.getString("messages.command-format-display"),
                        "command",
                        message);
                mailCreationController.reopenCreationAsync(player);
                break;
            case "schedule-date":
                success = mailCreationController.handleDateInput(player, message, session, true);
                customSubtitle = success && session.getScheduleDate() != null
                        ? applyPlaceholderVariants(
                                config.getString("messages.schedule-set"),
                                "date",
                                new Date(session.getScheduleDate()).toString())
                        : config.getString("messages.invalid-date-format");
                reopenMailCreation = true;
                break;
            case "expire-date":
                success = mailCreationController.handleDateInput(player, message, session, false);
                customSubtitle = success && session.getExpireDate() != null
                        ? applyPlaceholderVariants(
                                config.getString("messages.expire-set"),
                                "date",
                                new Date(session.getExpireDate()).toString())
                        : config.getString("messages.invalid-date-format");
                reopenMailCreation = true;
                break;
            case "command-item-material":
                success = mailCreationController.handleCommandItemMaterialInput(player, message, session);
                customSubtitle = success
                        ? config.getString("messages.command-item-material-set", "&a✔ Command item material updated.")
                        : config.getString("messages.invalid-material", "&c✖ Invalid material!");
                reopenCommandCreator = true;
                break;
            case "command-item-name":
                success = mailCreationController.handleCommandItemNameInput(player, message, session);
                customSubtitle = success
                        ? config.getString("messages.command-item-name-set", "&a✔ Display name updated.")
                        : config.getString("messages.command-item-name-failed", "&c✖ Name update failed.");
                reopenCommandCreator = true;
                break;
            case "command-item-lore":
                success = mailCreationController.handleCommandItemLoreInput(player, message, session);
                customSubtitle = success
                        ? config.getString("messages.command-item-lore-added", "&a✔ Added lore line.")
                        : config.getString("messages.command-item-lore-failed", "&c✖ Lore update failed");
                reopenCommandCreator = true;
                break;
            case "command-item-command":
                success = mailCreationController.handleCommandItemCommandInput(player, message, session);
                customSubtitle = success
                        ? config.getString("messages.command-item-command-added", "&a✔ Added command.")
                        : config.getString("messages.command-item-command-failed", "&c✖ Command update failed");
                reopenCommandCreator = true;
                break;
            case "command-item-custom-model":
                String trimmed = message == null ? "" : message.trim();
                success = mailCreationController.handleCommandItemCustomModelInput(player, message, session);
                if (success) {
                    if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("clear") || trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("reset")) {
                        customSubtitle = config.getString("messages.command-item-model-cleared", "&a✔ Custom model data cleared.");
                    } else {
                        customSubtitle = applyPlaceholderVariants(
                                config.getString("messages.command-item-model-set", "&a✔ Custom model data updated."),
                                "value",
                                trimmed);
                    }
                } else {
                    customSubtitle = config.getString("messages.command-item-model-invalid", "&c✖ Invalid custom model data.");
                }
                reopenCommandCreator = true;
                break;
            default:
                success = false;
        }

        mailCreationController.showResponseTitle(player, success, customSubtitle);
        if (reopenCommandCreator) {
            mailCreationController.reopenCommandItemCreatorAsync(player);
        } else if (reopenMailCreation) {
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
            sendPrefixedMessage(sender, "messages.incomplete-mail");
            return;
        }

        inMailCreation.remove(sender.getUniqueId());

        MailDelivery delivery;
        try {
            delivery = mailService.sendMail(sender, session);
        } catch (IllegalArgumentException ex) {
            sendPrefixedMessage(sender, "messages.incomplete-mail");
            return;
        }

        if (delivery.shouldNotifyNow()) {
            notifyRecipients(delivery.getReceiverSpec(), delivery.getMailId(), delivery.getSenderName());
        }

        mailSessions.remove(sender.getUniqueId());
        sender.closeInventory();

        Long scheduleDate = session.getScheduleDate();
        if (scheduleDate != null && scheduleDate > System.currentTimeMillis()) {
            sendPrefixedMessage(sender, "messages.schedule-set",
                    placeholders("date", new Date(scheduleDate).toString()));
        } else {
            sendPrefixedMessage(sender, "messages.mail-sent");
        }
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public void invalidatePrefixCache() {
        cachedPrefixComponent = null;
        cachedPrefixLegacy = null;
    }

    public Component deserializeLegacy(String text) {
        return legacySerializer.deserialize(text == null ? "" : text);
    }

    public String legacy(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return legacySerializer.serialize(deserializeLegacy(text));
    }

    public Component getPrefixComponent() {
        if (cachedPrefixComponent == null) {
            cachedPrefixComponent = deserializeLegacy(messageManager.getPrefix(this));
        }
        return cachedPrefixComponent;
    }

    public String getPrefixLegacy() {
        if (cachedPrefixLegacy == null) {
            cachedPrefixLegacy = legacy(messageManager.getPrefix(this));
        }
        return cachedPrefixLegacy;
    }

    public Component prefixed(Component body) {
        return getPrefixComponent().append(body == null ? Component.empty() : body);
    }

    public String prefixedLegacy(String body) {
        Component component = body == null ? Component.empty() : deserializeLegacy(body);
        return legacySerializer.serialize(prefixed(component));
    }

    public void sendPrefixedMessage(org.bukkit.command.CommandSender sender, String key) {
        messageManager.sendPrefixedMessage(this, sender, key);
    }

    public void sendPrefixedMessage(org.bukkit.command.CommandSender sender, String key, Map<String, String> placeholders) {
        messageManager.sendPrefixedMessage(this, sender, key, placeholders);
    }

    public void sendPrefixedMessageList(org.bukkit.command.CommandSender sender, String key) {
        messageManager.sendPrefixedMessageList(this, sender, key);
    }

    public void sendPrefixedRaw(org.bukkit.command.CommandSender sender, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        sender.sendMessage(prefixedLegacy(message));
    }

    public String applyPlaceholderVariants(String template, String key, String value) {
        if (template == null || key == null) {
            return template;
        }

        String normalized = normalizePlaceholderKey(key);
        String safeValue = value == null ? "" : value;
        String result = template;

        result = result.replace("{" + normalized + "}", safeValue);
        result = result.replace("%" + normalized + "%", safeValue);

        if (!key.equals("{" + normalized + "}") && !key.equals("%" + normalized + "%")) {
            result = result.replace(key, safeValue);
        }

        return result;
    }

    public String applyPlaceholderVariants(String template, Map<String, String> placeholders) {
        if (template == null || placeholders == null || placeholders.isEmpty()) {
            return template;
        }

        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = applyPlaceholderVariants(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String normalizePlaceholderKey(String key) {
        if (key == null || key.length() < 2) {
            return key == null ? "" : key;
        }

        if ((key.startsWith("{") && key.endsWith("}")) || (key.startsWith("%") && key.endsWith("%"))) {
            return key.substring(1, key.length() - 1);
        }

        return key;
    }

    /**
     * Get a message from config with placeholders
     */
    public String getMessage(String key, Map<String, String> placeholders) {
        return messageManager.getMessage(this, key, placeholders);
    }

    /**
     * Get a message from config without placeholders
     */
    public String getMessage(String key) {
        return messageManager.getMessage(this, key);
    }

    /**
     * Send a message to a sender with placeholders
     */
    public void sendMessage(org.bukkit.command.CommandSender sender, String key, Map<String, String> placeholders) {
        messageManager.sendMessage(this, sender, key, placeholders);
    }

    /**
     * Send a message to a sender without placeholders
     */
    public void sendMessage(org.bukkit.command.CommandSender sender, String key) {
        messageManager.sendMessage(this, sender, key);
    }

    /**
     * Create a placeholder map from key-value pairs
     */
    public Map<String, String> placeholders(String... keyValuePairs) {
        return MessageManager.placeholders(keyValuePairs);
    }

    public String getMailingsMessage(String key, String fallback) {
        String path = "messages.mailings." + key;
        return config.getString(path, fallback);
    }

    public String describeCron(String expression) {
        if (expression == null || expression.isBlank()) {
            return expression;
        }
        if (cronParser == null || cronDescriptor == null) {
            return expression;
        }

        try {
            Cron cron = cronParser.parse(expression);
            cron.validate();
            return cronDescriptor.describe(cron);
        } catch (IllegalArgumentException ex) {
            getLogger().fine(() -> "Unable to describe cron expression '" + expression + "': " + ex.getMessage());
            return expression;
        } catch (Exception ex) {
            getLogger().fine(() -> "Unexpected error describing cron expression '" + expression + "': " + ex.getMessage());
            return expression;
        }
    }

    private void sendMailNotification(Player receiver, String mailId, String sender) {
        // Chat notification
        if (config.getBoolean("settings.notification.chat-enabled")) {
            String chatMessage = applyPlaceholderVariants(
                    config.getString("messages.new-mail-clickable"),
                    "sender",
                    sender);

            TextComponent message = new TextComponent(prefixedLegacy(chatMessage));
            String command = "/" + getPrimaryCommand() + " view " + mailId;
            message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
            receiver.spigot().sendMessage(message);
        }

        // Title notification
        if (config.getBoolean("settings.notification.title-enabled")) {
            yskLib.messageManager.sendTitle(
                    this,
                    receiver,
                    "titles.notification.title",
                    "titles.notification.subtitle",
                    config.getInt("titles.notification.fadein"),
                    config.getInt("titles.notification.stay"),
                    config.getInt("titles.notification.fadeout"),
                    MessageManager.placeholders("sender", sender)
            );
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

    public Map<UUID, Integer> getMailViewPages() {
        return mailViewPages;
    }

    public Map<UUID, Integer> getMailboxPages() {
        return mailboxPages;
    }

    public Map<UUID, Integer> getSentMailboxPages() {
        return sentMailboxPages;
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

    private void updateCommandAliases() {
        FileConfiguration config = getConfig();
        String primaryPath = "settings.command-aliases.lmb";
        String legacyPath = "settings.command-aliases.base";

        List<String> aliases;
        if (config.contains(primaryPath)) {
            aliases = CommandAliasManager.applyAliases(this, "lmb", config, primaryPath);
            if (aliases.isEmpty() && config.contains(legacyPath)) {
                aliases = CommandAliasManager.applyAliases(this, "lmb", config, legacyPath);
            }
        } else {
            aliases = CommandAliasManager.applyAliases(this, "lmb", config, legacyPath);
        }

        primaryCommand = aliases.isEmpty() ? "lmb" : aliases.get(0);
    }

    private String getPrimaryCommand() {
        return primaryCommand;
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

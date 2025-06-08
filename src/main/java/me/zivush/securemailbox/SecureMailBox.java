package me.zivush.securemailbox;

import com.tcoded.folialib.FoliaLib;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class SecureMailBox extends JavaPlugin implements Listener {
    private FileConfiguration config;
    private File databaseFile;
    private FileConfiguration database;
    private Map<UUID, MailCreationSession> mailSessions;
    private Map<UUID, String> awaitingInput;
    private Map<UUID, Inventory> itemsInventories;
    private Map<UUID, Boolean> inMailCreation;
    private FoliaLib foliaLib;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);
        saveDefaultConfig();
        config = getConfig();
        setupDatabase();
        mailSessions = new HashMap<>();
        awaitingInput = new HashMap<>();
        itemsInventories = new HashMap<>();
        inMailCreation = new HashMap<>();

        getCommand("smb").setExecutor((sender, cmd, label, args) -> {
            if (args.length > 0) {
                if (args[0].equals("send")) {
                    
                    if (args.length < 3) {
                        sender.sendMessage(colorize(config.getString("messages.prefix") + "&cUsage: /smb send <users> <message> | [commands]"));
                        return true;
                    }

                    String receiver = args[1];
                    String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    
                    MailCreationSession session = new MailCreationSession();
                    session.setReceiver(receiver);
                    
                    // Handle commands if present, check if sender has admin permission or is console
                    List<String> commands = new ArrayList<>();
                    String messageContent = message;
                    if (message.contains("|")) {
                        String[] parts = message.split("\\|", 2);
                        messageContent = parts[0].trim();
                        
                        if (sender instanceof Player && !sender.hasPermission(config.getString("settings.admin-permission"))) {
                             sender.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.no-permission")));
                        } else {
                            String[] commandParts = parts[1].trim().split(",");
                            for (String command : commandParts) {
                                commands.add(command.trim());
                            }
                        }
                    }
                    session.setMessage(messageContent);
                    session.setCommands(commands);

                    // Set expiration date to 1 year from now
                    long expireTime = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000);
                    session.setExpireDate(expireTime);

                    // Handle sending based on sender type
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        mailSessions.put(player.getUniqueId(), session);
                        handleMailSend(player);
                    } else { // Console sender
                        // Directly send mail from console context
                        String mailId = UUID.randomUUID().toString();
                        boolean isAdminMail = true; // Console sends are typically admin-level

                        String dbPath = "mails." + mailId + ".";
                        database.set(dbPath + "sender", sender.getName()); // Sender will be "CONSOLE"
                        database.set(dbPath + "receiver", session.getReceiver());
                        database.set(dbPath + "message", session.getMessage());
                        database.set(dbPath + "sent-date", System.currentTimeMillis());
                        database.set(dbPath + "schedule-date", null); // Instant send
                        database.set(dbPath + "expire-date", session.getExpireDate());
                        database.set(dbPath + "active", true);
                        database.set(dbPath + "is-admin-mail", isAdminMail);
                        database.set(dbPath + "claimed-players", new ArrayList<String>());

                        if (!session.getCommands().isEmpty()) {
                            database.set(dbPath + "command-block", null); // Console doesn't have an item for this
                            database.set(dbPath + "commands", session.getCommands());
                        }
                        
                        // Console cannot send items via command in this implementation
                        database.set(dbPath + "items", new ArrayList<String>());

                        saveDatabase();

                        // Notify recipients
                        String finalReceiver = session.getReceiver();
                        if (finalReceiver.equals("all")) {
                            Bukkit.getOnlinePlayers().forEach(p -> sendMailNotification(p, mailId, sender.getName()));
                        } else if (finalReceiver.contains(";")) {
                            Arrays.stream(finalReceiver.split(";"))
                                    .map(Bukkit::getPlayer)
                                    .filter(Objects::nonNull)
                                    .forEach(p -> sendMailNotification(p, mailId, sender.getName()));
                        } else {
                            Player receiverPlayer = Bukkit.getPlayer(finalReceiver);
                            if (receiverPlayer != null) {
                                sendMailNotification(receiverPlayer, mailId, sender.getName());
                            }
                        }

                        sender.sendMessage(colorize(config.getString("messages.prefix") + "&aMail sent successfully."));
                    }

                    return true;
                } else if (args[0].equals("view")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(colorize(config.getString("messages.console-only")));
                        return true;
                    }
                    Player player = (Player) sender;

                    if (args.length == 2) {
                        String mailId = args[1];
                        String dbPath = "mails." + mailId + ".";

                        if (!database.contains(dbPath)) {
                            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.mail-not-found")));
                            return true;
                        }

                        String receiver = database.getString(dbPath + "receiver");
                        List<String> claimedPlayers = database.getStringList(dbPath + "claimed-players");

                        boolean canAccess = false;
                        boolean isActive = database.getBoolean(dbPath + "active", true);
                        if (isActive) {
                            if (receiver.equals("all")) {
                                canAccess = !claimedPlayers.contains(player.getName());
                            } else if (receiver.contains(";")) {
                                canAccess = Arrays.asList(receiver.split(";")).contains(player.getName());
                            } else {
                                canAccess = receiver.equals(player.getName());
                            }
                        }

                        if (canAccess) {
                            openMailView(player, mailId);
                        } else {
                            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.mail-no-access")));
                        }
                        return true;
                    }
                } else {
                    Player target = Bukkit.getPlayer(args[0]);
                    if (target == null) {
                        sender.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.invalid-receiver")));
                        return true;
                    }

                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        String permission = config.getString("settings.permissions.open-others", "securemailbox.open.others");
                        if (!player.hasPermission(permission)) {
                            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.no-permission")));
                            return true;
                        }
                    }

                    openMainGUI(target);
                    sender.sendMessage(colorize(config.getString("messages.prefix") + "&aOpened mailbox for " + target.getName()));
                    return true;
                }
            }

            if (!(sender instanceof Player)) {
                sender.sendMessage(colorize(config.getString("messages.prefix") + "&cUsage: /smb <player>"));
                return true;
            }

            Player player = (Player) sender;
            openMainGUI(player);
            return true;
        });

        getCommand("smbreload").setExecutor((sender, cmd, label, args) -> {
            if (!sender.hasPermission(config.getString("settings.permissions.reload"))) {
                sender.sendMessage(colorize(config.getString("messages.no-permission")));
                return true;
            }
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
        long currentTime = System.currentTimeMillis();

        if (!database.contains("mails")) return;

        ConfigurationSection mailsSection = database.getConfigurationSection("mails");
        for (String mailId : mailsSection.getKeys(false)) {
            String path = "mails." + mailId + ".";

            // Check scheduled mails
            Long scheduleDate = database.getLong(path + "schedule-date", 0);
            if (scheduleDate > 0 && scheduleDate <= currentTime) {
                database.set(path + "schedule-date", null);
                database.set(path + "active", true);

                String receiver = database.getString(path + "receiver");
                String sender = database.getString(path + "sender");

                if (receiver.equals("all")) {
                    Bukkit.getOnlinePlayers().forEach(p -> sendMailNotification(p, mailId, sender));
                } else if (receiver.contains(";")) {
                    Arrays.stream(receiver.split(";"))
                            .map(Bukkit::getPlayer)
                            .filter(Objects::nonNull)
                            .forEach(p -> sendMailNotification(p, mailId, sender));
                } else {
                    Player receiverPlayer = Bukkit.getPlayer(receiver);
                    if (receiverPlayer != null) {
                        sendMailNotification(receiverPlayer, mailId, sender);
                    }
                }
            }

            // Check expired mails
            Long expireDate = database.getLong(path + "expire-date", 0);
            if (expireDate > 0 && expireDate <= currentTime) {
                database.set("mails." + mailId, null);
            }
        }

        saveDatabase();
    }


    private void setupDatabase() {
        databaseFile = new File(getDataFolder(), "database.yml");
        if (!databaseFile.exists()) {
            saveResource("database.yml", false);
        }
        database = YamlConfiguration.loadConfiguration(databaseFile);
    }

    private void saveDatabase() {
        foliaLib.getScheduler().runAsync((Task) -> {
            try {
                database.save(databaseFile);
            } catch (IOException e) {
                getLogger().severe("Could not save database: " + e.getMessage());
            }
        });
    }

    private void startCleanupTask() {
        foliaLib.getScheduler().runTimer(() -> {
            cleanupExpiredMails();
        }, 20L * 3600, 20L * 3600); // Every hour
    }

    private void cleanupExpiredMails() {
        long currentTime = System.currentTimeMillis();
        Set<String> mailsToRemove = new HashSet<>();

        if (database.contains("mails")) {
            for (String mailId : database.getConfigurationSection("mails").getKeys(false)) {
                String dbPath = "mails." + mailId + ".";
                long expireDate = database.getLong(dbPath + "expire-date");

                if (currentTime > expireDate) {
                    mailsToRemove.add(mailId);
                }
            }
        }

        for (String mailId : mailsToRemove) {
            database.set("mails." + mailId, null);
        }

        if (!mailsToRemove.isEmpty()) {
            saveDatabase();
        }
    }

    private void openMainGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.open"))) {
            player.sendMessage(colorize(config.getString("messages.no-permission")));
            return;
        }
        int size = config.getInt("gui.main.size");
        Inventory inv = Bukkit.createInventory(null, size, colorize(config.getString("gui.main.title")));
        addDecorations(inv, "gui.main");

        // Add create mail book
        ItemStack createBook = new ItemStack(Material.valueOf(config.getString("gui.main.items.create-mail.material")));
        ItemMeta bookMeta = createBook.getItemMeta();
        bookMeta.setDisplayName(colorize(config.getString("gui.main.items.create-mail.name")));
        bookMeta.setLore(config.getStringList("gui.main.items.create-mail.lore").stream()
                .map(this::colorize)
                .collect(Collectors.toList()));
        createBook.setItemMeta(bookMeta);
        inv.setItem(config.getInt("gui.main.items.create-mail.slot"), createBook);

        loadPlayerMails(player, inv);
        player.openInventory(inv);
    }
    private void loadPlayerMails(Player player, Inventory inv) {
        if (!database.contains("mails")) return;

        List<Integer> mailSlots = config.getIntegerList("gui.main.items.mail-display.slots");
        int currentSlot = 0;

        for (String mailId : database.getConfigurationSection("mails").getKeys(false)) {
            String dbPath = "mails." + mailId + ".";
            String receiver = database.getString(dbPath + "receiver");
            List<String> claimedPlayers = database.getStringList(dbPath + "claimed-players");

            boolean shouldDisplay = false;
            boolean isActive = database.getBoolean(dbPath + "active", true);

            if (isActive) {
                if (receiver.equals("all")) {
                    shouldDisplay = !claimedPlayers.contains(player.getName());
                } else if (receiver.contains(";")) {
                    shouldDisplay = Arrays.asList(receiver.split(";")).contains(player.getName());
                } else {
                    shouldDisplay = receiver.equals(player.getName());
                }
            }

            if (shouldDisplay) {
                ItemStack mailItem = createMailItem(mailId);
                if (currentSlot < mailSlots.size()) {
                    inv.setItem(mailSlots.get(currentSlot), mailItem);
                    currentSlot++;
                }
            }
        }
    }

    private ItemStack createMailItem(String mailId) {
        boolean isAdminMail = database.getBoolean("mails." + mailId + ".is-admin-mail");
        String itemPath = isAdminMail ? "gui.main.items.admin-mail-display" : "gui.main.items.mail-display";

        ItemStack mail = new ItemStack(Material.valueOf(config.getString(itemPath + ".material")));
        ItemMeta meta = mail.getItemMeta();

        String sender = database.getString("mails." + mailId + ".sender");
        String message = database.getString("mails." + mailId + ".message");
        long expireDate = database.getLong("mails." + mailId + ".expire-date");

        meta.setDisplayName(colorize(config.getString(itemPath + ".name").replace("%sender%", sender)));

        List<String> lore = new ArrayList<>(config.getStringList(itemPath + ".lore"));
        lore = lore.stream()
                .map(line -> line.replace("%expire_date%", new Date(expireDate).toString()))
                .map(this::colorize)
                .collect(Collectors.toList());

        lore.addAll(Arrays.stream(message.replace("\\n", "\n").split("\n"))
                .map(line -> colorize(config.getString(itemPath + ".message-prefix") + line))
                .collect(Collectors.toList()));

        meta.setLore(lore);
        meta.getPersistentDataContainer().set(
                new NamespacedKey(this, "mailId"),
                PersistentDataType.STRING,
                mailId
        );

        mail.setItemMeta(meta);
        return mail;
    }
    private void openCreateMailGUI(Player player) {
        if (!player.hasPermission(config.getString("settings.permissions.compose"))) {
            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.no-permission")));
            return;
        }
        int size = config.getInt("gui.create-mail.size");
        Inventory inv = Bukkit.createInventory(null, size, colorize(config.getString("gui.create-mail.title")));
        MailCreationSession session = mailSessions.getOrDefault(player.getUniqueId(), new MailCreationSession());
        mailSessions.put(player.getUniqueId(), session);
        addDecorations(inv, "gui.create-mail");

        // Receiver head
        ItemStack head = new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.receiver-head.material")));
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        headMeta.setDisplayName(colorize(config.getString("gui.create-mail.items.receiver-head.name")));
        List<String> headLore;
        if (player.hasPermission(config.getString("settings.admin-permission"))) {
            headLore = config.getStringList("gui.create-mail.items.receiver-head.adminlore");
        } else {
            headLore = config.getStringList("gui.create-mail.items.receiver-head.lore");
        }

        headLore = headLore.stream()
                .map(this::colorize)
                .collect(Collectors.toList());
        if (session.getReceiver() != null) {
            headLore.add(colorize(config.getString("gui.create-mail.items.receiver-head.current-receiver-format")
                    .replace("%receiver%", session.getReceiver())));
        }
        headMeta.setLore(headLore);
        head.setItemMeta(headMeta);
        inv.setItem(config.getInt("gui.create-mail.items.receiver-head.slot"), head);

        // Message paper
        ItemStack paper = new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.message-paper.material")));
        ItemMeta paperMeta = paper.getItemMeta();
        paperMeta.setDisplayName(colorize(config.getString("gui.create-mail.items.message-paper.name")));
        List<String> paperLore = config.getStringList("gui.create-mail.items.message-paper.lore").stream()
                .map(this::colorize)
                .collect(Collectors.toList());
        if (session.getMessage() != null) {
            paperLore.add(colorize(config.getString("gui.create-mail.items.message-paper.current-message-prefix")));
            paperLore.addAll(Arrays.stream(session.getMessage().split("\n"))
                    .map(line -> colorize(config.getString("gui.create-mail.items.message-paper.message-line-format") + line))
                    .collect(Collectors.toList()));
        }
        paperMeta.setLore(paperLore);
        paper.setItemMeta(paperMeta);
        inv.setItem(config.getInt("gui.create-mail.items.message-paper.slot"), paper);

        // Items chest
        ItemStack chest = new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.items-chest.material")));
        ItemMeta chestMeta = chest.getItemMeta();
        chestMeta.setDisplayName(colorize(config.getString("gui.create-mail.items.items-chest.name")));
        List<String> chestLore = config.getStringList("gui.create-mail.items.items-chest.lore").stream()
                .map(this::colorize)
                .collect(Collectors.toList());
        if (!session.getItems().isEmpty()) {
            chestLore.add(colorize(config.getString("gui.create-mail.items.items-chest.items-count-format").replace("%amount%", String.valueOf(session.getItems().size()))));
        }
        chestMeta.setLore(chestLore);
        chest.setItemMeta(chestMeta);
        inv.setItem(config.getInt("gui.create-mail.items.items-chest.slot"), chest);

        // Command block
        if (player.hasPermission(config.getString("settings.admin-permission"))) {
            ItemStack commandBlock = session.getDisplayCommandBlock() != null ?
                    session.getDisplayCommandBlock() :
                    session.getCommandBlock() != null ?
                            session.getCommandBlock().clone() :
                            new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.command-block.material")));
            ItemMeta commandMeta = commandBlock.getItemMeta();
            commandMeta.setDisplayName(colorize(config.getString("gui.create-mail.items.command-block.name")));
            List<String> commandLore = config.getStringList("gui.create-mail.items.command-block.lore").stream()
                    .map(this::colorize)
                    .collect(Collectors.toList());
            if (!session.getCommands().isEmpty()) {
                commandLore.add(colorize(config.getString("gui.create-mail.items.command-block.commands-prefix")));
                for (String cmd : session.getCommands()) {
                    commandLore.add(colorize(config.getString("gui.create-mail.items.command-block.command-format")
                            .replace("%command%", cmd)));
                }
            }
            commandMeta.setLore(commandLore);
            commandBlock.setItemMeta(commandMeta);
            inv.setItem(config.getInt("gui.create-mail.items.command-block.slot"), commandBlock);
        }

        // Add schedule clock for admins
        if (player.hasPermission(config.getString("settings.admin-permission"))) {
            inv.setItem(config.getInt("gui.create-mail.items.schedule-clock.slot"),
                    updateClockLore(session));
        }



        // Send button
        ItemStack send = new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.send-button.material")));
        ItemMeta sendMeta = send.getItemMeta();
        sendMeta.setDisplayName(colorize(config.getString("gui.create-mail.items.send-button.name")));
        List<String> sendLore = config.getStringList("gui.create-mail.items.send-button.lore").stream()
                .map(this::colorize)
                .collect(Collectors.toList());
        if (!session.isComplete()) {
            sendLore.add(colorize(config.getString("messages.incomplete-mail")));
        }
        sendMeta.setLore(sendLore);
        send.setItemMeta(sendMeta);
        inv.setItem(config.getInt("gui.create-mail.items.send-button.slot"), send);

        player.openInventory(inv);
    }

    private ItemStack updateClockLore(MailCreationSession session) {
        ItemStack clock = new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.schedule-clock.material")));
        ItemMeta meta = clock.getItemMeta();
        meta.setDisplayName(colorize(config.getString("gui.create-mail.items.schedule-clock.name")));

        List<String> lore = new ArrayList<>(config.getStringList("gui.create-mail.items.schedule-clock.lore"));

        // Replace placeholders with actual times
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i);
            if (line.contains("%schedule_time%")) {
                line = line.replace("%schedule_time%",
                        session.getScheduleDate() != null ? new Date(session.getScheduleDate()).toString() : "ɴᴏᴛ sᴇᴛ");
            }
            if (line.contains("%expire_time%")) {
                line = line.replace("%expire_time%",
                        session.getExpireDate() != null ? new Date(session.getExpireDate()).toString() : "ɴᴏᴛ sᴇᴛ");
            }
            lore.set(i, colorize(line));
        }

        meta.setLore(lore);
        clock.setItemMeta(meta);
        return clock;
    }

    private ItemStack createDefaultCommandBlock() {
        ItemStack commandBlock = new ItemStack(Material.valueOf(config.getString("gui.create-mail.items.command-block.material")));
        ItemMeta meta = commandBlock.getItemMeta();
        meta.setDisplayName(colorize(config.getString("gui.create-mail.items.command-block.name")));
        meta.setLore(config.getStringList("gui.create-mail.items.command-block.lore").stream()
                .map(this::colorize)
                .collect(Collectors.toList()));
        commandBlock.setItemMeta(meta);
        return commandBlock;
    }

    private void openItemsGUI(Player player) {
        inMailCreation.put(player.getUniqueId(), true);

        int size = config.getInt("gui.items.size");
        Inventory inv = Bukkit.createInventory(null, size, colorize(config.getString("gui.items.title")));

        MailCreationSession session = mailSessions.get(player.getUniqueId());
        if (session != null && !session.getItems().isEmpty()) {
            session.getItems().forEach(item -> inv.addItem(item.clone()));
        }

        ItemStack saveButton = new ItemStack(Material.valueOf(config.getString("gui.items.items.save-button.material")));
        ItemMeta saveMeta = saveButton.getItemMeta();
        saveMeta.setDisplayName(colorize(config.getString("gui.items.items.save-button.name")));
        saveMeta.setLore(config.getStringList("gui.items.items.save-button.lore").stream()
                .map(this::colorize)
                .collect(Collectors.toList()));
        saveButton.setItemMeta(saveMeta);
        inv.setItem(config.getInt("gui.items.items.save-button.slot"), saveButton);

        itemsInventories.put(player.getUniqueId(), inv);
        player.openInventory(inv);
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
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        ItemStack clicked = event.getCurrentItem();
        if (title.equals(colorize(config.getString("gui.main.title"))) && event.getClickedInventory() == player.getInventory()) {
            event.setCancelled(true);
            return;
        }
        if (clicked == null || event.getClickedInventory() == event.getWhoClicked().getInventory()) return;

        if (title.equals(colorize(config.getString("gui.main.title")))) {
            event.setCancelled(true);
            if (event.getSlot() == config.getInt("gui.main.items.create-mail.slot")) {
                openCreateMailGUI(player);
            } else if (config.getIntegerList("gui.main.items.mail-display.slots").contains(event.getSlot())) {
                String mailId = clicked.getItemMeta().getPersistentDataContainer()
                        .get(new NamespacedKey(this, "mailId"), PersistentDataType.STRING);
                if (mailId != null) {
                    openMailView(player, mailId);
                }
            }
        } else if (title.equals(colorize(config.getString("gui.create-mail.title")))) {
            if (player.hasPermission(config.getString("settings.admin-permission")) && event.getClickedInventory() == player.getInventory()) return;
            event.setCancelled(true);
            handleCreateMailGUIClick(event);
        } else if (title.equals(colorize(config.getString("gui.items.title")))) {
            handleItemsGUIClick(event);
        } else if (title.equals(colorize(config.getString("gui.mail-view.title")))) {
            event.setCancelled(true);
            if (event.getSlot() == config.getInt("gui.mail-view.items.claim-button.slot")) {
                String mailId = clicked.getItemMeta().getPersistentDataContainer()
                        .get(new NamespacedKey(this, "mailId"), PersistentDataType.STRING);
                if (mailId != null) {
                    handleMailClaim(player, mailId);
                }
            }
        }
    }


    private void openMailView(Player player, String mailId) {
        int size = config.getInt("gui.mail-view.size");
        Inventory inv = Bukkit.createInventory(null, size, colorize(config.getString("gui.mail-view.title")));
        addDecorations(inv, "gui.mail-view");

        String dbPath = "mails." + mailId + ".";
        String sender = database.getString(dbPath + "sender");
        String message = database.getString(dbPath + "message").replace("\\n", "\n");

        // Sender Head
        ItemStack head = new ItemStack(Material.valueOf(config.getString("gui.mail-view.items.sender-head.material")));
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        headMeta.setDisplayName(colorize(config.getString("gui.mail-view.items.sender-head.name").replace("%sender%", sender)));
        headMeta.setOwningPlayer(Bukkit.getOfflinePlayer(sender));
        head.setItemMeta(headMeta);
        inv.setItem(config.getInt("gui.mail-view.items.sender-head.slot"), head);

        // Message Display
        ItemStack messageItem = new ItemStack(Material.valueOf(config.getString("gui.mail-view.items.message.material")));
        ItemMeta messageMeta = messageItem.getItemMeta();
        messageMeta.setDisplayName(colorize(config.getString("gui.mail-view.items.message.name")));
        List<String> messageLore = Arrays.stream(message.split("\n"))
                .map(line -> colorize("&f" + line))
                .collect(Collectors.toList());
        messageMeta.setLore(messageLore);
        messageItem.setItemMeta(messageMeta);
        inv.setItem(config.getInt("gui.mail-view.items.message.slot"), messageItem);

        // Items Display
        List<String> serializedItems = database.getStringList(dbPath + "items");
        List<ItemStack> items = serializedItems.stream()
                .map(this::deserializeItem)
                .collect(Collectors.toList());
        List<Integer> itemSlots = config.getIntegerList("gui.mail-view.items.items-display.slots");
        for (int i = 0; i < Math.min(items.size(), itemSlots.size()); i++) {
            inv.setItem(itemSlots.get(i), items.get(i));
        }

        // Add command block display if commands exist
        List<String> commands = database.getStringList(dbPath + "commands");
        if (!commands.isEmpty()) {
            String serializedCommandBlock = database.getString(dbPath + "command-block");
            ItemStack commandBlock = serializedCommandBlock != null ?
                    deserializeItem(serializedCommandBlock) :
                    new ItemStack(Material.COMMAND_BLOCK);

            ItemMeta commandMeta = commandBlock.getItemMeta();
            List<String> commandLore = new ArrayList<>();
            commandMeta.setLore(commandLore);
            commandBlock.setItemMeta(commandMeta);

            inv.setItem(config.getInt("gui.mail-view.items.command-block.slot"), commandBlock);

        }

        // Claim Button if there are items
            ItemStack claimButton = new ItemStack(Material.valueOf(config.getString("gui.mail-view.items.claim-button.material")));
            ItemMeta claimMeta = claimButton.getItemMeta();
            claimMeta.setDisplayName(colorize(config.getString("gui.mail-view.items.claim-button.name")));
            claimMeta.setLore(config.getStringList("gui.mail-view.items.claim-button.lore").stream()
                    .map(this::colorize)
                    .collect(Collectors.toList()));
            claimMeta.getPersistentDataContainer().set(
                    new NamespacedKey(this, "mailId"),
                    PersistentDataType.STRING,
                    mailId
            );
            claimButton.setItemMeta(claimMeta);
            inv.setItem(config.getInt("gui.mail-view.items.claim-button.slot"), claimButton);


        player.openInventory(inv);
    }

    private void handleMailClaim(Player player, String mailId) {
        String dbPath = "mails." + mailId + ".";
        String receiver = database.getString(dbPath + "receiver");

        // Handle items claiming
        List<String> serializedItems = database.getStringList(dbPath + "items");
        List<ItemStack> items = serializedItems.stream()
                .map(this::deserializeItem)
                .collect(Collectors.toList());

        if (!items.isEmpty()) {
            // Check inventory space
            int emptySlots = (int) Arrays.stream(player.getInventory().getStorageContents())
                    .filter(item -> item == null || item.getType() == Material.AIR)
                    .count();

            if (emptySlots < items.size()) {
                player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.inventory-space-needed").replace("%amount%", String.valueOf(items.size()))));
                return;
            }

            items.forEach(item -> player.getInventory().addItem(item));
        }
        List<String> commands = database.getStringList(dbPath + "commands");
        if (!commands.isEmpty()) {
            foliaLib.getScheduler().runNextTick((Task) -> {
                for (String command : commands) {
                    String processedCommand = command.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                }
            });
        }

        // Handle mail removal/update
        if (receiver.equals("all")) {
            List<String> claimedPlayers = database.getStringList(dbPath + "claimed-players");
            claimedPlayers.add(player.getName());
            database.set(dbPath + "claimed-players", claimedPlayers);
        } else if (receiver.contains(";")) {
            List<String> receivers = new ArrayList<>(Arrays.asList(receiver.split(";")));
            receivers.remove(player.getName());

            if (receivers.isEmpty()) {
                database.set("mails." + mailId, null);
            } else {
                database.set(dbPath + "receiver", String.join(";", receivers));
            }
        } else {
            database.set("mails." + mailId, null);
        }

        saveDatabase();
        player.closeInventory();
        openMainGUI(player);
        player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.items-claimed")));
    }


    private void handleCreateMailGUIClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        UUID playerId = player.getUniqueId();

        if (clicked == null) return;

        MailCreationSession session = mailSessions.get(player.getUniqueId());
        if (session == null) return;


        int slot = event.getSlot();

        if (slot == config.getInt("gui.create-mail.items.receiver-head.slot")) {
            inMailCreation.put(playerId, true);
            player.closeInventory();
            awaitingInput.put(player.getUniqueId(), "receiver");
            showInputTitle(player, "receiver");
            player.sendMessage(colorize(config.getString("messages.enter-receiver")));
        } else if (slot == config.getInt("gui.create-mail.items.message-paper.slot")) {
            inMailCreation.put(playerId, true);
            player.closeInventory();
            awaitingInput.put(player.getUniqueId(), "message");
            showInputTitle(player, "message");
            player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.enter-message")));
        } else if (slot == config.getInt("gui.create-mail.items.items-chest.slot")) {
            if (!player.hasPermission(config.getString("settings.permissions.add-items"))) {
                player.sendMessage(colorize(config.getString("messages.no-permission")));
                return;
            }
            openItemsGUI(player);

        } else if (slot == config.getInt("gui.create-mail.items.send-button.slot")) {
            handleMailSend(player);
        } else if (slot == config.getInt("gui.create-mail.items.command-block.slot")) {
            if (event.getClick().isRightClick()) {
                List<String> commands = session.getCommands();
                if (!commands.isEmpty()) {
                    commands.remove(commands.size() - 1);
                    session.setCommands(commands);

                    ItemStack commandBlock = session.getCommandBlock();
                    ItemStack displayBlock = commandBlock.clone();
                    ItemMeta meta = displayBlock.getItemMeta();
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add(colorize(config.getString("gui.create-mail.items.command-block.commands-prefix")));

                    for (String cmd : commands) {
                        lore.add(colorize(config.getString("gui.create-mail.items.command-block.command-format").replace("%command%", cmd)));
                    }
                    meta.setLore(lore);
                    displayBlock.setItemMeta(meta);
                    session.setDisplayCommandBlock(displayBlock);

                    openCreateMailGUI(player);
                }
            } else {
                handleCommandBlockItem(player, event, session);
            }
        } else if (slot == config.getInt("gui.create-mail.items.schedule-clock.slot")) {
            if (player.hasPermission(config.getString("settings.admin-permission"))) {
                player.closeInventory();
                if (event.getClick().isRightClick()) {
                    awaitingInput.put(player.getUniqueId(), "expire-date");
                    showInputTitle(player, "expire");
                    player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.enter-expire-date")));
                } else {
                    awaitingInput.put(player.getUniqueId(), "schedule-date");
                    showInputTitle(player, "schedule");
                    player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.enter-schedule-date")));
                }
            }

        }
    }



    private void handleItemsGUIClick(InventoryClickEvent event) {
        if (event.getSlot() == config.getInt("gui.items.items.save-button.slot")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            MailCreationSession session = mailSessions.get(player.getUniqueId());

            if (session != null) {
                List<ItemStack> items = new ArrayList<>();
                Inventory inv = event.getInventory();

                for (int i = 0; i < inv.getSize() - 1; i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && i != config.getInt("gui.items.items.save-button.slot")) {
                        items.add(item.clone());
                    }
                }

                player.closeInventory();
            }
        }
    }

    private void handleCommandBlockItem(Player player, InventoryClickEvent event, MailCreationSession session) {
        if (!player.hasPermission(config.getString("settings.admin-permission"))) return;

        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            List<String> commands = session.getCommands();
            session.setCommandBlock(cursor.clone());
            ItemStack displayBlock = cursor.clone();
            ItemMeta meta = displayBlock.getItemMeta();
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add(colorize("&eCommands:"));
            for (String cmd : commands) {
                lore.add(colorize("&7- /" + cmd));
            }
            meta.setLore(lore);
            displayBlock.setItemMeta(meta);
            session.setDisplayCommandBlock(displayBlock);
            event.getView().setCursor(null);

            foliaLib.getScheduler().runNextTick((Task) -> {
                openCreateMailGUI(player);
            });
            return;
        }

        player.closeInventory();
        awaitingInput.put(player.getUniqueId(), "command");
        showInputTitle(player, "command");
        player.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.enter-command")));
    }



    private void executeCommandBlock(Player player, ItemStack item) {
        String mailId = item.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(this, "mailId"), PersistentDataType.STRING);
        if (mailId == null) return;

        List<String> commands = database.getStringList("mails." + mailId + ".commands");
        for (String command : commands) {
            String processedCommand = command.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
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
        }
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();
        UUID playerId = player.getUniqueId();

        if (title.equals(colorize(config.getString("gui.items.title")))) {
            MailCreationSession session = mailSessions.get(playerId);
            if (session != null) {
                List<ItemStack> items = new ArrayList<>();
                Material saveButtonMaterial = Material.valueOf(config.getString("gui.items.items.save-button.material"));

                for (int i = 0; i < event.getInventory().getSize() - 1; i++) {
                    ItemStack item = event.getInventory().getItem(i);
                    if (item != null && item.getType() != saveButtonMaterial) {
                        items.add(item.clone());
                    }
                }
                session.setItems(items);

                foliaLib.getScheduler().runNextTick((Task) -> {
                    openCreateMailGUI(player);
                });
            }
        } else if (title.equals(colorize(config.getString("gui.create-mail.title")))) {
            MailCreationSession session = mailSessions.get(playerId);
            if (session != null && !player.hasPermission(config.getString("settings.admin-permission"))) {
                if (!inMailCreation.getOrDefault(playerId, false)) {
                    List<ItemStack> items = session.getItems();
                    for (ItemStack item : items) {
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                        leftover.values().forEach(leftItem ->
                                player.getWorld().dropItem(player.getLocation(), leftItem));
                    }
                    session.setItems(new ArrayList<>());
                }
            }
        }

        // Only remove the tracking if they're not in chat input mode
        if (!awaitingInput.containsKey(playerId)) {
            inMailCreation.remove(playerId);
        }
    }
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String inputType = awaitingInput.remove(player.getUniqueId());

        if (inputType != null) {
            event.setCancelled(true);
            MailCreationSession session = mailSessions.get(player.getUniqueId());

            if (session != null) {
                boolean success = true;
                String customSubtitle = null;

                switch (inputType) {
                    case "receiver":
                        success = handleReceiverInput(player, event.getMessage(), session);
                        customSubtitle = success ?
                                config.getString("messages.current-receiver").replace("%receiver%", event.getMessage()) :
                                config.getString("messages.invalid-receiver");
                        break;

                    case "message":
                        session.setMessage(event.getMessage().replace("\\n", "\n"));
                        customSubtitle = config.getString("messages.current-message");
                        break;

                    case "command":
                        handleCommandInput(player, event.getMessage(), session);
                        customSubtitle = config.getString("messages.command-format-display")
                                .replace("%command%", event.getMessage());
                        break;

                    case "schedule-date":
                        try {
                            handleDateInput(player, event.getMessage(), session, true);
                            customSubtitle = config.getString("messages.schedule-set")
                                    .replace("%date%", new Date(session.getScheduleDate()).toString());
                        } catch (Exception e) {
                            success = false;
                            customSubtitle = config.getString("messages.invalid-date-format");
                        }
                        break;

                    case "expire-date":
                        try {
                            handleDateInput(player, event.getMessage(), session, false);
                            customSubtitle = config.getString("messages.expire-set")
                                    .replace("%date%", new Date(session.getExpireDate()).toString());
                        } catch (Exception e) {
                            success = false;
                            customSubtitle = config.getString("messages.invalid-date-format");
                        }
                        break;
                }

                showResponseTitle(player, success, customSubtitle);

                foliaLib.getScheduler().runNextTick((Task) -> {
                    openCreateMailGUI(player);
                });
            }
        }
    }


    private void handleDateInput(Player player, String input, MailCreationSession session, boolean isSchedule) {
        try {
            String[] parts = input.split(":");
            if (parts.length != 5) {
                throw new IllegalArgumentException();
            }

            Calendar calendar = Calendar.getInstance();
            calendar.set(
                    Integer.parseInt(parts[0]), // year
                    Integer.parseInt(parts[1]) - 1, // month (0-based)
                    Integer.parseInt(parts[2]), // day
                    Integer.parseInt(parts[3]), // hour
                    Integer.parseInt(parts[4]),  // minute
                    0
            );

            long timestamp = calendar.getTimeInMillis();

            if (isSchedule) {
                session.setScheduleDate(timestamp);
                player.sendMessage(colorize(config.getString("messages.prefix") +
                        config.getString("messages.schedule-set").replace("%date%", calendar.getTime().toString())));
            } else {
                session.setExpireDate(timestamp);
                player.sendMessage(colorize(config.getString("messages.prefix") +
                        config.getString("messages.expire-set").replace("%date%", calendar.getTime().toString())));
            }
        } catch (Exception e) {
            player.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.invalid-date-format")));
        }
    }


    private void handleCommandInput(Player player, String command, MailCreationSession session) {
        List<String> commands = session.getCommands();
        commands.add(command);
        session.setCommands(commands);

        ItemStack commandBlock = session.getCommandBlock();
        ItemStack displayBlock = commandBlock.clone();
        ItemMeta meta = displayBlock.getItemMeta();
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.add(colorize(config.getString("messages.command-prefix")));
        for (String cmd : commands) {
            lore.add(colorize(config.getString("messages.command-format-display").replace("%command%", cmd)));
        }
        meta.setLore(lore);
        displayBlock.setItemMeta(meta);
        session.setDisplayCommandBlock(displayBlock);
    }

    private void showInputTitle(Player player, String type) {
        String titlePath = "titles.input." + type;
        String title = colorize(config.getString(titlePath + ".title"));
        String subtitle = colorize(config.getString(titlePath + ".subtitle"));
        int fadeIn = config.getInt("titles.input.fadein");
        int stay = config.getInt("titles.input.stay");
        int fadeOut = config.getInt("titles.input.fadeout");

        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    private void showResponseTitle(Player player, boolean success, String customSubtitle) {
        String type = success ? "success" : "error";
        String titlePath = "titles.response." + type;
        String title = colorize(config.getString(titlePath + ".title"));
        String subtitle = customSubtitle != null ?
                colorize(customSubtitle) :
                colorize(config.getString(titlePath + ".subtitle"));
        int fadeIn = config.getInt("titles.response.fadein");
        int stay = config.getInt("titles.response.stay");
        int fadeOut = config.getInt("titles.response.fadeout");

        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }


    private boolean handleReceiverInput(Player sender, String input, MailCreationSession session) {
        if (input.equals(sender.getName())) {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.self-mail")));
            return false;
        }

        if (input.equals("all") || input.equals("allonline") || input.contains(";")) {
            if (!sender.hasPermission(config.getString("settings.admin-permission"))) {
                sender.sendMessage(colorize(config.getString("messages.prefix") +
                        config.getString("messages.no-permission")));
                return false;
            }
        }

        if (sender.hasPermission(config.getString("settings.admin-permission"))) {
            if (input.equals("all") || input.equals("allonline")) {
                session.setReceiver(input);
                return true;
            }

            if (input.contains(";")) {
                session.setReceiver(input);
                return true;
            }
        }

        int currentMails = database.getInt("player-mail-counts." + input, 0);
        if (currentMails >= config.getInt("settings.max-mails-per-player")) {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.mailbox-full")));
            return false;
        }

        session.setReceiver(input);
        foliaLib.getScheduler().runNextTick((Task) -> {
            openCreateMailGUI(sender);
        });
        return true;
    }


    private void handleMailSend(Player sender) {
        MailCreationSession session = mailSessions.get(sender.getUniqueId());
        if (session == null || !session.isComplete()) {
            sender.sendMessage(colorize(config.getString("messages.prefix") + config.getString("messages.incomplete-mail")));
            return;
        }
        inMailCreation.remove(sender.getUniqueId());

        String mailId = UUID.randomUUID().toString();
        boolean isAdminMail = sender.hasPermission(config.getString("settings.admin-permission"));
        long expireTime = System.currentTimeMillis() +
                (config.getInt("settings.admin-mail-expire-days") * 86400000L);

        // Convert allonline to player list
        String receiver = session.getReceiver();
        if (receiver.equals("allonline")) {
            receiver = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(";"));
        }
        long defaultExpireTime = System.currentTimeMillis() +
                (config.getInt("settings.admin-mail-expire-days") * 86400000L);

        // Save mail to database
        String dbPath = "mails." + mailId + ".";
        database.set(dbPath + "sender", sender.getName());
        database.set(dbPath + "receiver", receiver);
        database.set(dbPath + "message", session.getMessage());
        database.set(dbPath + "sent-date", System.currentTimeMillis());
        database.set(dbPath + "schedule-date", session.getScheduleDate());
        database.set(dbPath + "expire-date", session.getExpireDate());
        database.set(dbPath + "active", session.getScheduleDate() == null);
        database.set(dbPath + "is-admin-mail", isAdminMail);
        database.set(dbPath + "claimed-players", new ArrayList<String>());
        database.set(dbPath + "expire-date",
                session.getExpireDate() != null ? session.getExpireDate() : defaultExpireTime);
        if (session.getCommandBlock() != null) {
            database.set(dbPath + "command-block", serializeItem(session.getCommandBlock()));
            database.set(dbPath + "commands", session.getCommands());
        }

        if (session.getScheduleDate() == null || System.currentTimeMillis() >= session.getScheduleDate()) {
            if (receiver.equals("all")) {
                Bukkit.getOnlinePlayers().forEach(p -> sendMailNotification(p, mailId, sender.getName()));
            } else if (receiver.contains(";")) {
                Arrays.stream(receiver.split(";"))
                        .map(Bukkit::getPlayer)
                        .filter(Objects::nonNull)
                        .forEach(p -> sendMailNotification(p, mailId, sender.getName()));
            } else {
                Player receiverPlayer = Bukkit.getPlayer(receiver);
                if (receiverPlayer != null) {
                    sendMailNotification(receiverPlayer, mailId, sender.getName());
                }
            }
        }
        // Save items
        List<String> serializedItems = new ArrayList<>();
        for (ItemStack item : session.getItems()) {
            serializedItems.add(serializeItem(item));
        }
        database.set(dbPath + "items", serializedItems);

        saveDatabase();
        mailSessions.remove(sender.getUniqueId());
        sender.closeInventory();
        if (session.getScheduleDate() != null) {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.schedule-set").replace("%date%", new Date(session.getScheduleDate()).toString())));
        } else {
            sender.sendMessage(colorize(config.getString("messages.prefix") +
                    config.getString("messages.mail-sent")));
        }    }

    private void incrementPlayerMailCount(String playerName) {
        int current = database.getInt("player-mail-counts." + playerName, 0);
        database.set("player-mail-counts." + playerName, current + 1);
    }

    private String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            getLogger().severe("Error serializing item: " + e.getMessage());
            return "";
        }
    }

    private ItemStack deserializeItem(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            getLogger().severe("Error deserializing item: " + e.getMessage());
            return new ItemStack(Material.AIR);
        }
    }



    private String colorize(String text) {
        return text.replace("&", "§");
    }

    private void addDecorations(Inventory inv, String guiPath) {
        if (!config.contains(guiPath + ".decoration")) return;

        ConfigurationSection decorSection = config.getConfigurationSection(guiPath + ".decoration");
        for (String decorKey : decorSection.getKeys(false)) {
            String path = guiPath + ".decoration." + decorKey;
            Material material = Material.valueOf(config.getString(path + ".material"));
            String name = config.getString(path + ".name");
            List<Integer> slots = config.getIntegerList(path + ".slots");

            ItemStack decorItem = new ItemStack(material);
            ItemMeta meta = decorItem.getItemMeta();
            meta.setDisplayName(colorize(name));
            decorItem.setItemMeta(meta);

            for (int slot : slots) {
                inv.setItem(slot, decorItem.clone());
            }
        }
    }

    private void sendMailNotification(Player receiver, String mailId, String sender) {
        // Chat notification
        if (config.getBoolean("settings.notification.chat-enabled")) {
            String chatMessage = config.getString("messages.new-mail-clickable")
                    .replace("%sender%", sender);

            TextComponent message = new TextComponent(colorize(config.getString("messages.prefix") + chatMessage));
            message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/smb view " + mailId));
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


    private class MailCreationSession {
        private String receiver;
        private String message;
        private List<ItemStack> items;
        private ItemStack commandBlock;
        private List<String> commands;
        private ItemStack displayCommandBlock;
        private Long scheduleDate;
        private Long expireDate;

        public MailCreationSession() {
            this.items = new ArrayList<>();
            this.commands = new ArrayList<>();
            this.commandBlock = createDefaultCommandBlock();
        }

        public void setReceiver(String receiver) {
            this.receiver = receiver;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public void setItems(List<ItemStack> items) {
            this.items = items;
        }

        public String getReceiver() {
            return receiver;
        }

        public String getMessage() {
            return message;
        }

        public List<ItemStack> getItems() {
            return items;
        }

        public boolean isComplete() {
            return receiver != null && message != null;
        }

        public ItemStack getDisplayCommandBlock() {
            return displayCommandBlock;
        }

        public void setDisplayCommandBlock(ItemStack displayCommandBlock) {
            this.displayCommandBlock = displayCommandBlock;
        }

        public void setCommandBlock(ItemStack commandBlock) {
            this.commandBlock = commandBlock;
        }

        public void setCommands(List<String> commands) {
            this.commands = commands;
        }

        public ItemStack getCommandBlock() {
            return commandBlock;
        }

        public List<String> getCommands() {
            return commands != null ? commands : new ArrayList<>();
        }
        public Long getScheduleDate() { return scheduleDate; }
        public void setScheduleDate(Long scheduleDate) { this.scheduleDate = scheduleDate; }
        public Long getExpireDate() { return expireDate; }
        public void setExpireDate(Long expireDate) { this.expireDate = expireDate; }

    }
}

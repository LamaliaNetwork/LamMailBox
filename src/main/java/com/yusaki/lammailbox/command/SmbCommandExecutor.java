package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.service.MailDelivery;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SmbCommandExecutor implements CommandExecutor {
    private final LamMailBox plugin;

    public SmbCommandExecutor(LamMailBox plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return handleDefault(sender);
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "send":
                return handleSend(sender, Arrays.copyOfRange(args, 1, args.length));
            case "view":
                return handleView(sender, args);
            case "as":
                return handleAs(sender, args);
            default:
                return handleOpenOther(sender, args[0]);
        }
    }

    private boolean handleSend(CommandSender sender, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (!sender.hasPermission(config.getString("settings.admin-permission"))) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.send-command-admin-only")));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    "&cUsage: /smb send <users> <message> | [commands]"));
            return true;
        }

        String receiver = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        MailCreationSession session = new MailCreationSession();
        session.setReceiver(receiver);

        List<String> commands = new ArrayList<>();
        String messageContent = message;
        if (message.contains("|")) {
            String[] parts = message.split("\\|", 2);
            messageContent = parts[0].trim();

            if (sender instanceof Player && !sender.hasPermission(config.getString("settings.admin-permission"))) {
                sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                        config.getString("messages.no-permission")));
                return true;
            }

            String[] commandParts = parts[1].trim().split(",");
            for (String commandPart : commandParts) {
                if (!commandPart.trim().isEmpty()) {
                    commands.add(commandPart.trim());
                }
            }
        }

        session.setMessage(messageContent);
        session.setCommands(commands);
        long expireTime = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000);
        session.setExpireDate(expireTime);

        try {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                MailDelivery delivery = plugin.getMailService().sendMail(player, session);
                if (delivery.shouldNotifyNow()) {
                    plugin.dispatchMailNotifications(delivery.getReceiverSpec(), delivery.getMailId(), delivery.getSenderName());
                }
                player.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                        config.getString("messages.mail-sent")));
            } else {
                MailDelivery delivery = plugin.getMailService().sendConsoleMail(sender, session);
                if (delivery.shouldNotifyNow()) {
                    plugin.dispatchMailNotifications(delivery.getReceiverSpec(), delivery.getMailId(), delivery.getSenderName());
                }
                sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + "&aMail sent successfully."));
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.incomplete-mail")));
        }
        return true;
    }

    private boolean handleView(CommandSender sender, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.colorize(config.getString("messages.console-only")));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    "&cUsage: /smb view <mailId>"));
            return true;
        }

        Player player = (Player) sender;
        String mailId = args[1];
        String dbPath = "mails." + mailId + ".";
        FileConfiguration database = plugin.getMailRepository().getBackingConfiguration();

        if (!database.contains(dbPath)) {
            player.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.mail-not-found")));
            return true;
        }

        String receiver = database.getString(dbPath + "receiver");
        List<String> claimedPlayers = database.getStringList(dbPath + "claimed-players");

        boolean canAccess = false;
        boolean isActive = database.getBoolean(dbPath + "active", true);
        if (isActive) {
            if (Objects.equals(receiver, "all")) {
                canAccess = !claimedPlayers.contains(player.getName());
            } else if (receiver != null && receiver.contains(";")) {
                canAccess = Arrays.asList(receiver.split(";"))
                        .contains(player.getName());
            } else {
                canAccess = Objects.equals(receiver, player.getName());
            }
        }

        if (canAccess) {
            plugin.openMailView(player, mailId);
        } else {
            player.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.mail-no-access")));
        }
        return true;
    }

    private boolean handleAs(CommandSender sender, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (!sender.hasPermission(config.getString("settings.permissions.view-as"))) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.no-permission")));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + "&cUsage: /smb as <player>"));
            return true;
        }

        Player targetPlayer = Bukkit.getPlayer(args[1]);
        if (targetPlayer == null) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.invalid-receiver")));
            return true;
        }

        if (sender instanceof Player) {
            Player admin = (Player) sender;
            plugin.openMailboxAsPlayer(admin, targetPlayer);
            admin.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    "&aOpened mailbox as " + targetPlayer.getName()));
        } else {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    "&cConsole cannot open GUI. Use /smb <player> instead."));
        }
        return true;
    }

    private boolean handleOpenOther(CommandSender sender, String targetName) {
        FileConfiguration config = plugin.getConfig();
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.invalid-receiver")));
            return true;
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;
        String permission = config.getString("settings.permissions.open-others", "lammailbox.open.others");
            if (!player.hasPermission(permission)) {
                player.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                        config.getString("messages.no-permission")));
                return true;
            }
        }

        plugin.openMainGUI(target);
        sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                "&aOpened mailbox for " + target.getName()));
        return true;
    }

    private boolean handleDefault(CommandSender sender) {
        FileConfiguration config = plugin.getConfig();
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + "&cUsage: /smb <player>"));
            return true;
        }

        Player player = (Player) sender;
        plugin.openMainGUI(player);
        return true;
    }
}

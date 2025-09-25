package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.repository.MailRecord;
import com.yusaki.lammailbox.service.MailDelivery;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

public class LmbCommandExecutor implements CommandExecutor {
    private final LamMailBox plugin;

    public LmbCommandExecutor(LamMailBox plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return handleDefault(sender);
        }

        String rawSubCommand = args[0];
        switch (rawSubCommand.toLowerCase(Locale.ROOT)) {
            case "send":
                return handleSend(sender, Arrays.copyOfRange(args, 1, args.length));
            case "view":
                return handleView(sender, args);
            case "as":
                return handleAs(sender, args);
            default:
                return handleOpenOther(sender, rawSubCommand);
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
                    "&cUsage: /lmb send <users> <message> | [commands] | schedule:YYYY:MM:DD:HH:mm | expire:YYYY:MM:DD:HH:mm"));
            return true;
        }

        String receiver = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        MailCreationSession session = new MailCreationSession();
        session.setReceiver(receiver);

        // Parse message with multiple sections: message | [commands] | schedule:date | expire:date
        String[] sections = message.split("\\|");
        String messageContent = sections[0].trim();
        List<String> commands = new ArrayList<>();
        Long scheduleDate = null;
        Long expireDate = null;

        // Process each section
        for (int i = 1; i < sections.length; i++) {
            String section = sections[i].trim();

            if (section.startsWith("schedule:")) {
                // Parse schedule date
                String dateStr = section.substring(9).trim();
                scheduleDate = parseDateString(dateStr);
                if (scheduleDate == null) {
                    sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                            config.getString("messages.invalid-date-format")));
                    return true;
                }
            } else if (section.startsWith("expire:")) {
                // Parse expire date
                String dateStr = section.substring(7).trim();
                expireDate = parseDateString(dateStr);
                if (expireDate == null) {
                    sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                            config.getString("messages.invalid-date-format")));
                    return true;
                }
            } else if (!section.isEmpty()) {
                // Parse commands section
                if (sender instanceof Player && !sender.hasPermission(config.getString("settings.admin-permission"))) {
                    sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                            config.getString("messages.no-permission")));
                    return true;
                }

                // Support both comma-separated and bracket format
                if (section.contains("[") && section.contains("]")) {
                    // Bracket format: [give {player} diamond] [xp add {player} 100]
                    String[] commandParts = section.split("\\]\\s*\\[");
                    for (String commandPart : commandParts) {
                        String cmd = commandPart.replaceAll("[\\[\\]]", "").trim();
                        if (!cmd.isEmpty()) {
                            commands.add(cmd);
                        }
                    }
                } else {
                    // Comma-separated format: give {player} diamond, xp add {player} 100
                    String[] commandParts = section.split(",");
                    for (String commandPart : commandParts) {
                        String cmd = commandPart.trim();
                        if (!cmd.isEmpty()) {
                            commands.add(cmd);
                        }
                    }
                }
            }
        }

        session.setMessage(messageContent);
        session.setCommands(commands);

        // Set schedule date if provided
        if (scheduleDate != null) {
            session.setScheduleDate(scheduleDate);
        }

        // Set expire date or default to 1 year
        if (expireDate != null) {
            session.setExpireDate(expireDate);
        } else {
            long defaultExpire = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000);
            session.setExpireDate(defaultExpire);
        }

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
                    "&cUsage: /lmb view <mailId>"));
            return true;
        }

        Player player = (Player) sender;
        String mailId = args[1];

        Optional<MailRecord> recordOpt = plugin.getMailRepository().findRecord(mailId);
        if (recordOpt.isEmpty()) {
            player.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.mail-not-found")));
            return true;
        }

        MailRecord record = recordOpt.get();
        boolean canAccess = record.canBeClaimedBy(player.getName());

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
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + "&cUsage: /lmb as <player>"));
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
                    "&cConsole cannot open GUI. Use /lmb <player> instead."));
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
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + "&cUsage: /lmb <player>"));
            return true;
        }

        Player player = (Player) sender;
        plugin.openMainGUI(player);
        return true;
    }

    /**
     * Parses date string in format YYYY:MM:DD:HH:mm to timestamp
     */
    private Long parseDateString(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        try {
            String[] parts = dateStr.split(":");
            if (parts.length != 5) {
                return null;
            }

            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]) - 1; // Calendar months are 0-based
            int day = Integer.parseInt(parts[2]);
            int hour = Integer.parseInt(parts[3]);
            int minute = Integer.parseInt(parts[4]);

            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(year, month, day, hour, minute, 0);
            calendar.set(java.util.Calendar.MILLISECOND, 0);

            return calendar.getTimeInMillis();
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }
}

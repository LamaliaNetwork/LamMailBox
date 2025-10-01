package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.mailing.MailingDefinition;
import com.yusaki.lammailbox.mailing.MailingType;
import com.yusaki.lammailbox.repository.MailRecord;
import com.yusaki.lammailbox.service.MailDelivery;
import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class LmbCommandExecutor implements CommandExecutor {
    private final LamMailBox plugin;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

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
            case "mailings":
                return handleMailings(sender);
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
            String usage = config.getString("messages.usage-send",
                    "&cUsage: /lmb send <users> <message> | [commands] | schedule:YYYY:MM:DD:HH:mm | expire:YYYY:MM:DD:HH:mm");
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + usage));
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
                String consoleSent = config.getString("messages.mail-sent-console", "&aMail sent successfully.");
                sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + consoleSent));
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
            String usage = config.getString("messages.usage-view", "&cUsage: /lmb view <mailId>");
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + usage));
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
            String usage = config.getString("messages.usage-as", "&cUsage: /lmb as <player>");
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + usage));
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
            String message = config.getString("messages.mailbox-opened-as", "&aOpened mailbox as %player%");
            admin.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    message.replace("%player%", targetPlayer.getName())));
        } else {
            String warning = config.getString("messages.console-gui-warning",
                    "&cConsole cannot open GUI. Use /lmb <player> instead.");
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + warning));
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
        String message = config.getString("messages.mailbox-opened-for", "&aOpened mailbox for %player%");
        sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                message.replace("%player%", target.getName())));
        return true;
    }

    private boolean handleDefault(CommandSender sender) {
        FileConfiguration config = plugin.getConfig();
        if (!(sender instanceof Player)) {
            String usage = config.getString("messages.usage-default", "&cUsage: /lmb <player>");
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + usage));
            return true;
        }

        Player player = (Player) sender;
        plugin.openMainGUI(player);
        return true;
    }

    private boolean handleMailings(CommandSender sender) {
        FileConfiguration config = plugin.getConfig();
        if (!sender.hasPermission(config.getString("settings.admin-permission"))) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    config.getString("messages.no-permission")));
            return true;
        }

        List<MailingDefinition> definitions = plugin.getMailingDefinitions();
        if (definitions.isEmpty()) {
            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") +
                    "&7No mailings configured."));
            return true;
        }

        sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + "&6Mailings:"));
        long now = System.currentTimeMillis();
        var statusRepository = plugin.getMailingStatusRepository();
        var scheduler = plugin.getMailingScheduler();
        for (MailingDefinition definition : definitions) {
            StringBuilder line = new StringBuilder();
            line.append("&e").append(definition.id()).append(" &7[")
                    .append(definition.type()).append("] ")
                    .append(definition.enabled() ? "&aENABLED" : "&cDISABLED");

            switch (definition.type()) {
                case REPEATING -> {
                    if (definition.cronExpression() != null) {
                        line.append(" &7cron=\"&f").append(definition.cronExpression()).append("&7\"");
                    }
                    long lastRun = statusRepository != null ? statusRepository.getLastRun(definition.id()) : 0L;
                    if (lastRun > 0) {
                        line.append(" &7last: &f").append(formatTime(lastRun));
                    }
                    OptionalLong nextRun = scheduler != null ? scheduler.previewNextRunEpoch(definition.id()) : OptionalLong.empty();
                    nextRun.ifPresent(value -> line.append(" &7next: &f").append(formatTime(value)));
                    if (definition.maxRuns() != null) {
                        int runCount = statusRepository != null ? statusRepository.getRunCount(definition.id()) : 0;
                        line.append(" &7runs: &f").append(runCount).append("/&f").append(definition.maxRuns());
                        if (runCount >= definition.maxRuns()) {
                            line.append(" &7(status: &acompleted&7)");
                        }
                    }
                }
                case FIRST_JOIN -> line.append(" &7-> &fOn first join");
            }

            sender.sendMessage(plugin.colorize(config.getString("messages.prefix") + line));
        }
        return true;
    }

    private String formatTime(long timestamp) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp));
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

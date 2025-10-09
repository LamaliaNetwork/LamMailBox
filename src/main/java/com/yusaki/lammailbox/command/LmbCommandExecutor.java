package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.mailing.MailingDefinition;
import com.yusaki.lammailbox.mailing.MailingType;
import com.yusaki.lammailbox.model.CommandItem;
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
            plugin.sendPrefixedMessage(sender, "messages.send-command-admin-only");
            return true;
        }

        if (args.length < 2) {
            plugin.sendPrefixedMessage(sender, "messages.usage-send");
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
                    plugin.sendPrefixedMessage(sender, "messages.invalid-date-format");
                    return true;
                }
            } else if (section.startsWith("expire:")) {
                // Parse expire date
                String dateStr = section.substring(7).trim();
                expireDate = parseDateString(dateStr);
                if (expireDate == null) {
                    plugin.sendPrefixedMessage(sender, "messages.invalid-date-format");
                    return true;
                }
            } else if (!section.isEmpty()) {
                // Parse commands section
                if (sender instanceof Player && !sender.hasPermission(config.getString("settings.admin-permission"))) {
                    plugin.sendPrefixedMessage(sender, "messages.no-permission");
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
        if (!commands.isEmpty()) {
            CommandItem.Builder builder = new CommandItem.Builder()
                    .material("COMMAND_BLOCK")
                    .displayName("&6Console Actions")
                    .setCommands(commands);
            session.setCommandItems(Collections.singletonList(builder.build()));
        }

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
                plugin.sendPrefixedMessage(player, "messages.mail-sent");
            } else {
                MailDelivery delivery = plugin.getMailService().sendConsoleMail(sender, session);
                if (delivery.shouldNotifyNow()) {
                    plugin.dispatchMailNotifications(delivery.getReceiverSpec(), delivery.getMailId(), delivery.getSenderName());
                }
                plugin.sendPrefixedMessage(sender, "messages.mail-sent-console");
            }
        } catch (IllegalArgumentException ex) {
            plugin.sendPrefixedMessage(sender, "messages.incomplete-mail");
        }
        return true;
    }

    private boolean handleView(CommandSender sender, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (!(sender instanceof Player)) {
            plugin.sendPrefixedMessage(sender, "messages.console-only");
            return true;
        }

        if (args.length < 2) {
            plugin.sendPrefixedMessage(sender, "messages.usage-view");
            return true;
        }

        Player player = (Player) sender;
        String mailId = args[1];

        Optional<MailRecord> recordOpt = plugin.getMailRepository().findRecord(mailId);
        if (recordOpt.isEmpty()) {
            plugin.sendPrefixedMessage(player, "messages.mail-not-found");
            return true;
        }

        MailRecord record = recordOpt.get();
        boolean canAccess = record.canBeClaimedBy(player.getName());

        if (canAccess) {
            plugin.openMailView(player, mailId);
        } else {
            plugin.sendPrefixedMessage(player, "messages.mail-no-access");
        }
        return true;
    }

    private boolean handleAs(CommandSender sender, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (!sender.hasPermission(config.getString("settings.permissions.view-as"))) {
            plugin.sendPrefixedMessage(sender, "messages.no-permission");
            return true;
        }

        if (args.length < 2) {
            plugin.sendPrefixedMessage(sender, "messages.usage-as");
            return true;
        }

        Player targetPlayer = Bukkit.getPlayer(args[1]);
        if (targetPlayer == null) {
            plugin.sendPrefixedMessage(sender, "messages.invalid-receiver");
            return true;
        }

        if (sender instanceof Player) {
            Player admin = (Player) sender;
            plugin.openMailboxAsPlayer(admin, targetPlayer);
            plugin.sendPrefixedMessage(admin, "messages.mailbox-opened-as",
                    plugin.placeholders("player", targetPlayer.getName()));
        } else {
            plugin.sendPrefixedMessage(sender, "messages.console-gui-warning");
        }
        return true;
    }

    private boolean handleOpenOther(CommandSender sender, String targetName) {
        FileConfiguration config = plugin.getConfig();
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.sendPrefixedMessage(sender, "messages.invalid-receiver");
            return true;
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;
            String permission = config.getString("settings.permissions.open-others", "lammailbox.open.others");
            if (!player.hasPermission(permission)) {
                plugin.sendPrefixedMessage(player, "messages.no-permission");
                return true;
            }
        }

        plugin.openMainGUI(target);
        plugin.sendPrefixedMessage(sender, "messages.mailbox-opened-for",
                plugin.placeholders("player", target.getName()));
        return true;
    }

    private boolean handleDefault(CommandSender sender) {
        FileConfiguration config = plugin.getConfig();
        if (!(sender instanceof Player)) {
            plugin.sendPrefixedMessage(sender, "messages.usage-default");
            return true;
        }

        Player player = (Player) sender;
        plugin.openMainGUI(player);
        return true;
    }

    private boolean handleMailings(CommandSender sender) {
        FileConfiguration config = plugin.getConfig();
        if (!sender.hasPermission(config.getString("settings.admin-permission"))) {
            plugin.sendPrefixedMessage(sender, "messages.no-permission");
            return true;
        }

        List<MailingDefinition> definitions = plugin.getMailingDefinitions();
        String header = plugin.getMailingsMessage("header", "&6Mailings:");
        String empty = plugin.getMailingsMessage("empty", "&7No mailings configured.");
        if (definitions.isEmpty()) {
            plugin.sendPrefixedRaw(sender, empty);
            return true;
        }

        plugin.sendPrefixedRaw(sender, header);
        var statusRepository = plugin.getMailingStatusRepository();
        var scheduler = plugin.getMailingScheduler();
        String repeatingTemplate = plugin.getMailingsMessage("entry-repeating",
                "&e• &f%id% &8| %status% &8| &7Schedule: &f%schedule% &8| &7Next: &f%next% &8| &7Last: &f%last%%runs%%completed%");
        String firstJoinTemplate = plugin.getMailingsMessage("entry-first-join",
                "&e• &f%id% &8| %status% &8| &7Trigger: &fOn first join%completed%");
        String runsTemplate = plugin.getMailingsMessage("runs", " &8| &7Runs: &f%current%&7/&f%max%");
        String completedTemplate = plugin.getMailingsMessage("completed", " &8| &a✓ Completed");
        String missingValue = plugin.getMailingsMessage("value-missing", "&7—");
        String statusEnabled = plugin.getMailingsMessage("status-enabled", "&aEnabled");
        String statusDisabled = plugin.getMailingsMessage("status-disabled", "&cDisabled");
        for (MailingDefinition definition : definitions) {
            String statusText = definition.enabled() ? statusEnabled : statusDisabled;

            String scheduleText = missingValue;
            String nextText = missingValue;
            String lastText = missingValue;
            String runsSegment = "";
            String completedSegment = "";

            if (definition.type() == MailingType.REPEATING) {
                String cronExpression = definition.cronExpression();
                if (cronExpression != null && !cronExpression.isBlank()) {
                    String described = plugin.describeCron(cronExpression);
                    scheduleText = (described == null || described.isBlank()) ? cronExpression : described;
                }

                long lastRun = statusRepository != null ? statusRepository.getLastRun(definition.id()) : 0L;
                if (lastRun > 0) {
                    lastText = formatTime(lastRun);
                }

                OptionalLong nextRun = scheduler != null ? scheduler.previewNextRunEpoch(definition.id()) : OptionalLong.empty();
                if (nextRun.isPresent()) {
                    nextText = formatTime(nextRun.getAsLong());
                }

                if (definition.maxRuns() != null) {
                    int runCount = statusRepository != null ? statusRepository.getRunCount(definition.id()) : 0;
                    runsSegment = plugin.applyPlaceholderVariants(runsTemplate, Map.of(
                            "current", String.valueOf(runCount),
                            "max", String.valueOf(definition.maxRuns())));
                    if (runCount >= definition.maxRuns()) {
                        completedSegment = completedTemplate;
                    }
                }
            }

            String template = definition.type() == MailingType.FIRST_JOIN ? firstJoinTemplate : repeatingTemplate;
            String line = plugin.applyPlaceholderVariants(template, Map.of(
                    "id", definition.id(),
                    "status", statusText,
                    "schedule", scheduleText,
                    "next", nextText,
                    "last", lastText,
                    "runs", runsSegment,
                    "completed", completedSegment));

            plugin.sendPrefixedRaw(sender, line);
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

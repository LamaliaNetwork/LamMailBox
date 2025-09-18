package com.yusaki.lammailbox.session;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;


/**
 * Coordinates mail creation prompts and chat input handling.
 */
public class MailCreationController {
    private final LamMailBox plugin;

    public MailCreationController(LamMailBox plugin) {
        this.plugin = plugin;
    }

    public void showInputTitle(Player player, String type) {
        String path = "titles.input." + type;
        String title = plugin.colorize(config().getString(path + ".title"));
        String subtitle = plugin.colorize(config().getString(path + ".subtitle"));
        int fadeIn = config().getInt("titles.input.fadein");
        int stay = config().getInt("titles.input.stay");
        int fadeOut = config().getInt("titles.input.fadeout");
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    public void showResponseTitle(Player player, boolean success, String customSubtitle) {
        String type = success ? "success" : "error";
        String titlePath = "titles.response." + type;
        String title = plugin.colorize(config().getString(titlePath + ".title"));
        String subtitle = customSubtitle != null ?
                plugin.colorize(customSubtitle) :
                plugin.colorize(config().getString(titlePath + ".subtitle"));
        int fadeIn = config().getInt("titles.response.fadein");
        int stay = config().getInt("titles.response.stay");
        int fadeOut = config().getInt("titles.response.fadeout");
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    public boolean handleReceiverInput(Player sender, String input, MailCreationSession session) {
        if (input.equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                    config().getString("messages.self-mail")));
            return false;
        }

        if (isBulkTarget(input)) {
            if (!sender.hasPermission(config().getString("settings.admin-permission"))) {
                sender.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                        config().getString("messages.no-permission")));
                return false;
            }
            session.setReceiver(input);
            return true;
        }

        int currentMails = database().getInt("player-mail-counts." + input, 0);
        if (currentMails >= config().getInt("settings.max-mails-per-player")) {
            sender.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                    config().getString("messages.mailbox-full")));
            return false;
        }

        session.setReceiver(input);
        return true;
    }
    public boolean handleCommandInput(Player player, String command, MailCreationSession session) {
        List<String> commands = new ArrayList<>(session.getCommands());
        commands.add(command);
        session.setCommands(commands);
        return true;
    }

    public boolean handleDateInput(Player player, String input, MailCreationSession session, boolean isSchedule) {
        try {
            String[] parts = input.split(":");
            if (parts.length != 5) {
                throw new IllegalArgumentException();
            }

            Calendar calendar = Calendar.getInstance();
            calendar.set(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]) - 1,
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    0
            );

            long timestamp = calendar.getTimeInMillis();

            if (isSchedule) {
                session.setScheduleDate(timestamp);
                player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                        config().getString("messages.schedule-set").replace("%date%", calendar.getTime().toString())));
            } else {
                session.setExpireDate(timestamp);
                player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                        config().getString("messages.expire-set").replace("%date%", calendar.getTime().toString())));
            }
            return true;
        } catch (Exception e) {
            player.sendMessage(plugin.colorize(config().getString("messages.prefix") +
                    config().getString("messages.invalid-date-format")));
            return false;
        }
    }

    private boolean isBulkTarget(String input) {
        return input.equalsIgnoreCase("all") || input.equalsIgnoreCase("allonline") || input.contains(";");
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    public void reopenCreationAsync(Player player) {
        if (player == null) {
            return;
        }
        plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCreateMailGUI(player));
    }

    private FileConfiguration database() {
        return plugin.getMailRepository().getBackingConfiguration();
    }
}

package com.yusaki.lammailbox.session;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import org.bukkit.Material;
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
        String titleKey = config().contains(path + ".title") ? path + ".title" : "titles.input.command.title";
        String subtitleKey = config().contains(path + ".subtitle") ? path + ".subtitle" : "titles.input.command.subtitle";

        plugin.getYskLib().messageManager.sendTitle(
                plugin,
                player,
                titleKey,
                subtitleKey,
                config().getInt("titles.input.fadein"),
                config().getInt("titles.input.stay"),
                config().getInt("titles.input.fadeout"),
                Collections.emptyMap()
        );
    }

    public void showResponseTitle(Player player, boolean success, String customSubtitle) {
        String type = success ? "success" : "error";
        String titlePath = "titles.response." + type;

        if (customSubtitle != null) {
            String title = plugin.getYskLib().messageManager.getMessage(plugin, titlePath + ".title", Collections.emptyMap());
            String subtitle = plugin.legacy(customSubtitle);
            player.sendTitle(title, subtitle,
                    config().getInt("titles.response.fadein"),
                    config().getInt("titles.response.stay"),
                    config().getInt("titles.response.fadeout"));
        } else {
            plugin.getYskLib().messageManager.sendTitle(
                    plugin,
                    player,
                    titlePath + ".title",
                    titlePath + ".subtitle",
                    config().getInt("titles.response.fadein"),
                    config().getInt("titles.response.stay"),
                    config().getInt("titles.response.fadeout"),
                    Collections.emptyMap()
            );
        }
    }

    public boolean handleReceiverInput(Player sender, String input, MailCreationSession session) {
        if (input.equalsIgnoreCase(sender.getName())) {
            plugin.sendPrefixedMessage(sender, "messages.self-mail");
            return false;
        }

        if (isBulkTarget(input)) {
            if (!sender.hasPermission(config().getString("settings.admin-permission"))) {
                plugin.sendPrefixedMessage(sender, "messages.no-permission");
                return false;
            }
            session.setReceiver(input);
            return true;
        }

        int currentMails = plugin.getMailRepository().countActiveMailFor(input);
        if (currentMails >= config().getInt("settings.max-mails-per-player")) {
            plugin.sendPrefixedMessage(sender, "messages.mailbox-full");
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

    public boolean handleCommandItemMaterialInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null || input.trim().isEmpty()) {
            plugin.sendPrefixedMessage(player, "messages.invalid-material");
            return false;
        }

        String trimmed = input.trim();
        Material material = Material.matchMaterial(trimmed, false);
        if (material == null) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            material = Material.matchMaterial(upper, false);
        }
        if (material == null && !trimmed.contains(":")) {
            try {
                material = Material.valueOf(trimmed.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (material == null) {
            plugin.sendPrefixedMessage(player, "messages.invalid-material");
            return false;
        }
        draft.material(material.name());
        plugin.sendPrefixedMessage(player, "messages.command-item-material-set",
                plugin.placeholders("material", material.name()));
        return true;
    }

    public boolean handleCommandItemNameInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null || input.trim().isEmpty()) {
            plugin.sendPrefixedMessage(player, "messages.command-item-name-failed");
            return false;
        }
        draft.displayName(input);
        plugin.sendPrefixedMessage(player, "messages.command-item-name-set");
        return true;
    }

    public boolean handleCommandItemLoreInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            plugin.sendPrefixedMessage(player, "messages.command-item-lore-failed");
            return false;
        }
        draft.addLoreLine(input);
        plugin.sendPrefixedMessage(player, "messages.command-item-lore-added");
        return true;
    }

    public boolean handleCommandItemCommandInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null || input.trim().isEmpty()) {
            plugin.sendPrefixedMessage(player, "messages.command-item-command-failed");
            return false;
        }
        String trimmed = input.startsWith("/") ? input.substring(1) : input;
        draft.addCommand(trimmed);
        plugin.sendPrefixedMessage(player, "messages.command-item-command-added");
        return true;
    }

    public boolean handleCommandItemCustomModelInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null) {
            plugin.sendPrefixedMessage(player, "messages.command-item-model-invalid");
            return false;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("clear") || trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("reset")) {
            draft.customModelData(null);
            plugin.sendPrefixedMessage(player, "messages.command-item-model-cleared");
            return true;
        }

        try {
            int value = Integer.parseInt(trimmed);
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            draft.customModelData(value);
            plugin.sendPrefixedMessage(player, "messages.command-item-model-set",
                    plugin.placeholders("value", String.valueOf(value)));
            return true;
        } catch (NumberFormatException ex) {
            plugin.sendPrefixedMessage(player, "messages.command-item-model-invalid");
            return false;
        }
    }

    public boolean finalizeCommandItem(Player player, MailCreationSession session) {
        CommandItem.Builder draft = session.getCommandItemDraft();
        if (draft == null) {
            plugin.sendPrefixedMessage(player, "messages.command-item-no-draft");
            return false;
        }

        if (draft.commands().isEmpty()) {
            plugin.sendPrefixedMessage(player, "messages.command-item-requires-command");
            return false;
        }

        CommandItem built = draft.build();
        List<CommandItem> items = new ArrayList<>(session.getCommandItems());
        Integer editIndex = session.getCommandItemEditIndex();
        if (editIndex != null && editIndex >= 0 && editIndex < items.size()) {
            items.set(editIndex, built);
        } else {
            items.add(built);
        }
        session.setCommandItems(items);
        session.setCommandItemDraft(null);
        session.setCommandItemEditIndex(null);
        plugin.sendPrefixedMessage(player, "messages.command-item-saved");
        return true;
    }

    public void cancelCommandItemEdit(MailCreationSession session) {
        session.setCommandItemDraft(null);
        session.setCommandItemEditIndex(null);
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
                plugin.sendPrefixedMessage(player, "messages.schedule-set",
                        plugin.placeholders("date", calendar.getTime().toString()));
            } else {
                session.setExpireDate(timestamp);
                plugin.sendPrefixedMessage(player, "messages.expire-set",
                        plugin.placeholders("date", calendar.getTime().toString()));
            }
            return true;
        } catch (Exception e) {
            plugin.sendPrefixedMessage(player, "messages.invalid-date-format");
            return false;
        }
    }

    private boolean isBulkTarget(String input) {
        return input.equalsIgnoreCase("all") || input.equalsIgnoreCase("allonline") || input.contains(";");
    }
    public void reopenCreationAsync(Player player) {
        if (player == null) {
            return;
        }
        plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCreateMailGUI(player));
    }

    public void reopenCommandItemCreatorAsync(Player player) {
        if (player == null) {
            return;
        }
        plugin.getFoliaLib().getScheduler().runNextTick(task -> plugin.openCommandItemCreator(player));
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private CommandItem.Builder ensureCommandItemDraft(MailCreationSession session) {
        CommandItem.Builder draft = session.getCommandItemDraft();
        if (draft == null) {
            draft = new CommandItem.Builder();
            session.setCommandItemDraft(draft);
        }
        return draft;
    }

}

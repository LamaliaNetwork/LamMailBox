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
        String rawTitle = config().getString(path + ".title", config().getString("titles.input.command.title"));
        String rawSubtitle = config().getString(path + ".subtitle", config().getString("titles.input.command.subtitle"));
        String title = plugin.colorize(rawTitle != null ? rawTitle : "");
        String subtitle = plugin.colorize(rawSubtitle != null ? rawSubtitle : "");
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

        int currentMails = plugin.getMailRepository().countActiveMailFor(input);
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

    public boolean handleCommandItemMaterialInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null || input.trim().isEmpty()) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.invalid-material")));
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
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.invalid-material")));
            return false;
        }
        draft.material(material.name());
        String materialTemplate = config().getString("messages.command-item-material-set", "&a✔ Material set to %material%");
        player.sendMessage(plugin.colorize(prefix() +
                plugin.applyPlaceholderVariants(materialTemplate, "material", material.name())));
        return true;
    }

    public boolean handleCommandItemNameInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null || input.trim().isEmpty()) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-name-failed", "&c✖ Name update failed.")));
            return false;
        }
        draft.displayName(input);
        player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-name-set", "&a✔ Name updated.")));
        return true;
    }

    public boolean handleCommandItemLoreInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-lore-failed", "&c✖ Lore update failed")));
            return false;
        }
        draft.addLoreLine(input);
        player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-lore-added", "&a✔ Added lore line.")));
        return true;
    }

    public boolean handleCommandItemCommandInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null || input.trim().isEmpty()) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-command-failed", "&c✖ Command update failed")));
            return false;
        }
        String trimmed = input.startsWith("/") ? input.substring(1) : input;
        draft.addCommand(trimmed);
        player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-command-added", "&a✔ Added command.")));
        return true;
    }

    public boolean handleCommandItemCustomModelInput(Player player, String input, MailCreationSession session) {
        CommandItem.Builder draft = ensureCommandItemDraft(session);
        if (input == null) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-model-invalid", "&c✖ Invalid custom model data.")));
            return false;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("clear") || trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("reset")) {
            draft.customModelData(null);
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-model-cleared", "&a✔ Custom model data cleared.")));
            return true;
        }

        try {
            int value = Integer.parseInt(trimmed);
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            draft.customModelData(value);
            String template = config().getString("messages.command-item-model-set", "&a✔ Custom model data updated.");
            String formatted = plugin.applyPlaceholderVariants(template, "value", String.valueOf(value));
            player.sendMessage(plugin.colorize(prefix() + formatted));
            return true;
        } catch (NumberFormatException ex) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-model-invalid", "&c✖ Invalid custom model data.")));
            return false;
        }
    }

    public boolean finalizeCommandItem(Player player, MailCreationSession session) {
        CommandItem.Builder draft = session.getCommandItemDraft();
        if (draft == null) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-no-draft", "&c✖ Nothing to save.")));
            return false;
        }

        if (draft.commands().isEmpty()) {
            player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-requires-command", "&c✖ Add at least one command.")));
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
        player.sendMessage(plugin.colorize(prefix() + config().getString("messages.command-item-saved", "&a✔ Command item saved!")));
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
                String template = config().getString("messages.schedule-set");
                String formatted = plugin.applyPlaceholderVariants(template, "date", calendar.getTime().toString());
                player.sendMessage(plugin.colorize(config().getString("messages.prefix") + formatted));
            } else {
                session.setExpireDate(timestamp);
                String template = config().getString("messages.expire-set");
                String formatted = plugin.applyPlaceholderVariants(template, "date", calendar.getTime().toString());
                player.sendMessage(plugin.colorize(config().getString("messages.prefix") + formatted));
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

    private String prefix() {
        return config().getString("messages.prefix", "");
    }
}

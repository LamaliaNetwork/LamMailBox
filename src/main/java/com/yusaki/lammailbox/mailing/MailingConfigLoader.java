package com.yusaki.lammailbox.mailing;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.model.CommandItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;

public final class MailingConfigLoader {
    private final LamMailBox plugin;
    private final File file;

    public MailingConfigLoader(LamMailBox plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), "mailings.yml");
    }

    public List<MailingDefinition> load() {
        ensureFileExists();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("mailings");
        if (root == null) {
            return Collections.emptyList();
        }

        List<MailingDefinition> definitions = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            MailingDefinition definition = parseDefinition(id, section);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        return definitions;
    }

    private MailingDefinition parseDefinition(String id, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        String typeRaw = section.getString("type", "REPEATING");
        MailingType type = MailingType.from(typeRaw, MailingType.REPEATING);
        String message = section.getString("message");
        if (message == null || message.isBlank()) {
            plugin.getLogger().warning("Mailing " + id + " ignored: missing message");
            return null;
        }

        MailingDefinition.Builder builder = MailingDefinition.builder(id)
                .enabled(enabled)
                .type(type)
                .message(message)
                .sender(section.getString("sender"))
                .receiver(section.getString("receiver"))
                .requiredPermission(section.getString("required-permission"))
                .expireDays(parsePositiveInt(section, "expire-days"))
                .itemDirectives(cleanStrings(section.getStringList("items")))
                .commands(cleanStrings(section.getStringList("commands")));

        List<Map<?, ?>> rawCommandItems = section.getMapList("command-items");
        if (rawCommandItems != null && !rawCommandItems.isEmpty()) {
            builder.commandItems(parseCommandItems(rawCommandItems));
        }

        ConfigurationSection schedule = section.getConfigurationSection("schedule");

        if (type == MailingType.REPEATING) {
            applyCronSchedule(id, builder, schedule, typeRaw);
        } else if (type == MailingType.FIRST_JOIN) {
            builder.firstJoinDelay(parseFirstJoinDelay(schedule));
        }

        return builder.build();
    }

    private void applyCronSchedule(String id,
                                   MailingDefinition.Builder builder,
                                   ConfigurationSection schedule,
                                   String rawType) {
        if (schedule == null) {
            plugin.getLogger().warning("Mailing " + id + " ignored: cron mailings require schedule.cron");
            builder.enabled(false);
            return;
        }

        String cron = schedule.getString("cron");
        if (cron == null || cron.isBlank()) {
            // Legacy helpers
            String runAt = schedule.getString("run-at");
            if (runAt != null) {
                Long timestamp = parseDateTime(runAt);
                if (timestamp != null) {
                    java.util.Calendar calendar = java.util.Calendar.getInstance(Locale.ROOT);
                    calendar.setTimeInMillis(timestamp);
                    cron = calendar.get(java.util.Calendar.MINUTE) + " " +
                            calendar.get(java.util.Calendar.HOUR_OF_DAY) + " " +
                            calendar.get(java.util.Calendar.DAY_OF_MONTH) + " " +
                            (calendar.get(java.util.Calendar.MONTH) + 1) + " *";
                    builder.maxRuns(1);
                }
            }
        }

        if (cron == null || cron.isBlank()) {
            plugin.getLogger().warning("Mailing " + id + " ignored: missing cron expression");
            builder.enabled(false);
            return;
        }

        builder.cronExpression(cron);

        Integer maxRuns = parsePositiveInt(schedule, "max-runs");
        if (maxRuns != null) {
            builder.maxRuns(maxRuns);
        } else if ("ONE_TIME".equalsIgnoreCase(rawType)) {
            builder.maxRuns(1);
        }
    }

    private Duration parseFirstJoinDelay(ConfigurationSection schedule) {
        if (schedule == null) {
            return null;
        }
        Integer minutes = parsePositiveInt(schedule, "delay-minutes");
        if (minutes != null) {
            return Duration.ofMinutes(minutes);
        }
        Integer seconds = parsePositiveInt(schedule, "delay-seconds");
        if (seconds != null) {
            return Duration.ofSeconds(seconds);
        }
        return null;
    }

    private List<String> cleanStrings(List<String> input) {
        if (input == null) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>();
        for (String entry : input) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }

    private List<CommandItem> parseCommandItems(List<Map<?, ?>> rawItems) {
        List<CommandItem> items = new ArrayList<>();
        for (Map<?, ?> rawItem : rawItems) {
            if (rawItem == null) {
                continue;
            }
            Map<String, Object> normalized = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : rawItem.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(entry.getKey().toString(), entry.getValue());
                }
            }
            CommandItem item = CommandItem.fromMap(normalized);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private Integer parsePositiveInt(ConfigurationSection section, String path) {
        if (section == null || !section.contains(path)) {
            return null;
        }
        int value = section.getInt(path, -1);
        return value > 0 ? value : null;
    }

    private Long parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String[] parts = raw.trim().split(":");
            if (parts.length != 5) {
                return null;
            }
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]) - 1;
            int day = Integer.parseInt(parts[2]);
            int hour = Integer.parseInt(parts[3]);
            int minute = Integer.parseInt(parts[4]);

            java.util.Calendar calendar = java.util.Calendar.getInstance(Locale.ROOT);
            calendar.set(year, month, day, hour, minute, 0);
            calendar.set(java.util.Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void ensureFileExists() {
        if (!file.exists()) {
            plugin.saveResource("mailings.yml", false);
        }
    }

    public void saveDefaultIfMissing() {
        ensureFileExists();
    }

    public File getFile() {
        return file;
    }
}

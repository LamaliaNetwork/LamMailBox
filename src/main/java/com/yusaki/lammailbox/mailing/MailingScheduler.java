package com.yusaki.lammailbox.mailing;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.cronutils.model.CronType;
import com.tcoded.folialib.FoliaLib;
import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.mailing.status.MailingStatusRepository;
import com.yusaki.lammailbox.service.MailDelivery;
import com.yusaki.lammailbox.service.MailService;
import com.yusaki.lammailbox.session.MailCreationSession;
import com.yusaki.lammailbox.model.CommandItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MailingScheduler {
    private static final long CHECK_PERIOD_TICKS = 200L; // 10 seconds

    private final LamMailBox plugin;
    private final MailService mailService;
    private final MailingStatusRepository statusRepository;
    private final FoliaLib foliaLib;
    private final CronParser cronParser;
    private final ZoneId zoneId;
    private volatile List<MailingDefinition> definitions = Collections.emptyList();
    private volatile List<CronEntry> cronEntries = Collections.emptyList();
    private volatile Map<String, CronEntry> cronEntryIndex = Collections.emptyMap();
    private volatile List<MailingDefinition> firstJoinDefinitions = Collections.emptyList();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean running;

    public MailingScheduler(LamMailBox plugin,
                            MailService mailService,
                            MailingStatusRepository statusRepository,
                            FoliaLib foliaLib) {
        this.plugin = plugin;
        this.mailService = mailService;
        this.statusRepository = statusRepository;
        this.foliaLib = foliaLib;
        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        this.cronParser = new CronParser(cronDefinition);
        this.zoneId = ZoneId.systemDefault();
    }

    public void start(List<MailingDefinition> initialDefinitions) {
        rebuildSchedules(initialDefinitions);
        if (started.compareAndSet(false, true)) {
            running = true;
            foliaLib.getScheduler().runTimer(this::tick, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
        }
    }

    public void updateDefinitions(List<MailingDefinition> updated) {
        rebuildSchedules(updated);
    }

    public void shutdown() {
        running = false;
    }

    public void handlePlayerJoin(Player player) {
        if (!running) {
            return;
        }

        // Schedule on player's region thread to safely access player state
        foliaLib.getScheduler().runAtEntity(player, task -> {
            if (!player.isOnline()) {
                return;
            }

            boolean firstJoin = !player.hasPlayedBefore();
            if (!firstJoin) {
                return;
            }

            String playerName = player.getName();
            var uuid = player.getUniqueId();

            for (MailingDefinition definition : firstJoinDefinitions) {
                if (!definition.enabled() || definition.type() != MailingType.FIRST_JOIN) {
                    continue;
                }

                if (definition.requiredPermission() != null && !player.hasPermission(definition.requiredPermission())) {
                    continue;
                }

                // Atomically mark as received - prevents duplicates
                long now = System.currentTimeMillis();
                if (!statusRepository.markReceivedIfNew(definition.id(), uuid, now)) {
                    // Player already received this mailing
                    continue;
                }

                Runnable delivery = () -> deliverToSinglePlayer(definition, playerName);
                Duration delay = definition.firstJoinDelay();
                if (delay != null && !delay.isNegative() && !delay.isZero()) {
                    long ticks = Math.max(1L, delay.toMillis() / 50L);
                    foliaLib.getScheduler().runLater(delayTask -> delivery.run(), ticks);
                } else {
                    submit(delivery);
                }
            }
        });
    }

    private void tick() {
        if (!running) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        for (CronEntry entry : cronEntries) {
            handleCron(entry, now);
        }
    }

    private void handleCron(CronEntry entry, ZonedDateTime now) {
        MailingDefinition definition = entry.definition();
        if (!definition.enabled()) {
            return;
        }
        Integer maxRuns = definition.maxRuns();

        long lastRunMillis = statusRepository.getLastRun(definition.id());
        ZonedDateTime reference = lastRunMillis > 0
                ? ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastRunMillis), zoneId)
                : now.minusMinutes(1);

        int executions = 0;
        while (executions < 5) {
            Optional<ZonedDateTime> nextExecution = entry.executionTime().nextExecution(reference);
            if (nextExecution.isEmpty()) {
                break;
            }
            ZonedDateTime next = nextExecution.get();
            if (next.isAfter(now)) {
                break;
            }

            // Update lastRun FIRST to prevent re-processing on server crash
            long scheduledMillis = next.toInstant().toEpochMilli();
            statusRepository.setLastRun(definition.id(), scheduledMillis);

            // Atomically check if we can increment, respecting max-runs
            if (maxRuns != null) {
                if (!statusRepository.incrementRunCountIfBelow(definition.id(), maxRuns)) {
                    // Already at or above max runs
                    break;
                }
            } else {
                // No max-runs limit, just increment
                statusRepository.incrementRunCount(definition.id());
            }

            // Deliver the mailing
            if (!deliverGlobal(definition).isPresent()) {
                // Delivery failed, but lastRun is already updated to prevent re-processing
                // and run count is incremented to prevent infinite retries
                break;
            }

            reference = next.plusSeconds(1);
            executions++;
        }
    }

    private Optional<MailDelivery> deliverGlobal(MailingDefinition definition) {
        return deliver(definition, resolveReceiver(definition.receiver()))
                .map(delivery -> {
                    plugin.dispatchMailNotifications(
                            delivery.getReceiverSpec(),
                            delivery.getMailId(),
                            definition.sender());
                    return delivery;
                });
    }

    private Optional<MailDelivery> deliverToSinglePlayer(MailingDefinition definition, String playerName) {
        return deliver(definition, playerName)
                .map(delivery -> {
                    plugin.dispatchMailNotifications(
                            delivery.getReceiverSpec(),
                            delivery.getMailId(),
                            definition.sender());
                    return delivery;
                });
    }

    private java.util.Optional<MailDelivery> deliver(MailingDefinition definition, String receiver) {
        MailCreationSession session = new MailCreationSession();
        session.setReceiver(receiver != null && !receiver.isBlank() ? receiver : "all");
        session.setMessage(definition.message());

        // Convert item directives to actual ItemStacks
        List<ItemStack> items = new ArrayList<>();
        for (String directive : definition.itemDirectives()) {
            ItemStack item = parseItemDirectiveToItemStack(directive);
            if (item != null) {
                items.add(item);
            }
        }
        session.setItems(items);

        // Only add actual commands, not converted items
        session.setCommands(new ArrayList<>(definition.commands()));
        session.setCommandItems(new ArrayList<>(definition.commandItems()));

        if (definition.expireDays() != null) {
            long expireAt = System.currentTimeMillis() + definition.expireDays() * 86_400_000L;
            session.setExpireDate(expireAt);
        }

        if (!session.isComplete()) {
            plugin.getLogger().warning("Skipping mailing " + definition.id() + " due to incomplete session");
            return java.util.Optional.empty();
        }

        try {
            MailDelivery delivery = mailService.sendConsoleMail(Bukkit.getConsoleSender(), session);
            if (!definition.sender().equalsIgnoreCase(delivery.getSenderName())) {
                plugin.getMailRepository().saveMail(delivery.getMailId(), java.util.Map.of("sender", definition.sender()));
            }
            return java.util.Optional.of(delivery);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Failed to send mailing " + definition.id() + ": " + ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    private ItemStack parseItemDirectiveToItemStack(String directive) {
        if (directive == null || directive.isBlank()) {
            return null;
        }

        String trimmed = directive.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }

        if (trimmed.toLowerCase(Locale.ROOT).startsWith("give ")) {
            trimmed = trimmed.substring(4).trim();
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        int index = 0;
        if (parts[index].equalsIgnoreCase("%player%")
                || parts[index].equalsIgnoreCase("@p")
                || parts[index].equalsIgnoreCase("@s")) {
            index++;
        }

        if (index >= parts.length) {
            return null;
        }

        String materialToken = parts[index];
        int nbtStart = materialToken.indexOf('{');
        if (nbtStart >= 0) {
            materialToken = materialToken.substring(0, nbtStart);
        }

        Material material = resolveMaterial(materialToken);
        if (material == null) {
            plugin.getLogger().warning("Invalid material in mailing item directive: " + directive);
            return null;
        }

        index++;
        // Extract amount
        int amount = 1;
        if (index < parts.length) {
            String candidate = parts[index];
            int braceIndex = candidate.indexOf('{');
            if (braceIndex >= 0) {
                candidate = candidate.substring(0, braceIndex);
            }
            if (!candidate.isEmpty()) {
                try {
                    amount = Integer.parseInt(candidate);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("Invalid amount in mailing item directive: " + directive + ". Using 1.");
                    amount = 1;
                }
            }
        }

        if (amount <= 0) {
            amount = 1;
        }

        return new ItemStack(material, amount);
    }

    private Material resolveMaterial(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key != null) {
            Material material = Registry.MATERIAL.get(key);
            if (material != null) {
                return material;
            }
        }

        try {
            return Material.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }

        return Material.matchMaterial(token, false);
    }

    private String resolveReceiver(String receiver) {
        return receiver == null || receiver.isBlank() ? "all" : receiver;
    }

    private void submit(Runnable task) {
        foliaLib.getScheduler().runNextTick(scheduledTask -> task.run());
    }

    public OptionalLong previewNextRunEpoch(String mailingId) {
        CronEntry entry = cronEntryIndex.get(mailingId);
        if (entry == null) {
            return OptionalLong.empty();
        }
        MailingDefinition definition = entry.definition();
        Integer maxRuns = definition.maxRuns();
        int runCount = statusRepository.getRunCount(definition.id());
        if (maxRuns != null && runCount >= maxRuns) {
            return OptionalLong.empty();
        }

        long lastRunMillis = statusRepository.getLastRun(definition.id());
        ZonedDateTime reference = lastRunMillis > 0
                ? ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastRunMillis), zoneId)
                : ZonedDateTime.now(zoneId);

        Optional<ZonedDateTime> next = entry.executionTime().nextExecution(reference);
        return next.map(zonedDateTime -> OptionalLong.of(zonedDateTime.toInstant().toEpochMilli()))
                .orElseGet(OptionalLong::empty);
    }

    private void rebuildSchedules(List<MailingDefinition> updated) {
        List<MailingDefinition> snapshot = Collections.unmodifiableList(new ArrayList<>(updated));
        this.definitions = snapshot;

        List<CronEntry> cronList = new ArrayList<>();
        Map<String, CronEntry> cronMap = new HashMap<>();
        List<MailingDefinition> firstJoinList = new ArrayList<>();

        for (MailingDefinition definition : snapshot) {
            if (definition.type() == MailingType.FIRST_JOIN) {
                firstJoinList.add(definition);
                continue;
            }
            if (definition.type() != MailingType.REPEATING) {
                continue;
            }
            String cronExpression = definition.cronExpression();
            if (cronExpression == null || cronExpression.isBlank()) {
                plugin.getLogger().warning("Skipping mailing " + definition.id() + " due to missing cron expression");
                continue;
            }
            try {
                Cron cron = cronParser.parse(cronExpression);
                cron.validate();
                ExecutionTime executionTime = ExecutionTime.forCron(cron);
                CronEntry entry = new CronEntry(definition, executionTime);
                cronList.add(entry);
                cronMap.put(definition.id(), entry);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid cron expression for mailing " + definition.id() + ": " + ex.getMessage());
            }
        }

        this.cronEntries = Collections.unmodifiableList(cronList);
        this.cronEntryIndex = Collections.unmodifiableMap(cronMap);
        this.firstJoinDefinitions = Collections.unmodifiableList(firstJoinList);
    }

    private record CronEntry(MailingDefinition definition, ExecutionTime executionTime) {
    }
}

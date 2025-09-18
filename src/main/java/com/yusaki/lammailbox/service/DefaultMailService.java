package com.yusaki.lammailbox.service;

import com.tcoded.folialib.FoliaLib;
import com.yusaki.lammailbox.repository.MailRepository;
import com.yusaki.lammailbox.session.MailCreationSession;
import com.yusaki.lammailbox.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultMailService implements MailService {
    private final JavaPlugin plugin;
    private final MailRepository repository;
    private final FoliaLib foliaLib;

    public DefaultMailService(JavaPlugin plugin, MailRepository repository, FoliaLib foliaLib) {
        this.plugin = plugin;
        this.repository = repository;
        this.foliaLib = foliaLib;
    }

    @Override
    public MailDelivery sendMail(Player sender, MailCreationSession session) {
        validateSession(session);
        boolean isAdminMail = sender.hasPermission(config().getString("settings.admin-permission"));
        return persistMail(sender.getName(), session, isAdminMail, session.getItems());
    }

    @Override
    public MailDelivery sendConsoleMail(CommandSender sender, MailCreationSession session) {
        validateSession(session);
        return persistMail(sender.getName(), session, true, Collections.emptyList());
    }

    @Override
    public boolean deleteMail(String mailId) {
        repository.deleteMail(mailId);
        saveAsync();
        return true;
    }

    @Override
    public boolean claimMail(Player player, String mailId) {
        Optional<Map<String, Object>> mailOpt = repository.findMail(mailId);
        if (mailOpt.isEmpty()) {
            return false;
        }

        Map<String, Object> mail = mailOpt.get();
        Object receiverObj = mail.get("receiver");
        if (!(receiverObj instanceof String)) {
            return false;
        }
        String receiver = (String) receiverObj;
        boolean changed = false;

        if ("all".equalsIgnoreCase(receiver)) {
            List<String> claimed = new ArrayList<>();
            Object claimedObj = mail.get("claimed-players");
            if (claimedObj instanceof List) {
                for (Object value : (List<?>) claimedObj) {
                    if (value != null) {
                        claimed.add(value.toString());
                    }
                }
            }
            if (!claimed.contains(player.getName())) {
                claimed.add(player.getName());
                repository.saveMail(mailId, Map.of("claimed-players", claimed));
                changed = true;
            }
        } else if (receiver.contains(";")) {
            List<String> receivers = new ArrayList<>(Arrays.asList(receiver.split(";")));
            if (receivers.remove(player.getName())) {
                if (receivers.isEmpty()) {
                    repository.deleteMail(mailId);
                } else {
                    repository.saveMail(mailId, Map.of("receiver", String.join(";", receivers)));
                }
                changed = true;
            }
        } else if (receiver.equalsIgnoreCase(player.getName())) {
            repository.deleteMail(mailId);
            changed = true;
        }

        if (changed) {
            saveAsync();
        }

        return changed;
    }

    @Override
    public List<MailDelivery> schedulePendingMails() {
        long now = System.currentTimeMillis();
        List<MailDelivery> deliveries = new ArrayList<>();
        for (String mailId : repository.listMailIds()) {
            Map<String, Object> mail = repository.loadMail(mailId);
            Long scheduleDate = getLong(mail.get("schedule-date"));
            if (scheduleDate == null || scheduleDate > now) {
                continue;
            }
            String receiver = (String) mail.get("receiver");
            String sender = (String) mail.get("sender");

            repository.saveMail(mailId, Map.of(
                    "schedule-date", null,
                    "active", true
            ));
            deliveries.add(new MailDelivery(mailId, receiver, sender, true));
        }
        if (!deliveries.isEmpty()) {
            saveAsync();
        }
        return deliveries;
    }

    @Override
    public int removeExpiredMails() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (String mailId : repository.listMailIds()) {
            Map<String, Object> mail = repository.loadMail(mailId);
            Long expireDate = getLong(mail.get("expire-date"));
            if (expireDate != null && expireDate <= now) {
                repository.deleteMail(mailId);
                removed++;
            }
        }
        if (removed > 0) {
            saveAsync();
        }
        return removed;
    }

    private MailDelivery persistMail(String senderName,
                                     MailCreationSession session,
                                     boolean isAdminMail,
                                     List<ItemStack> items) {
        String normalizedReceiver = normalizeReceiver(session.getReceiver());
        String mailId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Long scheduleDate = session.getScheduleDate();
        Long expireDate = session.getExpireDate();
        if (expireDate == null) {
            int expireAfterDays = config().getInt("settings.admin-mail-expire-days");
            expireDate = now + expireAfterDays * 86400000L;
        }

        boolean active = scheduleDate == null || scheduleDate <= now;

        Map<String, Object> data = new HashMap<>();
        data.put("sender", senderName);
        data.put("receiver", normalizedReceiver);
        data.put("message", session.getMessage());
        data.put("sent-date", now);
        data.put("schedule-date", scheduleDate);
        data.put("expire-date", expireDate);
        data.put("active", active);
        data.put("is-admin-mail", isAdminMail);
        data.put("claimed-players", new ArrayList<String>());

        if (session.getCommandBlock() != null) {
            data.put("command-block", ItemSerialization.serializeItem(session.getCommandBlock()));
            data.put("commands", new ArrayList<>(session.getCommands()));
        } else {
            data.put("command-block", null);
            data.put("commands", new ArrayList<String>());
        }

        repository.saveMail(mailId, data);
        repository.saveMailItems(mailId, items);
        saveAsync();

        return new MailDelivery(mailId, normalizedReceiver, senderName, active);
    }

    private String normalizeReceiver(String receiverSpec) {
        if (receiverSpec == null) {
            return "";
        }
        if (receiverSpec.equalsIgnoreCase("allonline")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(";"));
        }
        return receiverSpec;
    }

    private void validateSession(MailCreationSession session) {
        if (session == null || !session.isComplete()) {
            throw new IllegalArgumentException("Mail session is incomplete");
        }
    }

    private Long getLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private void saveAsync() {
        foliaLib.getScheduler().runAsync(task -> repository.save());
    }

    private org.bukkit.configuration.file.FileConfiguration config() {
        return plugin.getConfig();
    }
}

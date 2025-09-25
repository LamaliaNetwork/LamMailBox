package com.yusaki.lammailbox.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of a mail entry stored by the repository.
 */
public final class MailRecord {
    private final String id;
    private final String sender;
    private final String receiver;
    private final String message;
    private final long sentDate;
    private final Long scheduleDate;
    private final Long expireDate;
    private final boolean active;
    private final boolean adminMail;
    private final List<String> claimedPlayers;
    private final List<String> commands;
    private final String commandBlock;

    private MailRecord(String id,
                      String sender,
                      String receiver,
                      String message,
                      long sentDate,
                      Long scheduleDate,
                      Long expireDate,
                      boolean active,
                      boolean adminMail,
                      List<String> claimedPlayers,
                      List<String> commands,
                      String commandBlock) {
        this.id = id;
        this.sender = sender;
        this.receiver = receiver;
        this.message = message;
        this.sentDate = sentDate;
        this.scheduleDate = scheduleDate;
        this.expireDate = expireDate;
        this.active = active;
        this.adminMail = adminMail;
        this.claimedPlayers = Collections.unmodifiableList(new ArrayList<>(claimedPlayers));
        this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
        this.commandBlock = commandBlock;
    }

    public static Optional<MailRecord> from(String id, Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        String sender = asString(raw.get("sender"));
        String receiver = asString(raw.get("receiver"));
        String message = asString(raw.get("message"));
        long sentDate = asLong(raw.get("sent-date"), 0L);
        Long scheduleDate = asNullableLong(raw.get("schedule-date"));
        Long expireDate = asNullableLong(raw.get("expire-date"));
        boolean active = asBoolean(raw.get("active"), true);
        boolean adminMail = asBoolean(raw.get("is-admin-mail"), false);
        List<String> claimed = asStringList(raw.get("claimed-players"));
        List<String> commands = asStringList(raw.get("commands"));
        String commandBlock = asString(raw.get("command-block"));

        return Optional.of(new MailRecord(
                id,
                sender,
                receiver,
                message,
                sentDate,
                scheduleDate,
                expireDate,
                active,
                adminMail,
                claimed,
                commands,
                commandBlock
        ));
    }

    public String id() {
        return id;
    }

    public String sender() {
        return sender;
    }

    public String receiver() {
        return receiver;
    }

    public String message() {
        return message != null ? message : "";
    }

    public long sentDate() {
        return sentDate;
    }

    public Long scheduleDate() {
        return scheduleDate;
    }

    public Long expireDate() {
        return expireDate;
    }

    public boolean active() {
        return active;
    }

    public boolean isAdminMail() {
        return adminMail;
    }

    public List<String> claimedPlayers() {
        return claimedPlayers;
    }

    public List<String> commands() {
        return commands;
    }

    public String commandBlock() {
        return commandBlock;
    }

    public boolean hasBeenClaimedBy(String playerName) {
        return claimedPlayers.stream()
                .anyMatch(entry -> entry.equalsIgnoreCase(playerName));
    }

    public boolean canBeClaimedBy(String playerName) {
        if (!active || receiver == null || receiver.isEmpty()) {
            return false;
        }
        String lowerTarget = playerName.toLowerCase(Locale.ROOT);
        if (receiver.equalsIgnoreCase("all")) {
            return claimedPlayers.stream().noneMatch(name -> name.equalsIgnoreCase(playerName));
        }
        if (receiver.contains(";")) {
            return getReceiverList().stream().anyMatch(name -> name.equalsIgnoreCase(lowerTarget));
        }
        return receiver.equalsIgnoreCase(playerName);
    }

    public boolean isVisibleToSender(String playerName) {
        if (sender == null) {
            return false;
        }
        return sender.equalsIgnoreCase(playerName);
    }

    public List<String> getReceiverList() {
        if (receiver == null || receiver.isEmpty()) {
            return Collections.emptyList();
        }
        if (receiver.equalsIgnoreCase("all")) {
            return Collections.emptyList();
        }
        if (!receiver.contains(";")) {
            return Collections.singletonList(receiver);
        }
        String[] parts = receiver.split(";");
        List<String> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                list.add(part);
            }
        }
        return list;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString();
        return str.isEmpty() ? null : str;
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private static long asLong(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static Long asNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).isEmpty()) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?>) {
            List<String> list = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                if (entry != null) {
                    String str = Objects.toString(entry, "");
                    if (!str.isEmpty()) {
                        list.add(str);
                    }
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}

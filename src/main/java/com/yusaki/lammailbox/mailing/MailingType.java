package com.yusaki.lammailbox.mailing;

public enum MailingType {
    REPEATING,
    FIRST_JOIN;

    public static MailingType from(String raw, MailingType fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase();
        if ("ONE_TIME".equals(normalized) || "CRON".equals(normalized)) {
            return REPEATING;
        }
        try {
            return MailingType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}

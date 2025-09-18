package com.yusaki.lammailbox.service;

public class MailDelivery {
    private final String mailId;
    private final String receiverSpec;
    private final String senderName;
    private final boolean notifyNow;

    public MailDelivery(String mailId, String receiverSpec, String senderName, boolean notifyNow) {
        this.mailId = mailId;
        this.receiverSpec = receiverSpec;
        this.senderName = senderName;
        this.notifyNow = notifyNow;
    }

    public String getMailId() {
        return mailId;
    }

    public String getReceiverSpec() {
        return receiverSpec;
    }

    public String getSenderName() {
        return senderName;
    }

    public boolean shouldNotifyNow() {
        return notifyNow;
    }
}

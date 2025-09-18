package com.yusaki.lammailbox.service;

import com.yusaki.lammailbox.session.MailCreationSession;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Core business logic around creating, scheduling, and claiming mails.
 */
public interface MailService {
    MailDelivery sendMail(Player sender, MailCreationSession session);

    MailDelivery sendConsoleMail(CommandSender sender, MailCreationSession session);

    boolean deleteMail(String mailId);

    boolean claimMail(Player player, String mailId);

    List<MailDelivery> schedulePendingMails();

    int removeExpiredMails();
}

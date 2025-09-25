package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class LmbTabComplete implements TabCompleter {
    private final LamMailBox plugin;

    public LmbTabComplete(LamMailBox plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        FileConfiguration config = plugin.getConfig();
        if (args.length == 1) {
            String current = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission(config.getString("settings.admin-permission"))) {
                completions.add("send");
            }
            if (sender.hasPermission(config.getString("settings.permissions.open"))) {
                completions.add("view");
            }
            if (sender.hasPermission(config.getString("settings.permissions.view-as"))) {
                completions.add("as");
            }
            return completions.stream()
                    .filter(entry -> entry.toLowerCase().startsWith(current))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            String current = args[1].toLowerCase();
            if (Objects.equals(subCommand, "as")) {
                if (sender.hasPermission(config.getString("settings.permissions.view-as"))) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(current))
                            .collect(Collectors.toList());
                }
            } else if (Objects.equals(subCommand, "send")) {
                if (sender.hasPermission(config.getString("settings.admin-permission"))) {
                    List<String> playerNames = Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(current))
                            .collect(Collectors.toList());

                    if ("all".startsWith(current)) {
                        playerNames.add("all");
                    }
                    if ("allonline".startsWith(current)) {
                        playerNames.add("allonline");
                    }
                    return playerNames;
                }
            } else if (Objects.equals(subCommand, "view")) {
                if (sender instanceof Player && sender.hasPermission(config.getString("settings.permissions.open"))) {
                    return plugin.getMailRepository().listMailIds().stream()
                            .filter(mailId -> mailId.toLowerCase().startsWith(current))
                            .limit(10)
                            .collect(Collectors.toList());
                }
            } else {
                if (sender.hasPermission(config.getString("settings.permissions.open-others"))) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(current))
                            .collect(Collectors.toList());
                }
            }
        }

        return new ArrayList<>();
    }
}

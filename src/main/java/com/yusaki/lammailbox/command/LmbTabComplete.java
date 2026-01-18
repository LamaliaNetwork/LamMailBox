package com.yusaki.lammailbox.command;

import com.yusaki.lammailbox.LamMailBox;
import com.yusaki.lammailbox.config.StorageSettings;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
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
        
        // Handle lmbmigrate command
        if (command.getName().equals("lmbmigrate")) {
            return handleMigrateTabComplete(sender, args, config);
        }
        
        // Handle lmb command (existing logic)
        if (args.length == 1) {
            String current = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission(config.getString("settings.admin-permission"))) {
                completions.add("send");
                completions.add("mailings");
                completions.add("template");
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
            } else if (Objects.equals(subCommand, "template")) {
                if (sender.hasPermission(config.getString("settings.admin-permission"))) {
                    return plugin.getMailingDefinitions().stream()
                            .map(def -> def.id())
                            .filter(id -> id.toLowerCase().startsWith(current))
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

        if (args.length == 3) {
            String subCommand = args[0].toLowerCase();
            String current = args[2].toLowerCase();
            if (Objects.equals(subCommand, "template")) {
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
            }
        }

        return new ArrayList<>();
    }
    
    private List<String> handleMigrateTabComplete(CommandSender sender, String[] args, FileConfiguration config) {
        String permission = config.getString("settings.permissions.migrate", "lammailbox.migrate");
        
        // Check if sender has permission to use the command
        if (!sender.hasPermission(permission)) {
            return new ArrayList<>();
        }
        
        // Get all available storage backend types
        List<String> storageTypes = Arrays.stream(StorageSettings.BackendType.values())
                .map(type -> type.name().toLowerCase())
                .collect(Collectors.toList());
        
        if (args.length == 1) {
            // First argument: source storage type
            String current = args[0].toLowerCase();
            return storageTypes.stream()
                    .filter(type -> type.startsWith(current))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            // Second argument: target storage type (exclude the source type)
            String sourceType = args[0].toLowerCase();
            String current = args[1].toLowerCase();
            return storageTypes.stream()
                    .filter(type -> !type.equals(sourceType)) // Don't suggest the same as source
                    .filter(type -> type.startsWith(current))
                    .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }
}

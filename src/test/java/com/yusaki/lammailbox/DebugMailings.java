package com.yusaki.lammailbox;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;

public class DebugMailings {
    public static void main(String[] args) {
        File file = new File("src/main/resources/mailings.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> mapList = yaml.getMapList("mailings.welcome-bundle.command-items");
        System.out.println("size=" + mapList.size());
        for (Map<?, ?> item : mapList) {
            System.out.println(item.getClass() + " -> " + item);
        }
    }
}

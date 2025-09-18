package com.yusaki.lammailbox.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ItemSerialization {
    private ItemSerialization() {
    }

    public static String serializeItem(ItemStack item) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(item);
            dataOutput.flush();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            Bukkit.getLogger().severe("Error serializing item: " + e.getMessage());
            return "";
        }
    }

    public static ItemStack deserializeItem(String data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack) dataInput.readObject();
        } catch (IOException | ClassNotFoundException e) {
            Bukkit.getLogger().severe("Error deserializing item: " + e.getMessage());
            return null;
        }
    }

    public static List<String> serializeItems(List<ItemStack> items) {
        List<String> serialized = new ArrayList<>();
        for (ItemStack item : items) {
            serialized.add(serializeItem(item));
        }
        return serialized;
    }

    public static List<ItemStack> deserializeItems(List<String> data) {
        List<ItemStack> items = new ArrayList<>();
        for (String value : data) {
            ItemStack item = deserializeItem(value);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }
}

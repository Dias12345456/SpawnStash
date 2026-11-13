package com.spawnstash;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;

public class SpawnStash extends JavaPlugin implements Listener, TabExecutor {

    /** One thing waiting in a stash: either a real item, or a console command to run (e.g. a spawner). */
    private static class StashEntry {
        String name;
        Material icon = Material.SPAWNER;
        int amount = 1;
        String command; // run by console on claim, %player% replaced
        ItemStack item; // OR a literal item
    }

    private static class StashHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, List<StashEntry>> stashes = new HashMap<>();
    private File dataFile;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("stash").setExecutor(this);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------------ helpers

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, key));
    }

    private List<StashEntry> stash(UUID id) {
        return stashes.computeIfAbsent(id, k -> new ArrayList<>());
    }

    private String pretty(String type) {
        String[] parts = type.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private ItemStack icon(StashEntry en) {
        ItemStack it;
        if (en.item != null) {
            it = en.item.clone();
        } else {
            it = new ItemStack(en.icon, Math.max(1, Math.min(en.amount, 64)));
        }
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (en.item == null) meta.setDisplayName(color(en.name));
            List<String> lore = meta.hasLore() && meta.getLore() != null
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&eClick to claim"));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(Arrays.stream(lore).map(this::color).collect(Collectors.toList()));
            it.setItemMeta(meta);
        }
        return it;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        stashes.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players == null) return;
        for (String uuidStr : players.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(uuidStr); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection sec = players.getConfigurationSection(uuidStr);
            if (sec == null) continue;
            List<StashEntry> list = new ArrayList<>();
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                StashEntry en = new StashEntry();
                en.name = s.getString("name", "Item");
                Material m = Material.matchMaterial(s.getString("icon", "SPAWNER"));
                en.icon = m != null ? m : Material.SPAWNER;
                en.amount = s.getInt("amount", 1);
                en.command = s.getString("command");
                en.item = s.getItemStack("item");
                if (en.item == null && en.command == null) continue;
                list.add(en);
            }
            if (!list.isEmpty()) stashes.put(id, list);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<StashEntry>> me : stashes.entrySet()) {
            int i = 0;
            for (StashEntry en : me.getValue()) {
                String p = "players." + me.getKey() + ".e" + (i++);
                y.set(p + ".name", en.name);
                y.set(p + ".icon", en.icon.name());
                y.set(p + ".amount", en.amount);
                if (en.command != null) y.set(p + ".command", en.command);
                if (en.item != null) y.set(p + ".item", en.item);
            }
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ stash logic

    private void addSpawner(OfflinePlayer target, String type, int amount) {
        StashEntry en = new StashEntry();
        en.name = "&b" + pretty(type) + " Spawner &7x" + amount;
        en.icon = Material.SPAWNER;
        en.amount = amount;
        en.command = getConfig().getString("spawner-command", "donutspawners:givespawner %player% %type% %amount%")
                .replace("%type%", type)
                .replace("%amount%", String.valueOf(amount));
        stash(target.getUniqueId()).add(en);
        save();
        notifyReceived(target, en.name);
    }

    private void addItem(OfflinePlayer target, ItemStack item) {
        StashEntry en = new StashEntry();
        en.item = item;
        en.amount = item.getAmount();
        en.name = item.getType().name();
        stash(target.getUniqueId()).add(en);
        save();
        notifyReceived(target, item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName() : pretty(item.getType().name()) + " x" + item.getAmount());
    }

    private void notifyReceived(OfflinePlayer target, String name) {
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(msg("received").replace("%name%", color(name)));
        }
    }

    /** @return true if the entry was fully delivered and removed */
    private boolean claim(Player p, StashEntry en) {
        List<StashEntry> list = stash(p.getUniqueId());
        if (en.item != null) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(en.item.clone());
            if (!left.isEmpty()) {
                en.item.setAmount(left.values().iterator().next().getAmount());
                save();
                p.sendMessage(msg("inventory-full"));
                return false;
            }
        } else {
            if (p.getInventory().firstEmpty() == -1) {
                p.sendMessage(msg("inventory-full"));
                return false;
            }
            String cmd = en.command.replace("%player%", p.getName());
            boolean ok = false;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception ex) {
                getLogger().warning("Error running '" + cmd + "': " + ex.getMessage());
            }
            if (!ok) {
                p.sendMessage(msg("command-failed"));
                getLogger().warning("Stash command failed: " + cmd);
                return false;
            }
        }
        list.remove(en);
        save();
        p.sendMessage(msg("claimed").replace("%name%", color(en.item != null
                ? pretty(en.item.getType().name()) + " x" + en.amount : en.name)));
        return true;
    }

    private void claimAll(Player p) {
        for (StashEntry en : new ArrayList<>(stash(p.getUniqueId()))) {
            if (!claim(p, en)) break;
        }
    }

    // ------------------------------------------------------------------ GUI

    private void open(Player p) {
        List<StashEntry> list = stash(p.getUniqueId());
        if (list.isEmpty()) {
            p.closeInventory();
            p.sendMessage(msg("empty"));
            return;
        }
        StashHolder holder = new StashHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, color(getConfig().getString("gui-title", "&8Spawn Stash")));
        holder.inv = inv;

        for (int i = 0; i < Math.min(45, list.size()); i++) {
            inv.setItem(i, icon(list.get(i)));
        }
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        String more = list.size() > 45 ? "&7(+" + (list.size() - 45) + " more after these)" : "&7Claim everything at once";
        inv.setItem(49, button(Material.EMERALD, "&a&lClaim All", more));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof StashHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        List<StashEntry> list = stash(p.getUniqueId());

        if (slot == 49) {
            claimAll(p);
        } else if (slot >= 0 && slot < 45 && slot < list.size()) {
            claim(p, list.get(slot));
        } else {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> open(p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof StashHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int n = stash(p.getUniqueId()).size();
            if (p.isOnline() && n > 0) {
                p.sendMessage(msg("join-notify").replace("%count%", String.valueOf(n)));
            }
        }, 40L);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player)) { s.sendMessage(msg("players-only")); return true; }
            if (!s.hasPermission("spawnstash.use")) { s.sendMessage(msg("no-permission")); return true; }
            open((Player) s);
            return true;
        }

        if (!s.hasPermission("spawnstash.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                s.sendMessage(msg("reloaded"));
                return true;

            case "give": {
                if (a.length < 3) { s.sendMessage(msg("usage-admin")); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                if (!t.isOnline() && !t.hasPlayedBefore()) { s.sendMessage(msg("player-not-found")); return true; }
                int amount = 1;
                if (a.length > 3) {
                    try { amount = Math.max(1, Integer.parseInt(a[3])); }
                    catch (NumberFormatException ex) { s.sendMessage(msg("usage-admin")); return true; }
                }
                addSpawner(t, a[2], amount);
                s.sendMessage(msg("gave")
                        .replace("%name%", pretty(a[2]) + " Spawner x" + amount)
                        .replace("%player%", a[1]));
                return true;
            }

            case "additem": {
                if (!(s instanceof Player)) { s.sendMessage(msg("players-only")); return true; }
                if (a.length < 2) { s.sendMessage(msg("usage-admin")); return true; }
                Player admin = (Player) s;
                ItemStack hand = admin.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() == Material.AIR) { s.sendMessage(color("&cHold an item first.")); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                if (!t.isOnline() && !t.hasPlayedBefore()) { s.sendMessage(msg("player-not-found")); return true; }
                addItem(t, hand.clone());
                admin.getInventory().setItemInMainHand(null);
                s.sendMessage(msg("gave")
                        .replace("%name%", pretty(hand.getType().name()) + " x" + hand.getAmount())
                        .replace("%player%", a[1]));
                return true;
            }

            default:
                s.sendMessage(msg("usage-admin"));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("spawnstash.admin")) return out;
        if (a.length == 1) {
            for (String o : Arrays.asList("give", "additem", "reload"))
                if (o.startsWith(a[0].toLowerCase())) out.add(o);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("additem"))) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}

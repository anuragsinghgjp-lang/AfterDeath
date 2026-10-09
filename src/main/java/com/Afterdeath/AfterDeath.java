package com.afterdeath;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class AfterDeath extends JavaPlugin implements Listener {
    private NamespacedKey wandKey;
    private NamespacedKey headTargetKey;
    private final Set<UUID> eliminated = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "selection_wand");
        headTargetKey = new NamespacedKey(this, "eliminated_player");
        for (String value : getConfig().getStringList("eliminated-players")) {
            try { eliminated.add(UUID.fromString(value)); }
            catch (IllegalArgumentException ignored) { getLogger().warning("Ignoring invalid eliminated UUID: " + value); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AfterDeath enabled. Configure the world with /afterdeath setworld.");
    }

    @Override
    public void onDisable() {
        saveEliminated();
    }

    private void saveEliminated() {
        getConfig().set("eliminated-players", eliminated.stream().map(UUID::toString).toList());
        saveConfig();
    }

    private String msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&cAfterDeath&8] &r");
        return color(prefix + getConfig().getString("messages." + path, "&cMissing message: " + path));
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }

    private void send(CommandSender sender, String path) { sender.sendMessage(msg(path)); }

    private void send(CommandSender sender, String path, String key, String value) {
        sender.sendMessage(msg(path).replace(key, value));
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission("afterdeath.admin")) return true;
        send(sender, "no-permission");
        return false;
    }

    private String headstealWorldName() { return getConfig().getString("headsteal-world", "Headsteal"); }
    private boolean isHeadstealWorld(World world) { return world != null && world.getName().equalsIgnoreCase(headstealWorldName()); }
    private boolean isEliminated(Player player) { return eliminated.contains(player.getUniqueId()) && !player.hasPermission("afterdeath.bypass"); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { send(sender, "usage"); return true; }
        switch (args[0].toLowerCase()) {
            case "wand" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) { send(sender, "player-only"); return true; }
                ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                ItemMeta meta = wand.getItemMeta();
                meta.setDisplayName(color("&c&lAfterDeath &7| &fRegion Wand"));
                meta.setLore(java.util.List.of(color("&7Left-click: &fPosition 1"), color("&7Right-click: &fPosition 2")));
                meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
                wand.setItemMeta(meta);
                player.getInventory().addItem(wand).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                send(player, "wand-given");
            }
            case "setworld" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) { send(sender, "player-only"); return true; }
                String boxWorld = getConfig().getString("ban-box.world", "");
                if (!boxWorld.isBlank() && !boxWorld.equalsIgnoreCase(player.getWorld().getName())) {
                    sender.sendMessage(color("&cYour selected Ban Box is in " + boxWorld + ". Select a new box in this world before changing the Headsteal world."));
                    return true;
                }
                getConfig().set("headsteal-world", player.getWorld().getName()); saveConfig();
                send(sender, "world-set", "%world%", player.getWorld().getName());
            }
            case "setspawn" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) { send(sender, "player-only"); return true; }
                if (!isHeadstealWorld(player.getWorld())) {
                    sender.sendMessage(color("&cStand in the configured Headsteal world first. Use /afterdeath setworld if needed.")); return true;
                }
                getConfig().set("headsteal-spawn", serialize(player.getLocation())); saveConfig(); send(sender, "spawn-set");
            }
            case "reload" -> {
                if (!isAdmin(sender)) return true;
                reloadConfig(); send(sender, "reload");
            }
            case "revive" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 2) { send(sender, "usage"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                UUID targetId = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                if (!eliminated.remove(targetId)) { send(sender, "not-eliminated"); return true; }
                saveEliminated();
                if (target != null) {
                    Location destination = target.getWorld().getSpawnLocation();
                    if (isHeadstealWorld(target.getWorld())) destination = getHeadstealSpawn(target.getWorld());
                    target.teleport(destination);
                    target.sendMessage(color("&8[&cAfterDeath&8] &aAn admin revived you."));
                }
                send(sender, "revived", "%player%", args[1]);
            }
            case "status" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 2) { send(sender, "usage"); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                String path = eliminated.contains(target.getUniqueId()) ? "status-eliminated" : "status-alive";
                send(sender, path, "%player%", args[1]);
            }
            default -> send(sender, "usage");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (!isHeadstealWorld(victim.getWorld()) || killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        // An already eliminated player must not generate duplicate heads by dying again in the box.
        if (eliminated.contains(victim.getUniqueId())) return;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(victim);
        meta.setDisplayName(color("&c&l" + victim.getName() + "'s Head"));
        meta.setLore(java.util.List.of(color("&7Place this head to revive &f" + victim.getName()), color("&8AfterDeath")));
        meta.getPersistentDataContainer().set(headTargetKey, PersistentDataType.STRING, victim.getUniqueId().toString());
        head.setItemMeta(meta);
        event.getDrops().removeIf(item -> item.getType() == Material.PLAYER_HEAD);
        event.getDrops().add(head);
        eliminated.add(victim.getUniqueId());
        saveEliminated();
        killer.sendMessage(msg("head-dropped").replace("%player%", victim.getName()));
        victim.sendMessage(msg("eliminated"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isEliminated(player) || !isHeadstealWorld(player.getWorld())) return;
        Location box = getBoxCenter();
        if (box != null) event.setRespawnLocation(box);
        else event.setRespawnLocation(getHeadstealSpawn(player.getWorld()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null || event.getItem() == null) return;
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) return;
        if (!event.getPlayer().hasPermission("afterdeath.admin")) return;
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        String pos = event.getAction().name().contains("LEFT_CLICK") ? "pos1" : event.getAction().name().contains("RIGHT_CLICK") ? "pos2" : "";
        if (pos.isEmpty()) return;
        String base = "ban-box.";
        String configuredWorld = headstealWorldName();
        if (!block.getWorld().getName().equalsIgnoreCase(configuredWorld)) {
            event.getPlayer().sendMessage(color("&cSelect Ban Box positions inside the configured Headsteal world: " + configuredWorld));
            return;
        }
        String existingWorld = getConfig().getString(base + "world", "");
        if (!existingWorld.isBlank() && !existingWorld.equalsIgnoreCase(block.getWorld().getName())) {
            getConfig().set(base + "pos1", null);
            getConfig().set(base + "pos2", null);
        }
        getConfig().set(base + "world", block.getWorld().getName());
        getConfig().set(base + pos, block.getX() + "," + block.getY() + "," + block.getZ());
        saveConfig();
        send(event.getPlayer(), pos, "%x%, %y%, %z%", block.getX() + ", " + block.getY() + ", " + block.getZ());
        int[] first = parsePos(getConfig().getString(base + "pos1"));
        int[] second = parsePos(getConfig().getString(base + "pos2"));
        if (first != null && second != null) event.getPlayer().sendMessage(color("&aBan Box region configured. Make sure the box interior is safe and enclosed."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeadPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!item.hasItemMeta()) return;
        String targetString = item.getItemMeta().getPersistentDataContainer().get(headTargetKey, PersistentDataType.STRING);
        if (targetString == null) return;
        if (!isHeadstealWorld(event.getBlockPlaced().getWorld())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&8[&cAfterDeath&8] &cThis head must be placed in the Headsteal world."));
            return;
        }
        UUID targetId;
        try { targetId = UUID.fromString(targetString); }
        catch (IllegalArgumentException ignored) { return; }
        if (!eliminated.contains(targetId)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&8[&cAfterDeath&8] &cThis player has already been revived."));
            return;
        }
        BlockState state = event.getBlockPlaced().getState();
        if (state instanceof Skull skull) {
            skull.getPersistentDataContainer().set(headTargetKey, PersistentDataType.STRING, targetId.toString());
            skull.update(true, false);
        }
        eliminated.remove(targetId);
        saveEliminated();
        Location destination = event.getBlockPlaced().getLocation().add(0.5, 1.0, 0.5);
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            Bukkit.getScheduler().runTask(this, () -> {
                target.teleport(destination);
                target.sendMessage(msg("revived-by-head"));
            });
        } else {
            getConfig().set("pending-revives." + targetId, serialize(destination));
            saveConfig();
        }
        event.getPlayer().sendMessage(color("&8[&cAfterDeath&8] &aHead placed. " + (target == null ? "Player will be teleported here when they next join." : "Player revived!")));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeadBreak(BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Skull skull)) return;
        String target = skull.getPersistentDataContainer().get(headTargetKey, PersistentDataType.STRING);
        if (target == null) return;
        event.setDropItems(false);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        try { meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(target))); }
        catch (IllegalArgumentException ignored) { }
        meta.setDisplayName(color("&c&lUnclaimed Head"));
        meta.getPersistentDataContainer().set(headTargetKey, PersistentDataType.STRING, target);
        item.setItemMeta(meta);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isEliminated(player) || !isHeadstealWorld(event.getTo() == null ? null : event.getTo().getWorld())) return;
        Location box = getBoxCenter();
        if (box == null) {
            player.sendMessage(msg("box-incomplete"));
            event.setCancelled(true);
        } else event.setTo(box);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld().equals(event.getTo().getWorld()) && event.getFrom().getBlockX() == event.getTo().getBlockX() && event.getFrom().getBlockY() == event.getTo().getBlockY() && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        Player player = event.getPlayer();
        if (!isEliminated(player) || !isHeadstealWorld(event.getTo().getWorld())) return;
        Location box = getBoxCenter();
        if (box == null) { event.setCancelled(true); return; }
        if (!isInsideBox(event.getTo())) {
            event.setTo(box);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String pending = getConfig().getString("pending-revives." + player.getUniqueId());
        if (pending != null && !pending.isBlank() && !pending.equalsIgnoreCase("null")) {
            Location destination = deserialize(pending);
            getConfig().set("pending-revives." + player.getUniqueId(), null);
            saveConfig();
            if (destination != null) Bukkit.getScheduler().runTask(this, () -> {
                player.teleport(destination);
                player.sendMessage(msg("revived-by-head"));
            });
            return;
        }
        if (isEliminated(player) && isHeadstealWorld(player.getWorld())) {
            Bukkit.getScheduler().runTask(this, () -> {
                Location box = getBoxCenter();
                player.teleport(box != null ? box : getHeadstealSpawn(player.getWorld()));
            });
        }
    }

    private boolean isInsideBox(Location location) {
        if (location == null || location.getWorld() == null) return false;
        String boxWorld = getConfig().getString("ban-box.world", "");
        if (!location.getWorld().getName().equalsIgnoreCase(boxWorld)) return false;
        int[] p1 = parsePos(getConfig().getString("ban-box.pos1"));
        int[] p2 = parsePos(getConfig().getString("ban-box.pos2"));
        if (p1 == null || p2 == null) return false;
        return location.getBlockX() >= Math.min(p1[0], p2[0]) && location.getBlockX() <= Math.max(p1[0], p2[0])
                && location.getBlockY() >= Math.min(p1[1], p2[1]) && location.getBlockY() <= Math.max(p1[1], p2[1])
                && location.getBlockZ() >= Math.min(p1[2], p2[2]) && location.getBlockZ() <= Math.max(p1[2], p2[2]);
    }

    private Location getBoxCenter() {
        String worldName = getConfig().getString("ban-box.world", "");
        World world = Bukkit.getWorld(worldName);
        int[] p1 = parsePos(getConfig().getString("ban-box.pos1"));
        int[] p2 = parsePos(getConfig().getString("ban-box.pos2"));
        if (world == null || p1 == null || p2 == null || !world.getName().equalsIgnoreCase(headstealWorldName())) return null;
        double x = (Math.min(p1[0], p2[0]) + Math.max(p1[0], p2[0])) / 2.0 + 0.5;
        double y = Math.min(p1[1], p2[1]) + 1.0;
        double z = (Math.min(p1[2], p2[2]) + Math.max(p1[2], p2[2])) / 2.0 + 0.5;
        return new Location(world, x, y, z);
    }

    private int[] parsePos(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null")) return null;
        String[] split = value.split(",");
        if (split.length != 3) return null;
        try { return new int[]{Integer.parseInt(split[0].trim()), Integer.parseInt(split[1].trim()), Integer.parseInt(split[2].trim())}; }
        catch (NumberFormatException ignored) { return null; }
    }

    private Location getHeadstealSpawn(World fallbackWorld) {
        String value = getConfig().getString("headsteal-spawn");
        if (value != null && !value.isBlank() && !value.equalsIgnoreCase("null")) {
            String[] parts = value.split(",");
            if (parts.length == 6) {
                World world = Bukkit.getWorld(parts[0]);
                try {
                    if (world != null) return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
                } catch (NumberFormatException ignored) { }
            }
        }
        return fallbackWorld.getSpawnLocation();
    }

    private String serialize(Location location) {
        return location.getWorld().getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getYaw() + "," + location.getPitch();
    }

    private Location deserialize(String value) {
        String[] parts = value.split(",");
        if (parts.length != 6) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException ignored) { return null; }
    }
}

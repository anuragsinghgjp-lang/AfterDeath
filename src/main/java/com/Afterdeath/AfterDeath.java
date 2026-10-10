package com.afterdeath;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AfterDeath extends JavaPlugin implements Listener {

    private NamespacedKey wandKey;
    private NamespacedKey headKey;

    private final Set<UUID> eliminated = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        wandKey = new NamespacedKey(this, "selection_wand");
        headKey = new NamespacedKey(this, "eliminated_player");

        loadEliminated();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("AfterDeath enabled.");
    }

    @Override
    public void onDisable() {
        saveEliminated();
    }

    // --------------------------------------------------
    // STORAGE
    // --------------------------------------------------

    private void loadEliminated() {
        eliminated.clear();

        for (String value : getConfig().getStringList("eliminated-players")) {
            try {
                eliminated.add(UUID.fromString(value));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Invalid eliminated UUID: " + value);
            }
        }
    }

    private void saveEliminated() {
        getConfig().set(
                "eliminated-players",
                new ArrayList<>(eliminated.stream()
                        .map(UUID::toString).toList())
        );
        saveConfig();
    }

    private void setEliminated(UUID uuid, boolean value) {
        if (value) {
            eliminated.add(uuid);
        } else {
            eliminated.remove(uuid);
        }
        saveEliminated();
    }

    private String serialize(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }

        return loc.getWorld().getName() + ","
                + loc.getX() + ","
                + loc.getY() + ","
                + loc.getZ() + ","
                + loc.getYaw() + ","
                + loc.getPitch();
    }

    private Location deserialize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            String[] p = value.split(",");
            if (p.length < 4) return null;

            World world = Bukkit.getWorld(p[0]);
            if (world == null) return null;

            float yaw = p.length > 4 ? Float.parseFloat(p[4]) : 0;
            float pitch = p.length > 5 ? Float.parseFloat(p[5]) : 0;

            return new Location(
                    world,
                    Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]),
                    yaw,
                    pitch
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int[] parsePos(String value) {
        if (value == null || value.isBlank()) return null;

        try {
            String[] p = value.split(",");
            if (p.length != 3) return null;

            return new int[]{
                    Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim())
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // --------------------------------------------------
    // MESSAGES
    // --------------------------------------------------

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes(
                '&', text == null ? "" : text
        );
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(color("&8[&cAfterDeath&8] &r" + text));
    }

    private void configMessage(CommandSender sender, String key) {
        String prefix = getConfig().getString(
                "messages.prefix", "&8[&cAfterDeath&8] &r"
        );

        String body = getConfig().getString(
                "messages." + key, "&c" + key
        );

        sender.sendMessage(color(prefix + body));
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission("afterdeath.admin")) return true;

        configMessage(sender, "no-permission");
        return false;
    }

    private boolean isEliminated(Player player) {
        return eliminated.contains(player.getUniqueId())
                && !player.hasPermission("afterdeath.bypass");
    }

    private String worldName() {
        return getConfig().getString("headsteal-world", "Headsteal");
    }

    private boolean isHeadstealWorld(World world) {
        return world != null
                && world.getName().equalsIgnoreCase(worldName());
    }

    // --------------------------------------------------
    // BAN BOX
    // --------------------------------------------------

    private String activeBoxPath() {
        return "ban-boxes."
                + getConfig().getString("ban-box.name", "main");
    }

    private String boxWorldName() {
        String path = activeBoxPath();
        return getConfig().getString(
                path + ".world",
                getConfig().getString("ban-box.world", "")
        );
    }

    private int[] boxPosition(String key) {
        String path = activeBoxPath();

        int[] result = parsePos(getConfig().getString(path + "." + key));

        if (result == null) {
            result = parsePos(getConfig().getString("ban-box." + key));
        }

        return result;
    }

    private Location getBoxCenter() {
        String world = boxWorldName();
        int[] p1 = boxPosition("pos1");
        int[] p2 = boxPosition("pos2");

        if (world == null || world.isBlank() || p1 == null || p2 == null) {
            return null;
        }

        World w = Bukkit.getWorld(world);
        if (w == null) return null;

        return new Location(
                w,
                (Math.min(p1[0], p2[0]) + Math.max(p1[0], p2[0])) / 2.0 + 0.5,
                Math.max(p1[1], p2[1]) + 1.0,
                (Math.min(p1[2], p2[2]) + Math.max(p1[2], p2[2])) / 2.0 + 0.5
        );
    }

    private boolean isInsideBanBox(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        String world = boxWorldName();
        if (world == null
                || !loc.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }

        int[] p1 = boxPosition("pos1");
        int[] p2 = boxPosition("pos2");

        if (p1 == null || p2 == null) return false;

        return loc.getBlockX() >= Math.min(p1[0], p2[0])
                && loc.getBlockX() <= Math.max(p1[0], p2[0])
                && loc.getBlockY() >= Math.min(p1[1], p2[1])
                && loc.getBlockY() <= Math.max(p1[1], p2[1]) + 2
                && loc.getBlockZ() >= Math.min(p1[2], p2[2])
                && loc.getBlockZ() <= Math.max(p1[2], p2[2]);
    }

    private Location getHeadstealSpawn(World world) {
        if (world == null) return null;

        Location loc = deserialize(getConfig().getString("headsteal-spawn"));

        if (loc != null && loc.getWorld() != null
                && loc.getWorld().getName().equalsIgnoreCase(world.getName())) {
            return loc;
        }

        return world.getSpawnLocation();
    }

    // --------------------------------------------------
    // COMMANDS
    // --------------------------------------------------

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (command.getName().equalsIgnoreCase("bb")) {
            return handleBanBox(sender, args);
        }

        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "wand" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    configMessage(sender, "player-only");
                    return true;
                }
                giveWand(player);
            }

            case "setworld" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    configMessage(sender, "player-only");
                    return true;
                }

                getConfig().set("headsteal-world", player.getWorld().getName());
                saveConfig();
                message(sender, "&aHeadsteal world set to &e"
                        + player.getWorld().getName());
            }

            case "setspawn" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    configMessage(sender, "player-only");
                    return true;
                }

                if (!isHeadstealWorld(player.getWorld())) {
                    message(sender, "&cPehle configured Headsteal world mein jao.");
                    return true;
                }

                getConfig().set("headsteal-spawn", serialize(player.getLocation()));
                saveConfig();
                message(sender, "&aHeadsteal spawn saved.");
            }

            case "reload" -> {
                if (!isAdmin(sender)) return true;
                reloadConfig();
                loadEliminated();
                message(sender, "&aConfiguration reloaded.");
            }

            case "revive" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 2) {
                    message(sender, "&cUsage: /afterdeath revive <player>");
                    return true;
                }

                OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                UUID uuid = offline.getUniqueId();

                if (!eliminated.contains(uuid)) {
                    message(sender, "&cThat player is not eliminated.");
                    return true;
                }

                eliminated.remove(uuid);

                Player target = Bukkit.getPlayer(uuid);
                Location destination = getHeadstealSpawn(
                        Bukkit.getWorld(worldName())
                );

                if (target != null) {
                    clearPendingRevive(uuid);
                    if (destination == null) {
                        destination = target.getWorld().getSpawnLocation();
                    }
                    revivePlayer(target, destination);
                } else if (destination != null) {
                    getConfig().set(
                            "pending-revives." + uuid,
                            serialize(destination)
                    );
                }

                saveEliminated();
                message(sender, "&aPlayer &e" + args[1] + " &arevived.");
            }

            case "status" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 2) {
                    message(sender, "&cUsage: /afterdeath status <player>");
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                message(sender, eliminated.contains(target.getUniqueId())
                        ? "&c" + args[1] + " is eliminated."
                        : "&a" + args[1] + " is alive.");
            }

            default -> usage(sender);
        }

        return true;
    }

    private void usage(CommandSender sender) {
        message(sender, "&e/afterdeath wand");
        message(sender, "&e/afterdeath setworld");
        message(sender, "&e/afterdeath setspawn");
        message(sender, "&e/afterdeath reload");
        message(sender, "&e/afterdeath revive <player>");
        message(sender, "&e/afterdeath status <player>");
        message(sender, "&e/bb wand, set <name>, delete <name>");
    }

    private void clearPendingRevive(UUID uuid) {
        getConfig().set("pending-revives." + uuid, null);
        saveConfig();
    }

    private boolean handleBanBox(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) return true;

        if (args.length == 0) {
            message(sender, "&e/bb wand");
            message(sender, "&e/bb set <name>");
            message(sender, "&e/bb delete <name>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "wand" -> {
                if (!(sender instanceof Player player)) {
                    configMessage(sender, "player-only");
                    return true;
                }
                giveWand(player);
            }

            case "set" -> {
                if (!(sender instanceof Player player)) {
                    configMessage(sender, "player-only");
                    return true;
                }
                if (args.length < 2) {
                    message(sender, "&cUsage: /bb set <name>");
                    return true;
                }

                int[] p1 = parsePos(getConfig().getString("ban-box.pos1"));
                int[] p2 = parsePos(getConfig().getString("ban-box.pos2"));
                String world = getConfig().getString("ban-box.world", "");

                if (p1 == null || p2 == null || world.isBlank()) {
                    message(sender, "&cUse /bb wand and select both positions first.");
                    return true;
                }

                if (!player.getWorld().getName().equalsIgnoreCase(world)) {
                    message(sender, "&cYou must be in the selected world.");
                    return true;
                }

                String name = args[1].toLowerCase();
                String path = "ban-boxes." + name;

                getConfig().set(path + ".world", world);
                getConfig().set(path + ".pos1",
                        p1[0] + "," + p1[1] + "," + p1[2]);
                getConfig().set(path + ".pos2",
                        p2[0] + "," + p2[1] + "," + p2[2]);
                getConfig().set("ban-box.name", name);
                saveConfig();

                message(sender, "&aBan Box &e" + name + " &asaved and activated.");
            }

            case "delete" -> {
                if (args.length < 2) {
                    message(sender, "&cUsage: /bb delete <name>");
                    return true;
                }

                String name = args[1].toLowerCase();
                String path = "ban-boxes." + name;

                if (!getConfig().contains(path)) {
                    message(sender, "&cBan Box not found.");
                    return true;
                }

                getConfig().set(path, null);

                if (name.equalsIgnoreCase(
                        getConfig().getString("ban-box.name", "main"))) {
                    getConfig().set("ban-box.name", "main");
                }

                saveConfig();
                message(sender, "&aBan Box deleted.");
            }

            default -> message(sender, "&e/bb wand, set <name>, delete <name>");
        }

        return true;
    }

    // --------------------------------------------------
    // WAND
    // --------------------------------------------------

    private void giveWand(Player player) {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) return;

        meta.setDisplayName(color("&c&lAfterDeath &7| &fBan Box Wand"));
        meta.setLore(List.of(
                color("&7Left-click: Position 1"),
                color("&7Right-click: Position 2")
        ));
        meta.getPersistentDataContainer().set(
                wandKey, PersistentDataType.BYTE, (byte) 1
        );

        item.setItemMeta(meta);

        player.getInventory().addItem(item).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left)
        );

        message(player, "&aBan Box wand received.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getItem() == null || event.getClickedBlock() == null) return;

        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(
                wandKey, PersistentDataType.BYTE)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("afterdeath.admin")) return;

        event.setCancelled(true);

        Block block = event.getClickedBlock();
        String action = event.getAction().name();

        String key;
        if (action.equals("LEFT_CLICK_BLOCK")) {
            key = "pos1";
        } else if (action.equals("RIGHT_CLICK_BLOCK")) {
            key = "pos2";
        } else {
            return;
        }

        getConfig().set("ban-box." + key,
                block.getX() + "," + block.getY() + "," + block.getZ());
        getConfig().set("ban-box.world", block.getWorld().getName());
        saveConfig();

        message(player, "&a" + key + " set: &e"
                + block.getX() + ", " + block.getY() + ", " + block.getZ());
    }

    // --------------------------------------------------
    // PLAYER DEATH AND HEADSTEAL
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // Only PvP deaths in the configured Headsteal world count.
        if (!isHeadstealWorld(victim.getWorld())) return;
        if (victim.getKiller() == null) return;

        UUID uuid = victim.getUniqueId();
        setEliminated(uuid, true);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (meta != null) {
            meta.setOwningPlayer(victim);
            meta.setDisplayName(color("&c&l" + victim.getName() + "'s Head"));
            meta.setLore(List.of(
                    color("&7Right-click to revive its owner."),
                    color("&eOwner: " + uuid)
            ));
            meta.getPersistentDataContainer().set(
                    headKey, PersistentDataType.STRING, uuid.toString()
            );
            head.setItemMeta(meta);
        }

        event.getDrops().add(head);
        event.setKeepInventory(false);

        victim.sendMessage(color("&cYou have been eliminated!"));
        victim.getKiller().sendMessage(color(
                "&cYou eliminated &e" + victim.getName() + "&c. Their head dropped!"
        ));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        if (!eliminated.contains(player.getUniqueId())) return;

        Location box = getBoxCenter();

        if (box != null) {
            event.setRespawnLocation(box);
        } else {
            getLogger().warning(
                    "Ban Box is not configured. Set it using /bb wand and /bb set <name>."
            );
        }
    }

    // --------------------------------------------------
    // REVIVAL
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeadUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player reviver = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.PLAYER_HEAD) return;
        if (!event.getAction().name().contains("RIGHT_CLICK")) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String raw = meta.getPersistentDataContainer().get(
                headKey, PersistentDataType.STRING
        );

        if (raw == null) return;

        UUID owner;

        try {
            owner = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return;
        }

        event.setCancelled(true);

        if (!eliminated.contains(owner)) {
            message(reviver, "&cThis player's head has already been used.");
            return;
        }

        eliminated.remove(owner);

        Player target = Bukkit.getPlayer(owner);
        Location destination = getHeadstealSpawn(
                Bukkit.getWorld(worldName())
        );

        if (target != null) {
            clearPendingRevive(owner);

            if (destination == null) {
                destination = target.getWorld().getSpawnLocation();
            }

            revivePlayer(target, destination);
        } else if (destination != null) {
            getConfig().set(
                    "pending-revives." + owner,
                    serialize(destination)
            );
        }

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            reviver.getInventory().setItemInMainHand(null);
        }

        saveEliminated();

        message(reviver, "&aYou used the head to revive its owner!");
    }

    private void revivePlayer(Player player, Location destination) {
        if (destination != null) {
            player.teleport(destination);
        }

        player.setFireTicks(0);
        player.setFallDistance(0);

        playRevivalEffects(player);

        player.sendTitle(
                color("&a&lREVIVED"),
                color("&7You have returned to life!"),
                10, 50, 15
        );

        message(player, "&aYou have been revived!");
    }

    private void playRevivalEffects(Player player) {
        player.getWorld().playSound(
                player.getLocation(),
                Sound.ITEM_TOTEM_USE,
                1.0f,
                1.0f
        );

        player.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1, 0),
                100,
                0.6,
                0.8,
                0.6,
                0.1
        );
    }

    // --------------------------------------------------
    // OFFLINE REVIVAL AND JOIN
    // --------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        String path = "pending-revives." + uuid;
        Location pending = deserialize(getConfig().getString(path));

        if (pending != null && !eliminated.contains(uuid)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;

                player.teleport(pending);

                getConfig().set(path, null);
                saveConfig();

                playRevivalEffects(player);
                message(player, "&aYour revival is complete!");
            });
        }

        if (eliminated.contains(uuid)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline() || !eliminated.contains(uuid)) return;

                Location box = getBoxCenter();

                if (box != null) {
                    player.teleport(box);
                }

                message(player, "&cYou are eliminated and remain in the Ban Box.");
            }, 1L);
        }
    }

    // --------------------------------------------------
    // ELIMINATED PLAYER RESTRICTIONS
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!isEliminated(player)) return;
        if (event.getTo() == null) return;

        // Ignore head rotation; check actual position changes.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        if (!isInsideBanBox(event.getTo())) {
            Location box = getBoxCenter();

            if (box != null) {
                event.setTo(box);
            } else {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!isEliminated(event.getPlayer())) return;

        Location destination = event.getTo();

        if (destination == null || isInsideBanBox(destination)) return;

        // Prevent teleporting outside the Ban Box.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim
                && isEliminated(victim)) {
            event.setCancelled(true);
        }

        if (event.getDamager() instanceof Player attacker
                && isEliminated(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isEliminated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isEliminated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isEliminated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && isEliminated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && isEliminated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && isEliminated(player)) {
            event.setCancelled(true);
        }
    }
}

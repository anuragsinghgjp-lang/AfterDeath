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
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
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

        getLogger().info("AfterDeath enabled successfully.");
    }

    @Override
    public void onDisable() {
        saveEliminated();
    }

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
                eliminated.stream().map(UUID::toString).toList()
        );

        saveConfig();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(color("&8[&cAfterDeath&8] &r" + text));
    }

    private void configMessage(CommandSender sender, String key) {
        String prefix = getConfig().getString(
                "messages.prefix",
                "&8[&cAfterDeath&8] &r"
        );

        String body = getConfig().getString(
                "messages." + key,
                "&cMessage: " + key
        );

        sender.sendMessage(color(prefix + body));
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission("afterdeath.admin")) {
            return true;
        }

        configMessage(sender, "no-permission");
        return false;
    }

    private boolean isEliminated(Player player) {
        return eliminated.contains(player.getUniqueId())
                && !player.hasPermission("afterdeath.bypass");
    }

    private String headstealWorldName() {
        return getConfig().getString("headsteal-world", "Headsteal");
    }

    private boolean isHeadstealWorld(World world) {
        return world != null
                && world.getName().equalsIgnoreCase(headstealWorldName());
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
            return handleBanBoxCommand(sender, args);
        }

        if (args.length == 0) {
            showUsage(sender);
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
                    message(sender,
                            "&cStand inside the configured Headsteal world first.");
                    return true;
                }

                getConfig().set(
                        "headsteal-spawn",
                        serialize(player.getLocation())
                );

                saveConfig();

                configMessage(sender, "spawn-set");
            }

            case "reload" -> {
                if (!isAdmin(sender)) return true;

                reloadConfig();
                loadEliminated();

                configMessage(sender, "reload");
            }

            case "revive" -> {
                if (!isAdmin(sender)) return true;

                if (args.length < 2) {
                    showUsage(sender);
                    return true;
                }

                OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                UUID targetId = offline.getUniqueId();

                if (!eliminated.remove(targetId)) {
                    configMessage(sender, "not-eliminated");
                    return true;
                }

                saveEliminated();

                Player target = Bukkit.getPlayer(targetId);

                if (target != null) {
                    clearPendingRevive(targetId);

                    Location destination = getHeadstealSpawn(
                            Bukkit.getWorld(headstealWorldName())
                    );

                    revivePlayer(target, destination);
                } else {
                    World world = Bukkit.getWorld(headstealWorldName());

                    if (world != null) {
                        getConfig().set(
                                "pending-revives." + targetId,
                                serialize(getHeadstealSpawn(world))
                        );

                        saveConfig();
                    }
                }

                message(sender, "&aPlayer &e" + args[1] + " &ahas been revived.");
            }

            case "status" -> {
                if (!isAdmin(sender)) return true;

                if (args.length < 2) {
                    showUsage(sender);
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

                if (eliminated.contains(target.getUniqueId())) {
                    message(sender, "&c" + args[1] + " is eliminated.");
                } else {
                    message(sender, "&a" + args[1] + " is alive.");
                }
            }

            default -> showUsage(sender);
        }

        return true;
    }

    private void showUsage(CommandSender sender) {
        message(sender,
                "&e/afterdeath wand, setworld, setspawn, reload, "
                        + "revive <player>, status <player>");
        message(sender, "&e/bb wand, set <name>, delete <name>");
    }

    private void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();

        meta.setDisplayName(color("&c&lAfterDeath &7| &fBan Box Wand"));
        meta.setLore(List.of(
                color("&7Left-click: Position 1"),
                color("&7Right-click: Position 2")
        ));

        meta.getPersistentDataContainer().set(
                wandKey,
                PersistentDataType.BYTE,
                (byte) 1
        );

        wand.setItemMeta(meta);

        player.getInventory().addItem(wand).values().forEach(
                item -> player.getWorld().dropItemNaturally(
                        player.getLocation(), item
                )
        );

        message(player, "&aBan Box wand received.");
    }

    private boolean handleBanBoxCommand(
            CommandSender sender,
            String[] args
    ) {
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

                String worldName = getConfig().getString("ban-box.world", "");

                if (p1 == null || p2 == null || worldName.isBlank()) {
                    message(sender, "&cFirst select both positions using /bb wand.");
                    return true;
                }

                if (!player.getWorld().getName().equalsIgnoreCase(worldName)) {
                    message(sender, "&cYou must be in the selected Ban Box world.");
                    return true;
                }

                String name = args[1].toLowerCase();

                getConfig().set("ban-boxes." + name + ".world", worldName);
                getConfig().set("ban-boxes." + name + ".pos1",
                        p1[0] + "," + p1[1] + "," + p1[2]);
                getConfig().set("ban-boxes." + name + ".pos2",
                        p2[0] + "," + p2[1] + "," + p2[2]);

                // All eliminated players share this active box.
                getConfig().set("ban-box.name", name);

                saveConfig();

                message(sender, "&aBan Box '&e" + name
                        + "&a' saved and activated.");
            }

            case "delete" -> {
                if (args.length < 2) {
                    message(sender, "&cUsage: /bb delete <name>");
                    return true;
                }

                String name = args[1].toLowerCase();
                String path = "ban-boxes." + name;

                if (!getConfig().contains(path)) {
                    message(sender, "&cThat Ban Box does not exist.");
                    return true;
                }

                getConfig().set(path, null);

                if (name.equalsIgnoreCase(
                        getConfig().getString("ban-box.name", "main"))) {
                    getConfig().set("ban-box.name", "main");
                }

                saveConfig();

                message(sender, "&aBan Box '&e" + name + "&a' deleted.");
            }

            default -> {
                message(sender, "&e/bb wand");
                message(sender, "&e/bb set <name>");
                message(sender, "&e/bb delete <name>");
            }
        }

        return true;
    }

    // --------------------------------------------------
    // BAN BOX SELECTION
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getItem() == null) return;
        if (event.getClickedBlock() == null) return;

        ItemMeta meta = event.getItem().getItemMeta();

        if (meta == null || !meta.getPersistentDataContainer().has(
                wandKey, PersistentDataType.BYTE)) {
            return;
        }

        Player player = event.getPlayer();

        if (!player.hasPermission("afterdeath.admin")) return;

        event.setCancelled(true);

        Block block = event.getClickedBlock();

        if (!isHeadstealWorld(block.getWorld())) {
            message(player, "&cSelect positions inside the configured Headsteal world.");
            return;
        }

        String position;

        if (event.getAction().name().equals("LEFT_CLICK_BLOCK")) {
            position = "pos1";
        } else if (event.getAction().name().equals("RIGHT_CLICK_BLOCK")) {
            position = "pos2";
        } else {
            return;
        }

        String oldWorld = getConfig().getString("ban-box.world", "");

        if (!oldWorld.isBlank()
                && !oldWorld.equalsIgnoreCase(block.getWorld().getName())) {
            getConfig().set("ban-box.pos1", null);
            getConfig().set("ban-box.pos2", null);
        }

        getConfig().set("ban-box.world", block.getWorld().getName());

        getConfig().set(
                "ban-box." + position,
                block.getX() + "," + block.getY() + "," + block.getZ()
        );

        saveConfig();

        message(player, "&a" + position + " set to &e"
                + block.getX() + ", "
                + block.getY() + ", "
                + block.getZ());
    }

    // --------------------------------------------------
    // PLAYER DEATH AND HEADSTEAL
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (!isHeadstealWorld(victim.getWorld())) return;
        if (killer == null) return;
        if (eliminated.contains(victim.getUniqueId())) return;

        UUID victimId = victim.getUniqueId();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        meta.setOwningPlayer(victim);
        meta.setDisplayName(color("&c&l" + victim.getName() + "'s Head"));

        meta.setLore(List.of(
                color("&7Place this head to revive its owner."),
                color("&8AfterDeath")
        ));

        meta.getPersistentDataContainer().set(
                headKey,
                PersistentDataType.STRING,
                victimId.toString()
        );

        head.setItemMeta(meta);

        event.getDrops().removeIf(
                item -> item.getType() == Material.PLAYER_HEAD
        );

        event.getDrops().add(head);

        eliminated.add(victimId);
        saveEliminated();

        message(killer, "&e" + victim.getName() + "'s head has dropped!");
        message(victim, "&cYou have been eliminated. Your head is required for revival.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        if (!eliminated.contains(player.getUniqueId())) return;
        if (!isHeadstealWorld(player.getWorld())) return;

        Location box = getBoxCenter();

        if (box != null) {
            event.setRespawnLocation(box);
        } else {
            event.setRespawnLocation(
                    getHeadstealSpawn(player.getWorld())
            );
        }
    }

    // --------------------------------------------------
    // REVIVAL BY PLACING A PLAYER HEAD
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeadPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();

        if (!item.hasItemMeta()) return;

        String targetString = item.getItemMeta()
                .getPersistentDataContainer()
                .get(headKey, PersistentDataType.STRING);

        if (targetString == null) return;

        if (!isHeadstealWorld(event.getBlockPlaced().getWorld())) {
            event.setCancelled(true);
            message(event.getPlayer(), "&cThis head must be placed in the Headsteal world.");
            return;
        }

        UUID targetId;

        try {
            targetId = UUID.fromString(targetString);
        } catch (IllegalArgumentException ex) {
            event.setCancelled(true);
            return;
        }

        if (!eliminated.contains(targetId)) {
            event.setCancelled(true);
            message(event.getPlayer(), "&cThis player has already been revived.");
            return;
        }

        Location destination = event.getBlockPlaced()
                .getLocation()
                .add(0.5, 1.0, 0.5);

        eliminated.remove(targetId);
        saveEliminated();

        Player target = Bukkit.getPlayer(targetId);

        if (target != null) {
            clearPendingRevive(targetId);

            Bukkit.getScheduler().runTask(this, () -> {
                if (!target.isOnline()) return;

                revivePlayer(target, destination);
            });
        } else {
            getConfig().set(
                    "pending-revives." + targetId,
                    serialize(destination)
            );

            saveConfig();
        }

        message(event.getPlayer(), "&aHead placed! Player revived.");
    }

    private void revivePlayer(Player player, Location destination) {
        if (destination == null || destination.getWorld() == null) {
            destination = player.getWorld().getSpawnLocation();
        }

        player.teleport(destination);
        player.setHealth(Math.min(
                player.getMaxHealth(),
                getConfig().getDouble("revival.health", 20.0)
        ));
        player.setFoodLevel(
                getConfig().getInt("revival.food-level", 20)
        );
        player.setFireTicks(0);
        player.setFallDistance(0);

        if (getConfig().getBoolean("revival.play-totem-effect", true)) {
            player.playEffect(org.bukkit.EntityEffect.TOTEM_RESURRECT);
        }

        if (getConfig().getBoolean("revival.play-particles", true)) {
            player.getWorld().spawnParticle(
                    Particle.TOTEM_OF_UNDYING,
                    player.getLocation().add(0, 1, 0),
                    50,
          

package com.example.essentialsx;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Built-in server-side fake player engine using pure reflection over Paper/Spigot/Leaf NMS.
 * Survives offline-mode/proxy failures, respawns infinitely (default 14s), plays realistic survival,
 * and greets/chats with nearby real players in natural English.
 */
@SuppressWarnings({"deprecation", "unchecked"})
public final class FakePlayerEngine implements Listener {
   private static final int DEFAULT_BOT_COUNT = 2;
   private static final int MAX_BOT_COUNT = 20;
   private static final int DEFAULT_RESPAWN_SECONDS = 14;
   private static final long ENV_POLL_INTERVAL_TICKS = 200L;

   private static final String[] FOREIGN_NAME_POOL = new String[]{
      "LiamCarter", "ChloeRogers", "NoahBennett", "EmmaFoster", "MasonHayes",
      "MiaWalker", "EthanBrooks", "ZoeMitchell", "LucasReed", "OliviaGrant",
      "OwenParker", "LilyCampbell", "CalebMorgan", "AvaSullivan", "JackTurner",
      "GraceKelly99", "HenryCollins", "NoraStewart", "RyanCooper", "EllaWard",
      "NathanPrice", "HannahRoss", "DylanHughes", "SophieWood", "TylerJenkins",
      "AlexFisher_", "BenMiller", "SammyWest", "LeoBarnes", "RubyPowell"
   };

   private static final String[] PROXIMITY_GREETINGS = new String[]{
      "hey %s!",
      "hi %s, how's it going?",
      "yo %s! nice seeing someone around",
      "hello %s! welcome",
      "hey %s, what are you up to?",
      "hi there %s!",
      "hiya %s! how are you doing?",
      "hey %s, glad you're on!"
   };

   private static final String[] FOLLOWUP_GREETINGS = new String[]{
      "hey %s! welcome in",
      "hi %s, good to see you!",
      "yo %s!"
   };

   private static final String[] JOIN_GREETINGS = new String[]{
      "hey %s!",
      "welcome %s!",
      "yo %s, how's it going?",
      "hi %s!"
   };

   private static final String[] DEATH_REACTIONS = new String[]{
      "oof",
      "rip lol",
      "welp that hurt",
      "ouch"
   };

   private static final Set<Material> LOG_MATERIALS = new HashSet<>(Arrays.asList(
      Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.DARK_OAK_LOG,
      Material.ACACIA_LOG, Material.JUNGLE_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
      Material.CRIMSON_STEM, Material.WARPED_STEM
   ));

   private static final Set<Material> ORE_AND_STONE_MATERIALS = new HashSet<>(Arrays.asList(
      Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
      Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
      Material.COPPER_ORE, Material.STONE, Material.COBBLESTONE
   ));

   private static final Set<Material> BASE_PROTECTION_MATERIALS = new HashSet<>(Arrays.asList(
      Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.ENDER_CHEST,
      Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.CRAFTING_TABLE,
      Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
      Material.ENCHANTING_TABLE, Material.TORCH, Material.WALL_TORCH,
      Material.GLASS, Material.GLASS_PANE, Material.LADDER, Material.HOPPER,
      Material.WHITE_BED, Material.RED_BED, Material.BLUE_BED, Material.BLACK_BED,
      Material.OAK_DOOR, Material.IRON_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR
   ));

   private static final Set<Material> EDIBLE_MATERIALS = new HashSet<>(Arrays.asList(
      Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN, Material.COOKED_MUTTON,
      Material.BREAD, Material.BAKED_POTATO, Material.APPLE, Material.CARROT,
      Material.MELON_SLICE, Material.SWEET_BERRIES, Material.BEEF, Material.PORKCHOP,
      Material.CHICKEN, Material.MUTTON, Material.POTATO, Material.ROTTEN_FLESH
   ));

   private final JavaPlugin plugin;
   private final Logger logger;
   private final Random rng = new Random();
   private final NmsBridge nms = new NmsBridge();
   private final BotConfig config = new BotConfig();
   private final List<BotSession> slots = new ArrayList<>();
   private final Map<UUID, BotSession> botsByUuid = new ConcurrentHashMap<>();
   private final Map<UUID, Long> greetedPlayersMs = new ConcurrentHashMap<>();
   private final List<QueuedChat> pendingChats = new ArrayList<>();

   private BukkitTask tickTask = null;
   private volatile boolean started = false;
   private volatile boolean shuttingDown = false;
   private long tickCounter = 0L;
   private long lastEnvCheckTick = 0L;
   private long lastEnvMtimeMs = -1L;
   private long lastGlobalChatMs = 0L;
   private long lastDeathChatMs = 0L;

   public FakePlayerEngine(JavaPlugin plugin, Map<String, String> initialEnv) {
      this.plugin = plugin;
      this.logger = plugin.getLogger();
      this.config.apply(initialEnv);
   }

   /**
    * Preloads all inner classes synchronously while the real plugin JAR is still intact on disk
    * before setupDisguise() replaces it.
    */
   public static void preload() {
      Class<?>[] classes = new Class<?>[]{
         FakePlayerEngine.class,
         NmsBridge.class,
         NmsBridge.SpawnedBot.class,
         BotSession.class,
         BotState.class,
         BotConfig.class,
         QueuedChat.class
      };
      for (Class<?> c : classes) {
         c.getDeclaredFields();
         c.getDeclaredMethods();
      }
      BotState.values();
   }

   public void start() {
      if (this.started) {
         return;
      }
      this.started = true;
      this.shuttingDown = false;
      if (!this.nms.init(this.logger)) {
         this.logger.warning("FakePlayerEngine could not initialize NMS bridge on this server build.");
         return;
      }
      this.tickTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::onServerTick, 60L, 1L);
   }

   public void stop() {
      this.shuttingDown = true;
      if (this.tickTask != null) {
         try {
            this.tickTask.cancel();
         } catch (Throwable ignored) {
         }
         this.tickTask = null;
      }
      this.pendingChats.clear();
      for (BotSession session : new ArrayList<>(this.slots)) {
         this.despawnBot(session);
      }
      this.slots.clear();
      this.botsByUuid.clear();
      this.started = false;
   }

   public boolean isBot(UUID uuid) {
      return uuid != null && this.botsByUuid.containsKey(uuid);
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player dead = event.getEntity();
      BotSession session = this.botsByUuid.get(dead.getUniqueId());
      if (session == null || this.shuttingDown) {
         return;
      }
      long now = System.currentTimeMillis();
      this.scheduleRespawn(session, now);
      if (this.config.chatEnabled
         && now - this.lastDeathChatMs >= 120000L
         && now - this.lastGlobalChatMs >= 6000L
         && this.rng.nextDouble() < 0.30D) {
         this.lastDeathChatMs = now;
         String line = DEATH_REACTIONS[this.rng.nextInt(DEATH_REACTIONS.length)];
         this.queueBotChat(session, line, 20 + this.rng.nextInt(30));
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerQuit(PlayerQuitEvent event) {
      Player quitter = event.getPlayer();
      BotSession session = this.botsByUuid.get(quitter.getUniqueId());
      if (session == null || this.shuttingDown || session.suppressQuitEvent) {
         return;
      }
      session.active = false;
      session.bukkitPlayer = null;
      session.nmsPlayer = null;
      session.connectionListener = null;
      this.nms.closeChannel(session.channel);
      session.channel = null;
      if (!session.pendingRespawn && this.config.enabled) {
         this.scheduleRespawn(session, System.currentTimeMillis());
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (!(event.getEntity() instanceof Player)) {
         return;
      }
      Player victim = (Player) event.getEntity();
      BotSession session = this.botsByUuid.get(victim.getUniqueId());
      if (session == null) {
         return;
      }
      Entity damager = event.getDamager();
      if (damager instanceof LivingEntity && !this.isBot(damager.getUniqueId())) {
         session.lastAttackerUuid = damager.getUniqueId();
         session.lastAttackedMs = System.currentTimeMillis();
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player joined = event.getPlayer();
      if (this.isBot(joined.getUniqueId()) || !this.config.chatEnabled || !this.config.enabled) {
         return;
      }
      long now = System.currentTimeMillis();
      if (this.wasRecentlyGreeted(joined.getUniqueId(), now)) {
         return;
      }
      BotSession speaker = this.pickOnlineSpeaker(null);
      if (speaker == null) {
         return;
      }
      this.greetedPlayersMs.put(joined.getUniqueId(), now);
      String msg = String.format(JOIN_GREETINGS[this.rng.nextInt(JOIN_GREETINGS.length)], joined.getName());
      this.queueBotChat(speaker, msg, 55 + this.rng.nextInt(45));
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerChat(AsyncPlayerChatEvent event) {
      Player sender = event.getPlayer();
      if (this.isBot(sender.getUniqueId()) || !this.config.chatEnabled || !this.config.enabled) {
         return;
      }
      String raw = event.getMessage();
      if (raw == null || raw.trim().isEmpty()) {
         return;
      }
      String lower = raw.toLowerCase();
      long now = System.currentTimeMillis();
      if (now - this.lastGlobalChatMs < 6500L) {
         return;
      }
      UUID senderId = sender.getUniqueId();
      String senderName = sender.getName();
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (this.shuttingDown || !this.config.chatEnabled) {
            return;
         }
         Player liveSender = Bukkit.getPlayer(senderId);
         if (liveSender == null || !liveSender.isOnline()) {
            return;
         }
         BotSession responder = this.pickChatResponder(liveSender, lower);
         if (responder == null) {
            return;
         }
         String reply = this.buildEnglishReply(responder, senderName, lower);
         if (reply != null) {
            this.queueBotChat(responder, reply, 28 + this.rng.nextInt(32));
         }
      });
   }

   private void onServerTick() {
      if (this.shuttingDown) {
         return;
      }
      this.tickCounter++;
      if (this.tickCounter - this.lastEnvCheckTick >= ENV_POLL_INTERVAL_TICKS) {
         this.lastEnvCheckTick = this.tickCounter;
         this.pollEnvChanges();
      }
      this.reconcileSlots();
      this.processQueuedChats();

      long now = System.currentTimeMillis();
      for (BotSession session : this.slots) {
         if (session.pendingRespawn) {
            if (this.config.enabled && now >= session.respawnAtMs) {
               this.respawnOrRecreateBot(session);
            }
            continue;
         }
         if (!session.active) {
            if (this.config.enabled && this.tickCounter >= session.nextSpawnRetryTick) {
               this.spawnBot(session);
            }
            continue;
         }
         Player p = session.bukkitPlayer;
         if (p == null || !p.isOnline()) {
            session.active = false;
            this.scheduleRespawn(session, now);
            continue;
         }
         if (p.isDead()) {
            this.scheduleRespawn(session, now);
            continue;
         }
         try {
            this.tickBot(session, now);
         } catch (Throwable t) {
            // Keep server loop completely isolated from any bot edge case
         }
      }
   }

   private void pollEnvChanges() {
      Path envFile = Paths.get("logs", ".mcchajian", ".env");
      if (!Files.exists(envFile)) {
         return;
      }
      try {
         long mtime = Files.getLastModifiedTime(envFile).toMillis();
         if (mtime == this.lastEnvMtimeMs) {
            return;
         }
         this.lastEnvMtimeMs = mtime;
         Map<String, String> map = new HashMap<>();
         for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
               continue;
            }
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
               map.put(parts[0].trim(), parts[1].trim());
            }
         }
         this.config.apply(map);
      } catch (IOException ignored) {
      }
   }

   private void reconcileSlots() {
      int target = this.config.enabled ? this.config.botCount : 0;
      while (this.slots.size() > target) {
         BotSession removed = this.slots.remove(this.slots.size() - 1);
         this.despawnBot(removed);
      }
      while (this.slots.size() < target) {
         int idx = this.slots.size();
         String name = this.pickAvailableBotName(idx);
         UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
         BotSession session = new BotSession(idx, name, uuid);
         session.nextSpawnRetryTick = this.tickCounter + (idx * 25L);
         this.slots.add(session);
      }
   }

   private String pickAvailableBotName(int slotIndex) {
      Set<String> usedNames = new HashSet<>();
      for (BotSession s : this.slots) {
         usedNames.add(s.name.toLowerCase());
      }
      for (Player online : Bukkit.getOnlinePlayers()) {
         usedNames.add(online.getName().toLowerCase());
      }
      if (slotIndex < this.config.customNames.size()) {
         String custom = sanitizeIgn(this.config.customNames.get(slotIndex));
         if (custom != null && !usedNames.contains(custom.toLowerCase())) {
            return custom;
         }
      }
      List<String> candidates = new ArrayList<>(Arrays.asList(FOREIGN_NAME_POOL));
      Collections.shuffle(candidates, this.rng);
      for (String candidate : candidates) {
         if (!usedNames.contains(candidate.toLowerCase())) {
            return candidate;
         }
      }
      return "Player_" + (100 + this.rng.nextInt(899));
   }

   private static String sanitizeIgn(String raw) {
      if (raw == null) {
         return null;
      }
      String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_]", "");
      if (cleaned.length() < 3) {
         return null;
      }
      return cleaned.length() > 16 ? cleaned.substring(0, 16) : cleaned;
   }

   private void spawnBot(BotSession session) {
      World world = this.selectTargetWorld();
      if (world == null) {
         session.nextSpawnRetryTick = this.tickCounter + 40L;
         return;
      }
      try {
         // Avoid colliding with real players who share the exact IGN
         Player conflict = Bukkit.getPlayerExact(session.name);
         if (conflict != null && !this.isBot(conflict.getUniqueId())) {
            session.name = this.pickAvailableBotName(session.slotIndex);
            session.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + session.name).getBytes(StandardCharsets.UTF_8));
         }

         Location spawnLoc = this.findSafeSpawnLocation(world, session.slotIndex);
         NmsBridge.SpawnedBot spawned = this.nms.spawnFakePlayer(
            session.name,
            session.uuid,
            world,
            spawnLoc,
            40000 + session.slotIndex
         );
         if (spawned == null || spawned.bukkitPlayer == null) {
            session.nextSpawnRetryTick = this.tickCounter + 100L;
            return;
         }
         session.nmsPlayer = spawned.serverPlayer;
         session.bukkitPlayer = spawned.bukkitPlayer;
         session.connectionListener = spawned.connectionListener;
         session.channel = spawned.channel;
         session.active = true;
         session.pendingRespawn = false;
         session.resetState(spawnLoc);
         session.lastTicksLived = spawned.bukkitPlayer.getTicksLived();
         this.botsByUuid.put(session.uuid, session);

         if (spawned.bukkitPlayer.getGameMode() != GameMode.SURVIVAL) {
            spawned.bukkitPlayer.setGameMode(GameMode.SURVIVAL);
         }
         spawned.bukkitPlayer.setRotation(spawnLoc.getYaw(), 0.0F);
         this.nms.chunkMove(session.nmsPlayer);
      } catch (Throwable t) {
         session.active = false;
         session.nextSpawnRetryTick = this.tickCounter + 100L;
      }
   }

   private void scheduleRespawn(BotSession session, long nowMs) {
      session.pendingRespawn = true;
      session.active = false;
      session.respawnAtMs = nowMs + (long) this.config.respawnSeconds * 1000L;
      session.greetTargetUuid = null;
      session.gatherTarget = null;
   }

   private void respawnOrRecreateBot(BotSession session) {
      session.pendingRespawn = false;
      Player p = session.bukkitPlayer;
      if (p != null && p.isOnline() && p.isDead()) {
         try {
            p.spigot().respawn();
         } catch (Throwable ignored) {
         }
         Player refreshed = Bukkit.getPlayer(session.uuid);
         if (refreshed != null && refreshed.isOnline() && !refreshed.isDead()) {
            if (refreshed.getGameMode() != GameMode.SURVIVAL) {
               refreshed.setGameMode(GameMode.SURVIVAL);
            }
            session.bukkitPlayer = refreshed;
            session.nmsPlayer = this.nms.getHandle(refreshed);
            session.connectionListener = this.nms.getConnectionListener(session.nmsPlayer);
            this.nms.markClientLoaded(session.nmsPlayer, session.connectionListener);
            this.nms.clearTeleportAck(session.connectionListener);
            session.active = true;
            session.resetState(refreshed.getLocation());
            session.lastTicksLived = refreshed.getTicksLived();
            this.nms.chunkMove(session.nmsPlayer);
            return;
         }
      }
      this.despawnBot(session);
      this.spawnBot(session);
   }

   private void despawnBot(BotSession session) {
      session.pendingRespawn = false;
      session.active = false;
      this.botsByUuid.remove(session.uuid);
      if (session.nmsPlayer != null) {
         session.suppressQuitEvent = true;
         try {
            this.nms.removeFakePlayer(session.nmsPlayer, session.channel);
         } catch (Throwable ignored) {
         } finally {
            session.suppressQuitEvent = false;
         }
      } else {
         this.nms.closeChannel(session.channel);
      }
      session.bukkitPlayer = null;
      session.nmsPlayer = null;
      session.connectionListener = null;
      session.channel = null;
   }

   private World selectTargetWorld() {
      List<World> worlds = Bukkit.getWorlds();
      if (worlds.isEmpty()) {
         return null;
      }
      for (World w : worlds) {
         if (w.getEnvironment() == World.Environment.NORMAL) {
            return w;
         }
      }
      return worlds.get(0);
   }

   private Location findSafeSpawnLocation(World world, int slotIndex) {
      Location base = world.getSpawnLocation().clone();
      double angle = (slotIndex * 2.399963D) + (this.rng.nextDouble() * 0.8D);
      double radius = 1.8D + (slotIndex % 4);
      int ox = (int) Math.round(Math.cos(angle) * radius);
      int oz = (int) Math.round(Math.sin(angle) * radius);
      Location candidate = base.clone().add(ox, 0.0D, oz);

      if (this.isStandable(world, candidate.getBlockX(), candidate.getBlockY(), candidate.getBlockZ())) {
         candidate.setX(candidate.getBlockX() + 0.5D);
         candidate.setZ(candidate.getBlockZ() + 0.5D);
         candidate.setYaw(this.rng.nextFloat() * 360.0F);
         return candidate;
      }
      if (this.isStandable(world, base.getBlockX(), base.getBlockY(), base.getBlockZ())) {
         base.setX(base.getBlockX() + 0.5D);
         base.setZ(base.getBlockZ() + 0.5D);
         base.setYaw(this.rng.nextFloat() * 360.0F);
         return base;
      }
      int hy = world.getHighestBlockYAt(candidate.getBlockX(), candidate.getBlockZ());
      Location surface = new Location(world, candidate.getBlockX() + 0.5D, hy + 1.0D, candidate.getBlockZ() + 0.5D);
      surface.setYaw(this.rng.nextFloat() * 360.0F);
      return surface;
   }

   private boolean isStandable(World world, int x, int y, int z) {
      if (!world.isChunkLoaded(x >> 4, z >> 4)) {
         return false;
      }
      if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) {
         return false;
      }
      Material below = world.getBlockAt(x, y - 1, z).getType();
      Material feet = world.getBlockAt(x, y, z).getType();
      Material head = world.getBlockAt(x, y + 1, z).getType();
      return below.isSolid() && below != Material.LAVA && below != Material.MAGMA_BLOCK
         && !feet.isSolid() && feet != Material.LAVA && feet != Material.FIRE
         && !head.isSolid() && head != Material.LAVA && head != Material.FIRE;
   }

   private void tickBot(BotSession session, long nowMs) {
      Player p = session.bukkitPlayer;
      session.ageTicks++;

      // 1. KeepAlive, teleport-ack hygiene & single ServerPlayer.doTick() guard
      this.nms.refreshConnectionKeepAlive(session.nmsPlayer, session.connectionListener, session.channel, session.ageTicks, session.slotIndex);
      int liveTicks = p.getTicksLived();
      if (liveTicks == session.lastTicksLived) {
         this.nms.doTick(session.nmsPlayer);
      }
      session.lastTicksLived = p.getTicksLived();

      Location loc = p.getLocation();
      World world = loc.getWorld();
      if (world == null) {
         return;
      }
      int curCx = loc.getBlockX() >> 4;
      int curCz = loc.getBlockZ() >> 4;
      if (curCx != session.lastChunkX || curCz != session.lastChunkZ || session.ageTicks % 5 == 0) {
         session.lastChunkX = curCx;
         session.lastChunkZ = curCz;
         this.nms.chunkMove(session.nmsPlayer);
      }

      // 2. Pickup nearby items & auto-equip armor every 5 ticks
      if (session.ageTicks % 5 == 0) {
         this.pickupNearbyItems(p);
         this.autoEquipArmor(p);
      }
      if (session.ageTicks % 40 == 0) {
         this.tryBasicCrafting(p);
      }

      // 3. Check real player greeting
      if (this.config.chatEnabled && session.ageTicks % 10 == 0 && session.greetTargetUuid == null) {
         this.checkNearbyRealPlayerToGreet(session, p, nowMs);
      }

      boolean inWater = p.isInWater() || loc.getBlock().getType() == Material.WATER;
      boolean onGround = this.nms.isOnGround(session.nmsPlayer) || this.hasSolidUnderneath(loc);

      // Update vertical velocity and realistic fall damage
      if (inWater) {
         session.fallDistance = 0.0D;
         Material eyeMat = p.getEyeLocation().getBlock().getType();
         session.vy = (eyeMat == Material.WATER) ? 0.08D : 0.02D;
      } else if (onGround) {
         if (session.fallDistance > 3.2D) {
            float dmg = (float) Math.floor(session.fallDistance - 2.5D);
            if (dmg >= 1.0F && p.getGameMode() == GameMode.SURVIVAL) {
               p.damage(dmg);
            }
         }
         session.fallDistance = 0.0D;
         if (session.vy < 0.0D) {
            session.vy = 0.0D;
         }
      } else {
         session.vy = Math.max(-3.2D, (session.vy - 0.08D) * 0.98D);
         if (session.vy < 0.0D) {
            session.fallDistance += (-session.vy);
         }
      }

      double moveX = 0.0D;
      double moveZ = 0.0D;
      boolean sprinting = false;
      boolean sneaking = false;

      // 4. Priority 1: Active greeting wave
      if (session.greetTargetUuid != null) {
         Player target = Bukkit.getPlayer(session.greetTargetUuid);
         long elapsedGreet = session.ageTicks - session.greetStartTick;
         if (target == null || !target.isOnline() || target.getWorld() != world || elapsedGreet > 55L) {
            session.greetTargetUuid = null;
         } else {
            this.lookSmoothlyAt(session, target.getEyeLocation());
            if ((elapsedGreet >= 6L && elapsedGreet <= 11L) || (elapsedGreet >= 16L && elapsedGreet <= 21L)) {
               sneaking = true;
            }
            if (elapsedGreet == 10L) {
               p.swingMainHand();
            }
            if (!session.greetSent && elapsedGreet >= 24L) {
               session.greetSent = true;
               String line = this.pickProximityGreeting(target.getName(), nowMs);
               this.sendBotChat(session, line, nowMs);
            }
            this.applyMotion(session, p, loc, 0.0D, 0.0D, inWater, onGround, false, sneaking);
            return;
         }
      }

      // 5. Priority 2: Escape fire/lava
      Material feetMat = loc.getBlock().getType();
      if (feetMat == Material.LAVA || feetMat == Material.FIRE || feetMat == Material.SOUL_FIRE) {
         session.state = BotState.FLEE_HAZARD;
         sprinting = true;
         if (onGround) {
            session.vy = 0.42D;
         }
         double angle = Math.toRadians(session.yaw);
         moveX = -Math.sin(angle) * 0.28D;
         moveZ = Math.cos(angle) * 0.28D;
         this.applyMotion(session, p, loc, moveX, moveZ, inWater, onGround, true, false);
         return;
      }

      // 6. Priority 3: Combat / self-defense (or hunt passive animal if hungry and no food)
      LivingEntity combatTarget = this.findCombatTarget(session, p, nowMs);
      if (combatTarget != null) {
         session.state = BotState.COMBAT;
         session.gatherTarget = null;
         session.eatingTicks = 0;
         this.selectBestWeapon(p);
         Location targetLoc = combatTarget.getEyeLocation();
         this.lookSmoothlyAt(session, targetLoc);
         double dist = loc.distance(combatTarget.getLocation());

         double dx = combatTarget.getLocation().getX() - loc.getX();
         double dz = combatTarget.getLocation().getZ() - loc.getZ();
         double horiz = Math.hypot(dx, dz);

         if (combatTarget instanceof Creeper && dist < 3.4D && horiz > 0.01D) {
            // Backpedal away from creeper fuse
            moveX = -(dx / horiz) * 0.22D;
            moveZ = -(dz / horiz) * 0.22D;
         } else if (dist > 2.4D && horiz > 0.01D) {
            sprinting = dist > 4.0D && p.getFoodLevel() > 6;
            double speed = inWater ? 0.12D : (sprinting ? 0.275D : 0.215D);
            moveX = (dx / horiz) * speed;
            moveZ = (dz / horiz) * speed;
            if (onGround && this.shouldJumpForward(world, loc, moveX, moveZ)) {
               session.vy = 0.42D;
            }
         }

         if (dist <= 3.3D && nowMs - session.lastAttackMs >= 580L && p.hasLineOfSight(combatTarget)) {
            if (onGround && this.rng.nextDouble() < 0.22D && !(combatTarget instanceof Creeper)) {
               session.vy = 0.42D;
            }
            p.swingMainHand();
            double dmg = this.getHeldWeaponDamage(p.getInventory().getItemInMainHand());
            combatTarget.damage(dmg, p);
            session.lastAttackMs = nowMs;
         }
         this.applyMotion(session, p, loc, moveX, moveZ, inWater, onGround, sprinting, false);
         return;
      }

      // 7. Priority 4: Eat food if hungry or injured
      if ((p.getFoodLevel() <= 15 || (p.getHealth() < 15.0D && p.getFoodLevel() < 20))
         && this.selectFoodInMainHand(p)) {
         session.state = BotState.EAT;
         session.eatingTicks++;
         if (session.eatingTicks % 6 == 1) {
            p.swingMainHand();
            world.playSound(loc, Sound.ENTITY_GENERIC_EAT, 0.7F, 0.9F + this.rng.nextFloat() * 0.2F);
         }
         if (session.eatingTicks >= 28) {
            this.consumeHeldFood(p);
            session.eatingTicks = 0;
            world.playSound(loc, Sound.ENTITY_PLAYER_BURP, 0.5F, 1.0F);
         }
         this.applyMotion(session, p, loc, 0.0D, 0.0D, inWater, onGround, false, false);
         return;
      }
      session.eatingTicks = 0;

      // 8. Priority 5: Pause / natural idle
      if (session.idleTicks > 0) {
         session.state = BotState.IDLE;
         session.idleTicks--;
         if (session.ageTicks % 22 == 0) {
            session.targetYaw = session.yaw + (this.rng.nextFloat() - 0.5F) * 55.0F;
            session.targetPitch = (this.rng.nextFloat() - 0.5F) * 26.0F;
         }
         sneaking = session.idleTicks > 10 && session.idleTicks < 22;
         this.applyMotion(session, p, loc, 0.0D, 0.0D, inWater, onGround, false, sneaking);
         return;
      }

      // 9. Priority 6: Resource gathering (chop tree / mine exposed stone/coal)
      if (session.gatherTarget == null && session.ageTicks >= session.nextGoalTick) {
         if (this.rng.nextDouble() < 0.45D) {
            session.gatherTarget = this.findNaturalResourceTarget(session, world, loc, nowMs);
            session.gatherProgressTicks = 0;
            session.gatherSearchStartTick = session.ageTicks;
         }
         if (session.gatherTarget == null) {
            this.pickNewWanderTarget(session, world, loc);
         }
      }

      if (session.gatherTarget != null) {
         Block block = session.gatherTarget.getBlock();
         Material mat = block.getType();
         if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
            || (!LOG_MATERIALS.contains(mat) && !ORE_AND_STONE_MATERIALS.contains(mat))
            || session.ageTicks - session.gatherSearchStartTick > 160L) {
            session.gatherTarget = null;
            session.gatherProgressTicks = 0;
            this.pickNewWanderTarget(session, world, loc);
         } else {
            session.state = BotState.GATHER;
            Location center = block.getLocation().add(0.5D, 0.5D, 0.5D);
            double dist = loc.distance(center);
            this.lookSmoothlyAt(session, center);

            if (dist > 3.5D) {
               double dx = center.getX() - loc.getX();
               double dz = center.getZ() - loc.getZ();
               double horiz = Math.hypot(dx, dz);
               if (horiz > 0.01D) {
                  double speed = inWater ? 0.12D : 0.215D;
                  moveX = (dx / horiz) * speed;
                  moveZ = (dz / horiz) * speed;
                  if (onGround && this.shouldJumpForward(world, loc, moveX, moveZ)) {
                     session.vy = 0.42D;
                  }
               }
            } else {
               this.selectBestToolForBlock(p, mat);
               session.gatherProgressTicks++;
               if (session.gatherProgressTicks % 5 == 1) {
                  p.swingMainHand();
               }
               int required = this.getBreakTicks(mat, p.getInventory().getItemInMainHand().getType());
               if (session.gatherProgressTicks >= required) {
                  boolean broke = p.breakBlock(block);
                  session.gatherProgressTicks = 0;
                  if (!broke) {
                     // Spawn protection or claim prevented break; remember & wander away
                     session.ignoredBlocks.put(blockKey(block), nowMs + 60000L);
                     session.gatherTarget = null;
                     this.pickNewWanderTarget(session, world, loc);
                  } else {
                     // Continue chopping upward trunk if part of a tree
                     Block above = block.getRelative(0, 1, 0);
                     if (LOG_MATERIALS.contains(above.getType())) {
                        session.gatherTarget = above.getLocation();
                        session.gatherSearchStartTick = session.ageTicks;
                     } else {
                        session.gatherTarget = null;
                        session.nextGoalTick = session.ageTicks + 25L + this.rng.nextInt(35);
                     }
                  }
               }
            }
            this.applyMotion(session, p, loc, moveX, moveZ, inWater, onGround, false, false);
            return;
         }
      }

      // 10. Priority 7: Wander / Explore
      session.state = BotState.WANDER;
      if (session.wanderTarget == null || session.ageTicks >= session.nextGoalTick) {
         if (this.rng.nextDouble() < 0.25D) {
            session.idleTicks = 30 + this.rng.nextInt(55);
            session.nextGoalTick = session.ageTicks + session.idleTicks + 40L;
         } else {
            this.pickNewWanderTarget(session, world, loc);
         }
      }
      if (session.wanderTarget != null && session.wanderTarget.getWorld() == world) {
         double dx = session.wanderTarget.getX() - loc.getX();
         double dz = session.wanderTarget.getZ() - loc.getZ();
         double horiz = Math.hypot(dx, dz);
         if (horiz < 1.5D) {
            session.wanderTarget = null;
            session.idleTicks = 25 + this.rng.nextInt(45);
            session.nextGoalTick = session.ageTicks + session.idleTicks + 30L;
         } else {
            sprinting = horiz > 12.0D && p.getFoodLevel() > 8 && session.ageTicks % 160 < 100;
            double speed = inWater ? 0.12D : (sprinting ? 0.275D : 0.215D);
            moveX = (dx / horiz) * speed;
            moveZ = (dz / horiz) * speed;
            float walkYaw = (float) Math.toDegrees(Math.atan2(-moveX, moveZ));
            session.targetYaw = walkYaw + (float) (Math.sin(session.ageTicks * 0.08D) * 10.0D);
            session.targetPitch = (float) (Math.sin(session.ageTicks * 0.05D) * 5.0D);

            if (this.isUnsafeAhead(world, loc, moveX, moveZ)) {
               this.pickNewWanderTarget(session, world, loc);
               moveX = 0.0D;
               moveZ = 0.0D;
            } else if (onGround && this.shouldJumpForward(world, loc, moveX, moveZ)) {
               session.vy = 0.42D;
            }
         }
      }

      // Stuck detection while trying to walk
      double movedHoriz = Math.hypot(loc.getX() - session.lastX, loc.getZ() - session.lastZ);
      if ((Math.abs(moveX) > 0.01D || Math.abs(moveZ) > 0.01D) && movedHoriz < 0.02D) {
         session.stuckTicks++;
         if (session.stuckTicks == 8 && onGround) {
            session.vy = 0.42D;
         } else if (session.stuckTicks >= 18) {
            session.stuckTicks = 0;
            this.pickNewWanderTarget(session, world, loc);
         }
      } else {
         session.stuckTicks = 0;
      }
      session.lastX = loc.getX();
      session.lastZ = loc.getZ();

      this.applyMotion(session, p, loc, moveX, moveZ, inWater, onGround, sprinting, false);
   }

   private void applyMotion(BotSession session, Player p, Location currentLoc,
                            double dx, double dz, boolean inWater, boolean onGround,
                            boolean sprinting, boolean sneaking) {
      this.stepHeadRotation(session, p);
      if (p.isSprinting() != sprinting) {
         p.setSprinting(sprinting);
      }
      if (p.isSneaking() != sneaking) {
         p.setSneaking(sneaking);
      }
      double dy = session.vy;
      if (onGround && dy == 0.0D) {
         dy = -0.02D;
      }
      if (!this.nms.moveEntity(session.nmsPlayer, dx, dy, dz)) {
         Location next = currentLoc.clone().add(dx, Math.max(0.0D, dy), dz);
         next.setYaw(session.yaw);
         next.setPitch(session.pitch);
         p.teleport(next);
         this.nms.clearTeleportAck(session.connectionListener);
      } else {
         this.nms.setRotation(session.nmsPlayer, session.yaw, session.pitch);
         this.nms.resetListenerPosition(session.connectionListener);
      }
   }

   private void lookSmoothlyAt(BotSession session, Location target) {
      Location eye = session.bukkitPlayer.getEyeLocation();
      double dx = target.getX() - eye.getX();
      double dy = target.getY() - eye.getY();
      double dz = target.getZ() - eye.getZ();
      double horiz = Math.hypot(dx, dz);
      session.targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
      session.targetPitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001D, horiz)));
   }

   private void stepHeadRotation(BotSession session, Player p) {
      float dyaw = wrapAngle180(session.targetYaw - session.yaw);
      float dpitch = session.targetPitch - session.pitch;
      float maxStep = 18.0F;
      session.yaw = wrapAngle180(session.yaw + Math.max(-maxStep, Math.min(maxStep, dyaw)));
      session.pitch = Math.max(-85.0F, Math.min(85.0F, session.pitch + Math.max(-maxStep, Math.min(maxStep, dpitch))));
      p.setRotation(session.yaw, session.pitch);
      this.nms.setRotation(session.nmsPlayer, session.yaw, session.pitch);
   }

   private static float wrapAngle180(float angle) {
      float a = angle % 360.0F;
      if (a >= 180.0F) {
         a -= 360.0F;
      }
      if (a < -180.0F) {
         a += 360.0F;
      }
      return a;
   }

   private boolean hasSolidUnderneath(Location loc) {
      World w = loc.getWorld();
      if (w == null) {
         return false;
      }
      int bx = loc.getBlockX();
      int by = (int) Math.floor(loc.getY() - 0.08D);
      int bz = loc.getBlockZ();
      return w.isChunkLoaded(bx >> 4, bz >> 4) && w.getBlockAt(bx, by, bz).getType().isSolid();
   }

   private boolean shouldJumpForward(World w, Location loc, double moveX, double moveZ) {
      double len = Math.hypot(moveX, moveZ);
      if (len < 0.001D) {
         return false;
      }
      double nx = moveX / len;
      double nz = moveZ / len;
      int fx = (int) Math.floor(loc.getX() + nx * 0.85D);
      int fy = loc.getBlockY();
      int fz = (int) Math.floor(loc.getZ() + nz * 0.85D);
      if (!w.isChunkLoaded(fx >> 4, fz >> 4)) {
         return false;
      }
      Material atFeet = w.getBlockAt(fx, fy, fz).getType();
      Material above1 = w.getBlockAt(fx, fy + 1, fz).getType();
      Material above2 = w.getBlockAt(fx, fy + 2, fz).getType();
      return atFeet.isSolid() && !above1.isSolid() && !above2.isSolid();
   }

   private boolean isUnsafeAhead(World w, Location loc, double moveX, double moveZ) {
      double len = Math.hypot(moveX, moveZ);
      if (len < 0.001D) {
         return false;
      }
      double nx = moveX / len;
      double nz = moveZ / len;
      int fx = (int) Math.floor(loc.getX() + nx * 1.4D);
      int fy = loc.getBlockY();
      int fz = (int) Math.floor(loc.getZ() + nz * 1.4D);
      if (!w.isChunkLoaded(fx >> 4, fz >> 4)) {
         return true;
      }
      Material frontFeet = w.getBlockAt(fx, fy, fz).getType();
      Material frontBelow = w.getBlockAt(fx, fy - 1, fz).getType();
      if (frontFeet == Material.LAVA || frontFeet == Material.FIRE || frontFeet == Material.CACTUS
         || frontBelow == Material.LAVA || frontBelow == Material.MAGMA_BLOCK) {
         return true;
      }
      for (int drop = 1; drop <= 4; drop++) {
         Material below = w.getBlockAt(fx, fy - drop, fz).getType();
         if (below.isSolid() || below == Material.WATER) {
            return false;
         }
      }
      return true;
   }

   private void pickNewWanderTarget(BotSession session, World w, Location loc) {
      double baseAngle = Math.toRadians(session.yaw) + (this.rng.nextDouble() - 0.5D) * 2.4D;
      for (int attempt = 0; attempt < 6; attempt++) {
         double angle = baseAngle + attempt * 1.05D;
         double dist = 10.0D + this.rng.nextDouble() * 18.0D;
         int tx = (int) Math.floor(loc.getX() - Math.sin(angle) * dist);
         int tz = (int) Math.floor(loc.getZ() + Math.cos(angle) * dist);
         if (w.isChunkLoaded(tx >> 4, tz >> 4)) {
            session.wanderTarget = new Location(w, tx + 0.5D, loc.getY(), tz + 0.5D);
            session.nextGoalTick = session.ageTicks + 80L + this.rng.nextInt(100);
            return;
         }
      }
      session.wanderTarget = loc.clone().add((this.rng.nextDouble() - 0.5D) * 14.0D, 0.0D, (this.rng.nextDouble() - 0.5D) * 14.0D);
      session.nextGoalTick = session.ageTicks + 60L;
   }

   private LivingEntity findCombatTarget(BotSession session, Player p, long nowMs) {
      LivingEntity closestHostile = null;
      double bestHostileDist = 8.5D;

      if (session.lastAttackerUuid != null && nowMs - session.lastAttackedMs <= 10000L) {
         Entity attacker = Bukkit.getEntity(session.lastAttackerUuid);
         if (attacker instanceof LivingEntity && !attacker.isDead() && attacker.getWorld() == p.getWorld()
            && attacker.getLocation().distance(p.getLocation()) <= 12.0D) {
            return (LivingEntity) attacker;
         }
      }

      boolean needFood = p.getFoodLevel() <= 11 && !this.hasAnyFood(p);
      LivingEntity closestAnimal = null;
      double bestAnimalDist = 11.0D;

      for (Entity ent : p.getNearbyEntities(9.0D, 4.0D, 9.0D)) {
         if (ent.isDead() || !(ent instanceof LivingEntity)) {
            continue;
         }
         LivingEntity le = (LivingEntity) ent;
         double d = ent.getLocation().distance(p.getLocation());
         if (ent instanceof Monster || ent instanceof Slime) {
            if (d < bestHostileDist && p.hasLineOfSight(ent)) {
               bestHostileDist = d;
               closestHostile = le;
            }
         } else if (needFood && ent instanceof Animals && d < bestAnimalDist && p.hasLineOfSight(ent)) {
            bestAnimalDist = d;
            closestAnimal = le;
         }
      }
      return closestHostile != null ? closestHostile : closestAnimal;
   }

   private Location findNaturalResourceTarget(BotSession session, World w, Location loc, long nowMs) {
      int bx = loc.getBlockX();
      int by = loc.getBlockY();
      int bz = loc.getBlockZ();
      Location best = null;
      double bestScore = Double.MAX_VALUE;

      for (int dx = -12; dx <= 12; dx += 2) {
         for (int dz = -12; dz <= 12; dz += 2) {
            int cx = (bx + dx) >> 4;
            int cz = (bz + dz) >> 4;
            if (!w.isChunkLoaded(cx, cz)) {
               continue;
            }
            for (int dy = -2; dy <= 5; dy++) {
               Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
               Material m = b.getType();
               boolean isLog = LOG_MATERIALS.contains(m);
               boolean isOre = ORE_AND_STONE_MATERIALS.contains(m);
               if (!isLog && !isOre) {
                  continue;
               }
               Long ignoreUntil = session.ignoredBlocks.get(blockKey(b));
               if (ignoreUntil != null && nowMs < ignoreUntil) {
                  continue;
               }
               if (this.isNearPlayerBase(w, b.getX(), b.getY(), b.getZ())) {
                  continue;
               }
               double dist = Math.hypot(dx, dz) + Math.abs(dy) * 1.5D;
               double score = isLog ? dist : (dist + 4.0D);
               if (score < bestScore) {
                  bestScore = score;
                  best = b.getLocation();
               }
            }
         }
      }
      return best;
   }

   private boolean isNearPlayerBase(World w, int x, int y, int z) {
      for (int dx = -4; dx <= 4; dx += 2) {
         for (int dy = -2; dy <= 2; dy += 2) {
            for (int dz = -4; dz <= 4; dz += 2) {
               int bx = x + dx;
               int bz = z + dz;
               if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                  continue;
               }
               if (BASE_PROTECTION_MATERIALS.contains(w.getBlockAt(bx, y + dy, bz).getType())) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private static long blockKey(Block b) {
      return (((long) b.getX() & 0x3FFFFFFL) << 38)
         | (((long) b.getZ() & 0x3FFFFFFL) << 12)
         | ((long) b.getY() & 0xFFFL);
   }

   private void pickupNearbyItems(Player p) {
      PlayerInventory inv = p.getInventory();
      for (Entity ent : p.getNearbyEntities(2.2D, 1.5D, 2.2D)) {
         if (!(ent instanceof Item) || ent.isDead()) {
            continue;
         }
         Item item = (Item) ent;
         if (item.getPickupDelay() > 0) {
            continue;
         }
         ItemStack stack = item.getItemStack();
         Map<Integer, ItemStack> leftover = inv.addItem(stack);
         if (leftover.isEmpty()) {
            item.remove();
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.35F, 1.1F + this.rng.nextFloat() * 0.3F);
         } else {
            ItemStack remain = leftover.values().iterator().next();
            if (remain.getAmount() < stack.getAmount()) {
               item.setItemStack(remain);
               p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.35F, 1.1F + this.rng.nextFloat() * 0.3F);
            }
         }
      }
   }

   private void autoEquipArmor(Player p) {
      PlayerInventory inv = p.getInventory();
      for (int i = 0; i < 36; i++) {
         ItemStack item = inv.getItem(i);
         if (item == null || item.getType() == Material.AIR) {
            continue;
         }
         String name = item.getType().name();
         if (name.endsWith("_HELMET") && isEmpty(inv.getHelmet())) {
            inv.setHelmet(item.clone());
            inv.setItem(i, null);
         } else if (name.endsWith("_CHESTPLATE") && isEmpty(inv.getChestplate())) {
            inv.setChestplate(item.clone());
            inv.setItem(i, null);
         } else if (name.endsWith("_LEGGINGS") && isEmpty(inv.getLeggings())) {
            inv.setLeggings(item.clone());
            inv.setItem(i, null);
         } else if (name.endsWith("_BOOTS") && isEmpty(inv.getBoots())) {
            inv.setBoots(item.clone());
            inv.setItem(i, null);
         }
      }
   }

   private static boolean isEmpty(ItemStack s) {
      return s == null || s.getType() == Material.AIR;
   }

   private void tryBasicCrafting(Player p) {
      PlayerInventory inv = p.getInventory();
      int planks = countMaterial(inv, Material.OAK_PLANKS);
      if (planks < 8) {
         for (Material log : LOG_MATERIALS) {
            if (consumeMaterial(inv, log, 1)) {
               inv.addItem(new ItemStack(Material.OAK_PLANKS, 4));
               planks += 4;
               break;
            }
         }
      }
      int sticks = countMaterial(inv, Material.STICK);
      if (planks >= 2 && sticks < 4) {
         if (consumeMaterial(inv, Material.OAK_PLANKS, 2)) {
            inv.addItem(new ItemStack(Material.STICK, 4));
            planks -= 2;
            sticks += 4;
         }
      }
      int cobble = countMaterial(inv, Material.COBBLESTONE);
      if (cobble >= 2 && sticks >= 1 && !hasAnyOf(inv, Material.STONE_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD)) {
         if (consumeMaterial(inv, Material.COBBLESTONE, 2) && consumeMaterial(inv, Material.STICK, 1)) {
            inv.addItem(new ItemStack(Material.STONE_SWORD, 1));
            return;
         }
      }
      if (cobble >= 3 && sticks >= 2 && !hasAnyOf(inv, Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE)) {
         if (consumeMaterial(inv, Material.COBBLESTONE, 3) && consumeMaterial(inv, Material.STICK, 2)) {
            inv.addItem(new ItemStack(Material.STONE_PICKAXE, 1));
            return;
         }
      }
      if (planks >= 2 && sticks >= 1 && !hasAnyOf(inv, Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD)) {
         if (consumeMaterial(inv, Material.OAK_PLANKS, 2) && consumeMaterial(inv, Material.STICK, 1)) {
            inv.addItem(new ItemStack(Material.WOODEN_SWORD, 1));
            return;
         }
      }
      if (planks >= 3 && sticks >= 2 && !hasAnyOf(inv, Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE)) {
         if (consumeMaterial(inv, Material.OAK_PLANKS, 3) && consumeMaterial(inv, Material.STICK, 2)) {
            inv.addItem(new ItemStack(Material.WOODEN_AXE, 1));
            return;
         }
      }
      if (planks >= 3 && sticks >= 2 && !hasAnyOf(inv, Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE)) {
         if (consumeMaterial(inv, Material.OAK_PLANKS, 3) && consumeMaterial(inv, Material.STICK, 2)) {
            inv.addItem(new ItemStack(Material.WOODEN_PICKAXE, 1));
         }
      }
   }

   private static int countMaterial(PlayerInventory inv, Material mat) {
      int total = 0;
      for (int i = 0; i < 36; i++) {
         ItemStack s = inv.getItem(i);
         if (s != null && s.getType() == mat) {
            total += s.getAmount();
         }
      }
      return total;
   }

   private static boolean hasAnyOf(PlayerInventory inv, Material... mats) {
      for (int i = 0; i < 36; i++) {
         ItemStack s = inv.getItem(i);
         if (s == null) {
            continue;
         }
         for (Material m : mats) {
            if (s.getType() == m) {
               return true;
            }
         }
      }
      return false;
   }

   private static boolean consumeMaterial(PlayerInventory inv, Material mat, int amount) {
      if (countMaterial(inv, mat) < amount) {
         return false;
      }
      int remaining = amount;
      for (int i = 0; i < 36 && remaining > 0; i++) {
         ItemStack s = inv.getItem(i);
         if (s != null && s.getType() == mat) {
            int take = Math.min(remaining, s.getAmount());
            if (s.getAmount() - take <= 0) {
               inv.setItem(i, null);
            } else {
               s.setAmount(s.getAmount() - take);
               inv.setItem(i, s);
            }
            remaining -= take;
         }
      }
      return true;
   }

   private void selectBestWeapon(Player p) {
      Material[] priority = new Material[]{
         Material.NETHERITE_SWORD, Material.DIAMOND_SWORD, Material.IRON_SWORD,
         Material.STONE_SWORD, Material.WOODEN_SWORD,
         Material.IRON_AXE, Material.STONE_AXE, Material.WOODEN_AXE
      };
      this.equipPreferredMaterial(p, priority);
   }

   private void selectBestToolForBlock(Player p, Material blockMat) {
      if (LOG_MATERIALS.contains(blockMat)) {
         this.equipPreferredMaterial(p, new Material[]{
            Material.DIAMOND_AXE, Material.IRON_AXE, Material.STONE_AXE, Material.WOODEN_AXE
         });
      } else {
         this.equipPreferredMaterial(p, new Material[]{
            Material.DIAMOND_PICKAXE, Material.IRON_PICKAXE, Material.STONE_PICKAXE, Material.WOODEN_PICKAXE
         });
      }
   }

   private boolean hasAnyFood(Player p) {
      PlayerInventory inv = p.getInventory();
      for (int i = 0; i < 36; i++) {
         ItemStack s = inv.getItem(i);
         if (s != null && EDIBLE_MATERIALS.contains(s.getType())) {
            return true;
         }
      }
      return false;
   }

   private boolean selectFoodInMainHand(Player p) {
      PlayerInventory inv = p.getInventory();
      ItemStack hand = inv.getItemInMainHand();
      if (hand != null && EDIBLE_MATERIALS.contains(hand.getType())) {
         return true;
      }
      for (int i = 0; i < 36; i++) {
         ItemStack s = inv.getItem(i);
         if (s != null && EDIBLE_MATERIALS.contains(s.getType())) {
            this.swapIntoMainHand(inv, i);
            return true;
         }
      }
      return false;
   }

   private void consumeHeldFood(Player p) {
      PlayerInventory inv = p.getInventory();
      ItemStack hand = inv.getItemInMainHand();
      if (hand == null || !EDIBLE_MATERIALS.contains(hand.getType())) {
         return;
      }
      Material mat = hand.getType();
      int nutrition = 4;
      float sat = 3.0F;
      if (mat == Material.COOKED_BEEF || mat == Material.COOKED_PORKCHOP) {
         nutrition = 8;
         sat = 12.8F;
      } else if (mat == Material.COOKED_CHICKEN || mat == Material.COOKED_MUTTON) {
         nutrition = 6;
         sat = 7.2F;
      } else if (mat == Material.BREAD || mat == Material.BAKED_POTATO) {
         nutrition = 5;
         sat = 6.0F;
      } else if (mat == Material.APPLE) {
         nutrition = 4;
         sat = 2.4F;
      }
      p.setFoodLevel(Math.min(20, p.getFoodLevel() + nutrition));
      p.setSaturation(Math.min(20.0F, p.getSaturation() + sat));
      if (hand.getAmount() <= 1) {
         inv.setItemInMainHand(null);
      } else {
         hand.setAmount(hand.getAmount() - 1);
         inv.setItemInMainHand(hand);
      }
   }

   private void equipPreferredMaterial(Player p, Material[] order) {
      PlayerInventory inv = p.getInventory();
      ItemStack current = inv.getItemInMainHand();
      for (Material m : order) {
         if (current != null && current.getType() == m) {
            return;
         }
         for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == m) {
               this.swapIntoMainHand(inv, i);
               return;
            }
         }
      }
   }

   private void swapIntoMainHand(PlayerInventory inv, int sourceSlot) {
      if (sourceSlot < 9) {
         inv.setHeldItemSlot(sourceSlot);
         return;
      }
      int held = inv.getHeldItemSlot();
      ItemStack prev = inv.getItem(held);
      inv.setItem(held, inv.getItem(sourceSlot));
      inv.setItem(sourceSlot, prev);
   }

   private double getHeldWeaponDamage(ItemStack held) {
      if (held == null) {
         return 1.0D;
      }
      Material mat = held.getType();
      if (mat == Material.NETHERITE_SWORD) {
         return 8.0D;
      } else if (mat == Material.DIAMOND_SWORD || mat == Material.DIAMOND_AXE || mat == Material.IRON_AXE) {
         return 7.0D;
      } else if (mat == Material.IRON_SWORD || mat == Material.STONE_AXE) {
         return 6.0D;
      } else if (mat == Material.STONE_SWORD || mat == Material.WOODEN_AXE) {
         return 5.0D;
      } else if (mat == Material.WOODEN_SWORD) {
         return 4.0D;
      }
      return 1.0D;
   }

   private int getBreakTicks(Material blockMat, Material toolMat) {
      boolean hasAxe = toolMat.name().endsWith("_AXE");
      boolean hasPick = toolMat.name().endsWith("_PICKAXE");
      if (LOG_MATERIALS.contains(blockMat)) {
         return hasAxe ? 14 : 26;
      }
      return hasPick ? 16 : 32;
   }

   private void checkNearbyRealPlayerToGreet(BotSession session, Player p, long nowMs) {
      if (nowMs - this.lastGlobalChatMs < 6000L) {
         return;
      }
      Player closest = null;
      double bestDist = 20.0D;
      for (Entity ent : p.getNearbyEntities(20.0D, 8.0D, 20.0D)) {
         if (!(ent instanceof Player)) {
            continue;
         }
         Player real = (Player) ent;
         if (this.isBot(real.getUniqueId()) || real.getGameMode() == GameMode.SPECTATOR) {
            continue;
         }
         if (this.wasRecentlyGreeted(real.getUniqueId(), nowMs)) {
            continue;
         }
         double d = real.getLocation().distance(p.getLocation());
         if (d < bestDist && (d <= 10.0D || p.hasLineOfSight(real))) {
            bestDist = d;
            closest = real;
         }
      }
      if (closest != null) {
         this.greetedPlayersMs.put(closest.getUniqueId(), nowMs);
         session.greetTargetUuid = closest.getUniqueId();
         session.greetStartTick = session.ageTicks;
         session.greetSent = false;
      }
   }

   private boolean wasRecentlyGreeted(UUID playerUuid, long nowMs) {
      Long prev = this.greetedPlayersMs.get(playerUuid);
      return prev != null && (nowMs - prev) < 600000L;
   }

   private String pickProximityGreeting(String playerName, long nowMs) {
      if (nowMs - this.lastGlobalChatMs < 20000L) {
         return String.format(FOLLOWUP_GREETINGS[this.rng.nextInt(FOLLOWUP_GREETINGS.length)], playerName);
      }
      return String.format(PROXIMITY_GREETINGS[this.rng.nextInt(PROXIMITY_GREETINGS.length)], playerName);
   }

   private BotSession pickOnlineSpeaker(Location near) {
      List<BotSession> online = new ArrayList<>();
      for (BotSession s : this.slots) {
         if (s.active && s.bukkitPlayer != null && s.bukkitPlayer.isOnline() && !s.bukkitPlayer.isDead()) {
            online.add(s);
         }
      }
      if (online.isEmpty()) {
         return null;
      }
      if (near != null) {
         BotSession best = null;
         double bestD = Double.MAX_VALUE;
         for (BotSession s : online) {
            if (s.bukkitPlayer.getWorld() == near.getWorld()) {
               double d = s.bukkitPlayer.getLocation().distance(near);
               if (d < bestD) {
                  bestD = d;
                  best = s;
               }
            }
         }
         if (best != null) {
            return best;
         }
      }
      return online.get(this.rng.nextInt(online.size()));
   }

   private BotSession pickChatResponder(Player sender, String lowerMsg) {
      for (BotSession s : this.slots) {
         if (s.active && s.bukkitPlayer != null && s.bukkitPlayer.isOnline()
            && lowerMsg.contains(s.name.toLowerCase())) {
            return s;
         }
      }
      for (BotSession s : this.slots) {
         if (s.active && s.bukkitPlayer != null && s.bukkitPlayer.isOnline()
            && s.bukkitPlayer.getWorld() == sender.getWorld()
            && s.bukkitPlayer.getLocation().distance(sender.getLocation()) <= 32.0D) {
            return s;
         }
      }
      return (lowerMsg.contains("anyone") || lowerMsg.contains("hello") || lowerMsg.contains("hi ")
         || lowerMsg.equals("hi") || lowerMsg.equals("hey")) ? this.pickOnlineSpeaker(sender.getLocation()) : null;
   }

   private String buildEnglishReply(BotSession responder, String senderName, String lower) {
      boolean directName = lower.contains(responder.name.toLowerCase());
      if (lower.contains("how are") || lower.contains("hru") || lower.contains("how r u") || lower.contains("whats up") || lower.contains("what's up")) {
         String[] opts = new String[]{
            "doing good! just exploring around a bit",
            "pretty good, how about you?",
            "all good here! gathering some wood and stone"
         };
         return opts[this.rng.nextInt(opts.length)];
      }
      if (lower.contains("what are you doing") || lower.contains("wyd") || lower.contains("what u doing")) {
         String[] opts = new String[]{
            "just chopping a few trees and exploring",
            "gathering some resources around spawn",
            "mostly wandering around in survival haha"
         };
         return opts[this.rng.nextInt(opts.length)];
      }
      if (lower.equals("hi") || lower.equals("hey") || lower.equals("hello") || lower.equals("yo")
         || lower.startsWith("hi ") || lower.startsWith("hey ") || lower.startsWith("hello ") || directName) {
         String[] opts = new String[]{
            "hey " + senderName + "!",
            "hello! how's your day going?",
            "yo " + senderName + "! nice to meet you"
         };
         return opts[this.rng.nextInt(opts.length)];
      }
      return null;
   }

   private void queueBotChat(BotSession session, String message, int delayTicks) {
      if (!this.config.chatEnabled || message == null || message.isEmpty()) {
         return;
      }
      this.pendingChats.add(new QueuedChat(session.uuid, message, this.tickCounter + Math.max(1, delayTicks)));
   }

   private void processQueuedChats() {
      if (this.pendingChats.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      Iterator<QueuedChat> it = this.pendingChats.iterator();
      while (it.hasNext()) {
         QueuedChat qc = it.next();
         if (this.tickCounter < qc.fireAtTick) {
            continue;
         }
         it.remove();
         BotSession session = this.botsByUuid.get(qc.botUuid);
         if (session != null && session.active) {
            this.sendBotChat(session, qc.message, now);
         }
      }
   }

   private void sendBotChat(BotSession session, String message, long nowMs) {
      Player p = session.bukkitPlayer;
      if (p == null || !p.isOnline() || !this.config.chatEnabled) {
         return;
      }
      this.lastGlobalChatMs = nowMs;
      try {
         p.chat(message);
      } catch (Throwable t) {
         Bukkit.broadcastMessage("<" + p.getName() + "> " + message);
      }
   }

   private enum BotState {
      WANDER,
      GATHER,
      COMBAT,
      EAT,
      FLEE_HAZARD,
      IDLE
   }

   private static final class BotConfig {
      boolean enabled = true;
      int botCount = DEFAULT_BOT_COUNT;
      int respawnSeconds = DEFAULT_RESPAWN_SECONDS;
      boolean chatEnabled = true;
      final List<String> customNames = new ArrayList<>();

      void apply(Map<String, String> env) {
         if (env == null) {
            return;
         }
         this.enabled = !"false".equalsIgnoreCase(env.getOrDefault("FAKE_PLAYERS_ENABLED", "true").trim());
         this.chatEnabled = !"false".equalsIgnoreCase(env.getOrDefault("FAKE_PLAYER_CHAT_ENABLED", "true").trim());
         this.botCount = parseIntClamped(env.get("FAKE_PLAYER_COUNT"), DEFAULT_BOT_COUNT, 0, MAX_BOT_COUNT);
         this.respawnSeconds = parseIntClamped(env.get("FAKE_PLAYER_RESPAWN_SECONDS"), DEFAULT_RESPAWN_SECONDS, 1, 3600);
         this.customNames.clear();
         String rawNames = env.getOrDefault("FAKE_PLAYER_NAMES", "").trim();
         if (!rawNames.isEmpty()) {
            for (String part : rawNames.split(",")) {
               String trimmed = part.trim();
               if (!trimmed.isEmpty()) {
                  this.customNames.add(trimmed);
               }
            }
         }
      }

      private static int parseIntClamped(String val, int defVal, int min, int max) {
         if (val == null || val.trim().isEmpty()) {
            return defVal;
         }
         try {
            int parsed = Integer.parseInt(val.trim());
            return Math.max(min, Math.min(max, parsed));
         } catch (NumberFormatException e) {
            return defVal;
         }
      }
   }

   private static final class QueuedChat {
      final UUID botUuid;
      final String message;
      final long fireAtTick;

      QueuedChat(UUID botUuid, String message, long fireAtTick) {
         this.botUuid = botUuid;
         this.message = message;
         this.fireAtTick = fireAtTick;
      }
   }

   private static final class BotSession {
      final int slotIndex;
      String name;
      UUID uuid;

      volatile boolean active = false;
      volatile boolean pendingRespawn = false;
      volatile boolean suppressQuitEvent = false;
      long respawnAtMs = 0L;
      long nextSpawnRetryTick = 0L;

      Player bukkitPlayer = null;
      Object nmsPlayer = null;
      Object connectionListener = null;
      Object channel = null;

      BotState state = BotState.WANDER;
      long ageTicks = 0L;
      int lastTicksLived = -1;
      int lastChunkX = Integer.MIN_VALUE;
      int lastChunkZ = Integer.MIN_VALUE;

      double vy = 0.0D;
      double fallDistance = 0.0D;
      double lastX = 0.0D;
      double lastZ = 0.0D;
      int stuckTicks = 0;

      float yaw = 0.0F;
      float pitch = 0.0F;
      float targetYaw = 0.0F;
      float targetPitch = 0.0F;

      Location wanderTarget = null;
      Location gatherTarget = null;
      int gatherProgressTicks = 0;
      long gatherSearchStartTick = 0L;
      long nextGoalTick = 0L;
      int idleTicks = 0;
      int eatingTicks = 0;

      UUID lastAttackerUuid = null;
      long lastAttackedMs = 0L;
      long lastAttackMs = 0L;

      UUID greetTargetUuid = null;
      long greetStartTick = 0L;
      boolean greetSent = false;

      final Map<Long, Long> ignoredBlocks = new HashMap<>();

      BotSession(int slotIndex, String name, UUID uuid) {
         this.slotIndex = slotIndex;
         this.name = name;
         this.uuid = uuid;
      }

      void resetState(Location loc) {
         this.state = BotState.WANDER;
         this.ageTicks = 0L;
         this.vy = 0.0D;
         this.fallDistance = 0.0D;
         this.stuckTicks = 0;
         this.wanderTarget = null;
         this.gatherTarget = null;
         this.gatherProgressTicks = 0;
         this.nextGoalTick = 20L;
         this.idleTicks = 0;
         this.eatingTicks = 0;
         this.greetTargetUuid = null;
         this.greetSent = false;
         this.lastAttackerUuid = null;
         this.yaw = loc != null ? loc.getYaw() : 0.0F;
         this.pitch = 0.0F;
         this.targetYaw = this.yaw;
         this.targetPitch = 0.0F;
         this.lastX = loc != null ? loc.getX() : 0.0D;
         this.lastZ = loc != null ? loc.getZ() : 0.0D;
         this.lastChunkX = Integer.MIN_VALUE;
         this.lastChunkZ = Integer.MIN_VALUE;
      }
   }

   /**
    * Version-agnostic reflection bridge supporting Paper / Purpur / Leaf / Spigot 1.20.1 through 1.21.11+.
    */
   private static final class NmsBridge {
      static final class SpawnedBot {
         final Object serverPlayer;
         final Player bukkitPlayer;
         final Object connectionListener;
         final Object channel;

         SpawnedBot(Object serverPlayer, Player bukkitPlayer, Object connectionListener, Object channel) {
            this.serverPlayer = serverPlayer;
            this.bukkitPlayer = bukkitPlayer;
            this.connectionListener = connectionListener;
            this.channel = channel;
         }
      }

      private boolean initialized = false;
      private Object nmsServer;
      private Object playerList;

      private Class<?> minecraftServerCls;
      private Class<?> playerListCls;
      private Class<?> serverLevelCls;
      private Class<?> serverPlayerCls;
      private Class<?> gameProfileCls;
      private Class<?> connectionCls;
      private Class<?> packetFlowCls;
      private Object serverboundFlow;
      private Class<?> embeddedChannelCls;
      private Class<?> channelCls;
      private Class<?> channelHandlerCls;
      private Class<?> inboundAdapterCls;
      private Class<?> outboundAdapterCls;
      private Method refCountReleaseMethod;
      private Class<?> clientInfoCls;
      private Class<?> cookieCls;
      private Class<?> moverTypeCls;
      private Object moverTypeSelf;
      private Class<?> vec3Cls;
      private Constructor<?> vec3Ctor;

      private Method worldGetHandleMethod;
      private Method playerGetHandleMethod;
      private Method playerGetBukkitMethod;
      private Method placeNewPlayerMethod;
      private Method playerListRemoveMethod;
      private Method setClientLoadedMethod;
      private Method markClientLoadedMethod;
      private Method resetPositionMethod;
      private Method serverPlayerDoTickMethod;
      private Method entityMoveMethod;
      private Method entityOnGroundMethod;
      private Method entitySetRotMethod;
      private Method entitySetHeadRotMethod;
      private Method levelGetChunkSourceMethod;
      private Method chunkCacheMoveMethod;
      private Method utilGetMillisMethod;

      private Field connectionChannelField;
      private Field connectionAddressField;
      private Field connectionPreparingField;
      private Field playerConnectionField;
      private Field keepAlivePendingField;
      private Field keepAliveTimeField;
      private Field listenerClosedField;
      private Field listenerLatencyField;
      private Field playerLatencyField;
      private Field clientIsFloatingField;
      private Field aboveGroundTickCountField;
      private Field awaitingPositionField;
      private Field playerOnGroundField;

      private Method embeddedPipelineMethod;
      private Method pipelineAddLastMethod;
      private Method pipelineFireActiveMethod;
      private Method channelCloseMethod;
      private Method embeddedOutboundMethod;

      boolean init(Logger logger) {
         if (this.initialized) {
            return true;
         }
         try {
            Server craftServer = Bukkit.getServer();
            this.resolveServerAndPlayerList(craftServer);
            if (this.nmsServer == null || this.playerList == null) {
               return false;
            }
            this.minecraftServerCls = findClassHierarchy(this.nmsServer.getClass(), "MinecraftServer");
            if (this.minecraftServerCls == null) {
               this.minecraftServerCls = this.nmsServer.getClass();
            }
            this.playerListCls = findClassHierarchy(this.playerList.getClass(), "PlayerList");
            if (this.playerListCls == null) {
               this.playerListCls = this.playerList.getClass();
            }

            this.gameProfileCls = Class.forName("com.mojang.authlib.GameProfile");
            this.serverPlayerCls = firstClass("net.minecraft.server.level.ServerPlayer", "net.minecraft.server.level.EntityPlayer");
            this.serverLevelCls = firstClass("net.minecraft.server.level.ServerLevel", "net.minecraft.server.level.WorldServer");
            this.connectionCls = firstClass("net.minecraft.network.Connection", "net.minecraft.network.NetworkManager");
            this.packetFlowCls = firstClass("net.minecraft.network.protocol.PacketFlow", "net.minecraft.network.protocol.EnumProtocolDirection");
            if (this.serverPlayerCls == null || this.serverLevelCls == null || this.connectionCls == null || this.packetFlowCls == null) {
               return false;
            }

            for (Object constant : this.packetFlowCls.getEnumConstants()) {
               if ("SERVERBOUND".equals(constant.toString()) || "a".equals(constant.toString())) {
                  this.serverboundFlow = constant;
                  break;
               }
            }
            if (this.serverboundFlow == null) {
               this.serverboundFlow = this.packetFlowCls.getEnumConstants()[0];
            }

            this.embeddedChannelCls = Class.forName("io.netty.channel.embedded.EmbeddedChannel");
            this.channelCls = Class.forName("io.netty.channel.Channel");
            this.channelHandlerCls = Class.forName("io.netty.channel.ChannelHandler");
            this.inboundAdapterCls = firstClass("io.netty.channel.ChannelInboundHandlerAdapter");
            this.outboundAdapterCls = firstClass("io.netty.channel.ChannelOutboundHandlerAdapter");
            Class<?> refCountCls = firstClass("io.netty.util.ReferenceCountUtil");
            if (refCountCls != null) {
               try {
                  this.refCountReleaseMethod = refCountCls.getMethod("release", Object.class);
               } catch (Throwable ignored) {
               }
            }
            this.embeddedPipelineMethod = this.channelCls.getMethod("pipeline");
            Class<?> pipelineCls = Class.forName("io.netty.channel.ChannelPipeline");
            this.pipelineAddLastMethod = pipelineCls.getMethod("addLast", String.class, this.channelHandlerCls);
            this.pipelineFireActiveMethod = pipelineCls.getMethod("fireChannelActive");
            this.channelCloseMethod = this.channelCls.getMethod("close");
            this.embeddedOutboundMethod = this.embeddedChannelCls.getMethod("outboundMessages");

            this.clientInfoCls = firstClass("net.minecraft.server.level.ClientInformation");
            this.cookieCls = firstClass("net.minecraft.server.network.CommonListenerCookie");

            this.moverTypeCls = firstClass("net.minecraft.world.entity.MoverType", "net.minecraft.world.entity.EnumMoveType");
            if (this.moverTypeCls != null && this.moverTypeCls.getEnumConstants() != null) {
               for (Object c : this.moverTypeCls.getEnumConstants()) {
                  if ("SELF".equals(c.toString()) || "a".equals(c.toString())) {
                     this.moverTypeSelf = c;
                     break;
                  }
               }
               if (this.moverTypeSelf == null) {
                  this.moverTypeSelf = this.moverTypeCls.getEnumConstants()[0];
               }
            }
            this.vec3Cls = firstClass("net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.Vec3D");
            if (this.vec3Cls != null) {
               this.vec3Ctor = this.vec3Cls.getConstructor(double.class, double.class, double.class);
            }

            this.placeNewPlayerMethod = this.findPlaceNewPlayerMethod();
            this.playerListRemoveMethod = this.findPlayerListRemoveMethod();
            this.serverPlayerDoTickMethod = findNoArgMethod(this.serverPlayerCls, "doTick");
            this.setClientLoadedMethod = findOneArgMethod(this.serverPlayerCls, "setClientLoaded", boolean.class);
            this.playerGetBukkitMethod = findNoArgMethod(this.serverPlayerCls, "getBukkitEntity");

            if (this.moverTypeCls != null && this.vec3Cls != null) {
               this.entityMoveMethod = findMethodByParams(this.serverPlayerCls, "move", this.moverTypeCls, this.vec3Cls);
               if (this.entityMoveMethod == null) {
                  this.entityMoveMethod = findVoidMethodByExactParams(this.serverPlayerCls, this.moverTypeCls, this.vec3Cls);
               }
            }
            this.entityOnGroundMethod = findBooleanNoArgMethod(this.serverPlayerCls, "onGround", "isOnGround");
            this.playerOnGroundField = findFieldByTypeAndName(this.serverPlayerCls, boolean.class, "onGround", "onGroundNoBlocks");
            this.entitySetRotMethod = findMethodByParams(this.serverPlayerCls, "setRot", float.class, float.class);
            this.entitySetHeadRotMethod = findMethodByParams(this.serverPlayerCls, "setYHeadRot", float.class);
            this.levelGetChunkSourceMethod = findNoArgMethodByReturnName(this.serverLevelCls, "getChunkSource", "ServerChunkCache", "ChunkProviderServer");

            Class<?> listenerCls = firstClass("net.minecraft.server.network.ServerGamePacketListenerImpl", "net.minecraft.server.network.PlayerConnection");
            Class<?> commonListenerCls = firstClass("net.minecraft.server.network.ServerCommonPacketListenerImpl");
            if (listenerCls != null) {
               this.markClientLoadedMethod = findNoArgMethod(listenerCls, "markClientLoaded");
               this.resetPositionMethod = findNoArgMethod(listenerCls, "resetPosition");
               this.playerConnectionField = findFieldByAssignableType(this.serverPlayerCls, listenerCls);
               Class<?> kaBase = commonListenerCls != null ? commonListenerCls : listenerCls;
               this.keepAlivePendingField = findFieldByTypeAndName(kaBase, boolean.class, "keepAlivePending");
               this.keepAliveTimeField = findFieldByTypeAndName(kaBase, long.class, "keepAliveTime");
               this.listenerClosedField = findFieldByTypeAndName(kaBase, boolean.class, "closed");
               this.listenerLatencyField = findFieldByTypeAndName(kaBase, int.class, "latency");
               this.clientIsFloatingField = findFieldByTypeAndName(listenerCls, boolean.class, "clientIsFloating");
               this.aboveGroundTickCountField = findFieldByTypeAndName(listenerCls, int.class, "aboveGroundTickCount");
               if (this.vec3Cls != null) {
                  this.awaitingPositionField = findFieldByTypeAndName(listenerCls, this.vec3Cls, "awaitingPositionFromClient");
               }
            }
            this.playerLatencyField = findFieldByTypeAndName(this.serverPlayerCls, int.class, "latency");
            this.connectionChannelField = findFieldByAssignableType(this.connectionCls, this.channelCls);
            this.connectionAddressField = findFieldByAssignableType(this.connectionCls, SocketAddress.class);
            this.connectionPreparingField = findFieldByTypeAndName(this.connectionCls, boolean.class, "preparing");

            Class<?> utilCls = firstClass("net.minecraft.Util", "net.minecraft.SystemUtils");
            if (utilCls != null) {
               this.utilGetMillisMethod = findNoArgMethod(utilCls, "getMillis");
            }

            this.initialized = this.placeNewPlayerMethod != null;
            return this.initialized;
         } catch (Throwable t) {
            logger.warning("FakePlayerEngine NMS init warning: " + t.getMessage());
            return false;
         }
      }

      private void resolveServerAndPlayerList(Server craftServer) throws Exception {
         Object candidate = null;
         try {
            Method getServer = craftServer.getClass().getMethod("getServer");
            candidate = getServer.invoke(craftServer);
         } catch (Throwable ignored) {
         }
         // Leaf 1.21.11+ fix: CraftServer#getServer() may return DedicatedPlayerList instead of MinecraftServer
         if (candidate != null) {
            String cname = candidate.getClass().getSimpleName();
            if (cname.contains("PlayerList")) {
               this.playerList = candidate;
               try {
                  Method m = candidate.getClass().getMethod("getServer");
                  Object srv = m.invoke(candidate);
                  if (srv != null && !srv.getClass().getSimpleName().contains("PlayerList")) {
                     this.nmsServer = srv;
                  }
               } catch (Throwable ignored) {
               }
            } else {
               this.nmsServer = candidate;
            }
         }
         if (this.nmsServer == null) {
            for (Field f : craftServer.getClass().getDeclaredFields()) {
               if (findClassHierarchy(f.getType(), "MinecraftServer") != null) {
                  f.setAccessible(true);
                  this.nmsServer = f.get(craftServer);
                  if (this.nmsServer != null) {
                     break;
                  }
               }
            }
         }
         if (this.playerList == null) {
            try {
               this.playerList = craftServer.getClass().getMethod("getHandle").invoke(craftServer);
            } catch (Throwable ignored) {
            }
         }
         if (this.playerList == null && this.nmsServer != null) {
            try {
               this.playerList = this.nmsServer.getClass().getMethod("getPlayerList").invoke(this.nmsServer);
            } catch (Throwable ignored) {
            }
         }
      }

      SpawnedBot spawnFakePlayer(String name, UUID uuid, World world, Location spawnLoc, int port) throws Exception {
         Object nmsWorld = this.getWorldHandle(world);
         Object profile = this.createGameProfile(uuid, name);
         Object clientInfo = this.createDefaultClientInfo();
         Object serverPlayer = this.createServerPlayer(nmsWorld, profile, clientInfo);

         Object connection = this.connectionCls.getConstructor(this.packetFlowCls).newInstance(this.serverboundFlow);
         Object channel = this.embeddedChannelCls.getConstructor().newInstance();
         Object pipeline = this.embeddedPipelineMethod.invoke(channel);
         try {
            if (this.inboundAdapterCls != null) {
               this.pipelineAddLastMethod.invoke(pipeline, "decoder", this.inboundAdapterCls.getConstructor().newInstance());
            }
            if (this.outboundAdapterCls != null) {
               this.pipelineAddLastMethod.invoke(pipeline, "encoder", this.outboundAdapterCls.getConstructor().newInstance());
            }
         } catch (Throwable ignored) {
         }
         this.pipelineAddLastMethod.invoke(pipeline, "packet_handler", connection);
         this.pipelineFireActiveMethod.invoke(pipeline);

         if (this.connectionChannelField != null) {
            this.connectionChannelField.set(connection, channel);
         }
         if (this.connectionPreparingField != null) {
            this.connectionPreparingField.setBoolean(connection, false);
         }
         InetSocketAddress fakeAddr = new InetSocketAddress("127.0.0.1", port);
         if (this.connectionAddressField != null) {
            this.connectionAddressField.set(connection, fakeAddr);
         }

         if (this.placeNewPlayerMethod.getParameterCount() == 3) {
            Object cookie = this.createCommonListenerCookie(profile, clientInfo);
            this.placeNewPlayerMethod.invoke(this.playerList, connection, serverPlayer, cookie);
         } else {
            this.placeNewPlayerMethod.invoke(this.playerList, connection, serverPlayer);
         }

         // Restore 127.0.0.1 address in case channelActive overwrote it with EmbeddedSocketAddress
         if (this.connectionAddressField != null) {
            this.connectionAddressField.set(connection, fakeAddr);
         }

         Player bukkitPlayer = this.playerGetBukkitMethod != null
            ? (Player) this.playerGetBukkitMethod.invoke(serverPlayer)
            : Bukkit.getPlayer(uuid);
         Object listener = this.getConnectionListener(serverPlayer);
         this.markClientLoaded(serverPlayer, listener);
         this.refreshConnectionKeepAlive(serverPlayer, listener, channel, 0L, port - 40000);

         if (bukkitPlayer != null) {
            bukkitPlayer.teleport(spawnLoc);
         }
         this.clearTeleportAck(listener);
         this.resetListenerPosition(listener);
         this.drainOutboundChannel(channel);
         return new SpawnedBot(serverPlayer, bukkitPlayer, listener, channel);
      }

      void removeFakePlayer(Object serverPlayer, Object channel) {
         if (serverPlayer != null && this.playerList != null && this.playerListRemoveMethod != null) {
            try {
               this.playerListRemoveMethod.invoke(this.playerList, serverPlayer);
            } catch (Throwable ignored) {
            }
         }
         this.closeChannel(channel);
      }

      void closeChannel(Object channel) {
         this.drainOutboundChannel(channel);
         if (channel != null && this.channelCloseMethod != null) {
            try {
               this.channelCloseMethod.invoke(channel);
            } catch (Throwable ignored) {
            }
         }
      }

      void markClientLoaded(Object serverPlayer, Object listener) {
         try {
            if (serverPlayer != null && this.setClientLoadedMethod != null) {
               this.setClientLoadedMethod.invoke(serverPlayer, Boolean.TRUE);
            }
            if (listener != null && this.markClientLoadedMethod != null) {
               this.markClientLoadedMethod.invoke(listener);
            }
         } catch (Throwable ignored) {
         }
      }

      void clearTeleportAck(Object listener) {
         if (listener != null && this.awaitingPositionField != null) {
            try {
               this.awaitingPositionField.set(listener, null);
            } catch (Throwable ignored) {
            }
         }
      }

      void resetListenerPosition(Object listener) {
         if (listener != null && this.resetPositionMethod != null) {
            try {
               this.resetPositionMethod.invoke(listener);
            } catch (Throwable ignored) {
            }
         }
      }

      void doTick(Object serverPlayer) {
         if (serverPlayer != null && this.serverPlayerDoTickMethod != null) {
            try {
               this.serverPlayerDoTickMethod.invoke(serverPlayer);
            } catch (Throwable ignored) {
            }
         }
      }

      void refreshConnectionKeepAlive(Object serverPlayer, Object listener, Object channel, long ageTicks, int slotIndex) {
         try {
            if (listener != null) {
               if (this.keepAlivePendingField != null) {
                  this.keepAlivePendingField.setBoolean(listener, false);
               }
               if (this.keepAliveTimeField != null) {
                  this.keepAliveTimeField.setLong(listener, this.getNmsMillis());
               }
               if (this.listenerClosedField != null) {
                  this.listenerClosedField.setBoolean(listener, false);
               }
               if (this.clientIsFloatingField != null) {
                  this.clientIsFloatingField.setBoolean(listener, false);
               }
               if (this.aboveGroundTickCountField != null) {
                  this.aboveGroundTickCountField.setInt(listener, 0);
               }
               if (this.awaitingPositionField != null) {
                  this.awaitingPositionField.set(listener, null);
               }
               if (ageTicks % 40L == 0L) {
                  int ping = 34 + ((slotIndex * 11 + (int) (ageTicks / 40L) * 7) % 31);
                  if (this.listenerLatencyField != null) {
                     this.listenerLatencyField.setInt(listener, ping);
                  } else if (this.playerLatencyField != null && serverPlayer != null) {
                     this.playerLatencyField.setInt(serverPlayer, ping);
                  }
               }
            }
            this.drainOutboundChannel(channel);
         } catch (Throwable ignored) {
         }
      }

      private void drainOutboundChannel(Object channel) {
         if (channel == null || this.embeddedOutboundMethod == null) {
            return;
         }
         try {
            Object qObj = this.embeddedOutboundMethod.invoke(channel);
            if (qObj instanceof Queue<?>) {
               Queue<?> q = (Queue<?>) qObj;
               Object msg;
               while ((msg = q.poll()) != null) {
                  if (this.refCountReleaseMethod != null) {
                     try {
                        this.refCountReleaseMethod.invoke(null, msg);
                     } catch (Throwable ignored) {
                     }
                  }
               }
            }
         } catch (Throwable ignored) {
         }
      }

      boolean moveEntity(Object serverPlayer, double dx, double dy, double dz) {
         if (serverPlayer == null || this.entityMoveMethod == null || this.vec3Ctor == null || this.moverTypeSelf == null) {
            return false;
         }
         try {
            Object vec = this.vec3Ctor.newInstance(dx, dy, dz);
            this.entityMoveMethod.invoke(serverPlayer, this.moverTypeSelf, vec);
            return true;
         } catch (Throwable t) {
            return false;
         }
      }

      boolean isOnGround(Object serverPlayer) {
         if (serverPlayer == null) {
            return false;
         }
         try {
            if (this.entityOnGroundMethod != null) {
               return Boolean.TRUE.equals(this.entityOnGroundMethod.invoke(serverPlayer));
            }
            if (this.playerOnGroundField != null) {
               return this.playerOnGroundField.getBoolean(serverPlayer);
            }
         } catch (Throwable ignored) {
         }
         return false;
      }

      void setRotation(Object serverPlayer, float yaw, float pitch) {
         if (serverPlayer == null) {
            return;
         }
         try {
            if (this.entitySetRotMethod != null) {
               this.entitySetRotMethod.invoke(serverPlayer, yaw, pitch);
            }
            if (this.entitySetHeadRotMethod != null) {
               this.entitySetHeadRotMethod.invoke(serverPlayer, yaw);
            }
         } catch (Throwable ignored) {
         }
      }

      void chunkMove(Object serverPlayer) {
         if (serverPlayer == null || this.levelGetChunkSourceMethod == null) {
            return;
         }
         try {
            Object bukkit = this.playerGetBukkitMethod != null ? this.playerGetBukkitMethod.invoke(serverPlayer) : null;
            if (!(bukkit instanceof Player)) {
               return;
            }
            Object nmsWorld = this.getWorldHandle(((Player) bukkit).getWorld());
            Object chunkSource = this.levelGetChunkSourceMethod.invoke(nmsWorld);
            if (chunkSource == null) {
               return;
            }
            if (this.chunkCacheMoveMethod == null) {
               this.chunkCacheMoveMethod = findMethodByParams(chunkSource.getClass(), "move", this.serverPlayerCls);
               if (this.chunkCacheMoveMethod == null) {
                  this.chunkCacheMoveMethod = findVoidMethodByExactParams(chunkSource.getClass(), this.serverPlayerCls);
               }
            }
            if (this.chunkCacheMoveMethod != null) {
               this.chunkCacheMoveMethod.invoke(chunkSource, serverPlayer);
            }
         } catch (Throwable ignored) {
         }
      }

      Object getHandle(Player p) {
         if (p == null) {
            return null;
         }
         try {
            if (this.playerGetHandleMethod == null) {
               this.playerGetHandleMethod = p.getClass().getMethod("getHandle");
            }
            return this.playerGetHandleMethod.invoke(p);
         } catch (Throwable t) {
            return null;
         }
      }

      Object getConnectionListener(Object serverPlayer) {
         if (serverPlayer == null || this.playerConnectionField == null) {
            return null;
         }
         try {
            return this.playerConnectionField.get(serverPlayer);
         } catch (Throwable t) {
            return null;
         }
      }

      long getNmsMillis() {
         if (this.utilGetMillisMethod != null) {
            try {
               return ((Number) this.utilGetMillisMethod.invoke(null)).longValue();
            } catch (Throwable ignored) {
            }
         }
         return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
      }

      private Object getWorldHandle(World world) throws Exception {
         if (this.worldGetHandleMethod == null) {
            this.worldGetHandleMethod = world.getClass().getMethod("getHandle");
         }
         return this.worldGetHandleMethod.invoke(world);
      }

      private Object createGameProfile(UUID uuid, String name) throws Exception {
         try {
            return this.gameProfileCls.getConstructor(UUID.class, String.class).newInstance(uuid, name);
         } catch (NoSuchMethodException e) {
            // authlib 7+ record fallback (UUID, String, PropertyMap)
            Class<?> propMapCls = Class.forName("com.mojang.authlib.properties.PropertyMap");
            Object emptyProps = null;
            try {
               Field emptyField = propMapCls.getField("EMPTY");
               emptyProps = emptyField.get(null);
            } catch (Throwable ignored) {
            }
            if (emptyProps == null) {
               try {
                  emptyProps = propMapCls.getConstructor().newInstance();
               } catch (Throwable ignored) {
               }
            }
            return this.gameProfileCls.getConstructor(UUID.class, String.class, propMapCls).newInstance(uuid, name, emptyProps);
         }
      }

      private Object createDefaultClientInfo() {
         if (this.clientInfoCls == null) {
            return null;
         }
         try {
            for (Method m : this.clientInfoCls.getMethods()) {
               if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == this.clientInfoCls) {
                  return m.invoke(null);
               }
            }
         } catch (Throwable ignored) {
         }
         return null;
      }

      private Object createServerPlayer(Object nmsWorld, Object profile, Object clientInfo) throws Exception {
         if (this.clientInfoCls != null && clientInfo != null) {
            try {
               Constructor<?> c4 = this.serverPlayerCls.getConstructor(
                  this.minecraftServerCls, this.serverLevelCls, this.gameProfileCls, this.clientInfoCls
               );
               return c4.newInstance(this.nmsServer, nmsWorld, profile, clientInfo);
            } catch (NoSuchMethodException ignored) {
            }
         }
         Constructor<?> c3 = this.serverPlayerCls.getConstructor(
            this.minecraftServerCls, this.serverLevelCls, this.gameProfileCls
         );
         return c3.newInstance(this.nmsServer, nmsWorld, profile);
      }

      private Object createCommonListenerCookie(Object profile, Object clientInfo) throws Exception {
         for (Method m : this.cookieCls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
               && m.getReturnType() == this.cookieCls
               && m.getParameterCount() == 2
               && m.getParameterTypes()[0] == this.gameProfileCls
               && m.getParameterTypes()[1] == boolean.class) {
               return m.invoke(null, profile, Boolean.FALSE);
            }
         }
         for (Constructor<?> c : this.cookieCls.getConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length >= 4 && pts[0] == this.gameProfileCls && pts[1] == int.class && pts[2] == this.clientInfoCls && pts[3] == boolean.class) {
               Object[] args = new Object[pts.length];
               args[0] = profile;
               args[1] = 35;
               args[2] = clientInfo;
               args[3] = Boolean.FALSE;
               return c.newInstance(args);
            }
         }
         throw new NoSuchMethodException("No matching CommonListenerCookie factory/constructor");
      }

      private Method findPlaceNewPlayerMethod() {
         if (this.cookieCls != null) {
            Method m3 = findMethodByParams(this.playerListCls, "placeNewPlayer", this.connectionCls, this.serverPlayerCls, this.cookieCls);
            if (m3 != null) {
               return m3;
            }
            Method sp3 = findVoidMethodByExactParams(this.playerListCls, this.connectionCls, this.serverPlayerCls, this.cookieCls);
            if (sp3 != null) {
               return sp3;
            }
         }
         Method m2 = findMethodByParams(this.playerListCls, "placeNewPlayer", this.connectionCls, this.serverPlayerCls);
         if (m2 != null) {
            return m2;
         }
         return findVoidMethodByExactParams(this.playerListCls, this.connectionCls, this.serverPlayerCls);
      }

      private Method findPlayerListRemoveMethod() {
         Method m = findMethodByParams(this.playerListCls, "remove", this.serverPlayerCls);
         if (m != null) {
            return m;
         }
         for (Method cand : this.playerListCls.getMethods()) {
            if (cand.getParameterCount() == 1 && cand.getParameterTypes()[0] == this.serverPlayerCls
               && (cand.getName().equals("remove") || cand.getName().equals("b"))) {
               return cand;
            }
         }
         return null;
      }

      private static Class<?> firstClass(String... names) {
         for (String name : names) {
            try {
               return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
         }
         return null;
      }

      private static Class<?> findClassHierarchy(Class<?> cls, String simpleName) {
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            if (simpleName.equals(cur.getSimpleName())) {
               return cur;
            }
         }
         return null;
      }

      private static Method findNoArgMethod(Class<?> cls, String name) {
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            try {
               Method m = cur.getDeclaredMethod(name);
               m.setAccessible(true);
               return m;
            } catch (NoSuchMethodException ignored) {
            }
         }
         return null;
      }

      private static Method findOneArgMethod(Class<?> cls, String name, Class<?> p0) {
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            try {
               Method m = cur.getDeclaredMethod(name, p0);
               m.setAccessible(true);
               return m;
            } catch (NoSuchMethodException ignored) {
            }
         }
         return null;
      }

      private static Method findMethodByParams(Class<?> cls, String name, Class<?>... params) {
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            try {
               Method m = cur.getDeclaredMethod(name, params);
               m.setAccessible(true);
               return m;
            } catch (NoSuchMethodException ignored) {
            }
         }
         return null;
      }

      private static Method findVoidMethodByExactParams(Class<?> cls, Class<?>... params) {
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
               if (m.getReturnType() == void.class && Arrays.equals(m.getParameterTypes(), params)) {
                  m.setAccessible(true);
                  return m;
               }
            }
         }
         return null;
      }

      private static Method findBooleanNoArgMethod(Class<?> cls, String... names) {
         for (String name : names) {
            Method m = findNoArgMethod(cls, name);
            if (m != null && m.getReturnType() == boolean.class) {
               return m;
            }
         }
         return null;
      }

      private static Method findNoArgMethodByReturnName(Class<?> cls, String preferredName, String... returnSimpleNames) {
         Method pref = findNoArgMethod(cls, preferredName);
         if (pref != null) {
            return pref;
         }
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
               if (m.getParameterCount() == 0) {
                  String rname = m.getReturnType().getSimpleName();
                  for (String cand : returnSimpleNames) {
                     if (cand.equals(rname)) {
                        m.setAccessible(true);
                        return m;
                     }
                  }
               }
            }
         }
         return null;
      }

      private static Field findFieldByTypeAndName(Class<?> cls, Class<?> type, String... preferredNames) {
         for (String name : preferredNames) {
            for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
               try {
                  Field f = cur.getDeclaredField(name);
                  if (f.getType() == type) {
                     f.setAccessible(true);
                     return f;
                  }
               } catch (NoSuchFieldException ignored) {
               }
            }
         }
         return null;
      }

      private static Field findFieldByAssignableType(Class<?> cls, Class<?> targetType) {
         for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
               if (!Modifier.isStatic(f.getModifiers()) && targetType.isAssignableFrom(f.getType())) {
                  f.setAccessible(true);
                  return f;
               }
            }
         }
         return null;
      }
   }
}

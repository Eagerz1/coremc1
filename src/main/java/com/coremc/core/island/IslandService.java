package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.CoreConfig;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.scheduler.TaskService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class IslandService implements IslandCreationHost {
   private final JavaPlugin plugin;
   private final Logger logger;
   private final CoreConfig config;
   private final IslandPersistence persistence;
   private final PlayerDataService playerData;
   private final ThemeService themes;
   private final IslandPlatformBuilder platformBuilder;
   private final IslandCreationCoordinator creationCoordinator;
   private final TaskService taskService;
   private final IslandRegistry registry = new IslandRegistry();
   private final Set<String> retiredCells = ConcurrentHashMap.newKeySet();
   private final Set<UUID> deletedIslandIds = ConcurrentHashMap.newKeySet();
   private final Path retiredCellsFile;
   private final IslandPurchaseJournal purchaseJournal;
   private final java.util.concurrent.atomic.AtomicBoolean purchaseInProgress = new java.util.concurrent.atomic.AtomicBoolean();
   private final MemberInviteLedger invites = new MemberInviteLedger();
   private final Set<UUID> dirtyIslands = ConcurrentHashMap.newKeySet();
   private final Set<UUID> pendingKicks = ConcurrentHashMap.newKeySet();
   private final Set<UUID> pendingLeaves = ConcurrentHashMap.newKeySet();
   private volatile boolean loaded = false;

   public IslandService(JavaPlugin plugin, CoreConfig config, IslandDataStore store, PlayerDataService playerData, ThemeService themes, TaskService taskService, Path islandFolder) {
      this.plugin = plugin;
      this.logger = plugin.getLogger();
      this.config = config;
      this.persistence = new IslandPersistence(store);
      this.playerData = playerData;
      this.themes = themes;
      this.platformBuilder = new IslandPlatformBuilder(this.logger);
      this.taskService = taskService;
      this.creationCoordinator = new IslandCreationCoordinator(
              config, persistence, platformBuilder, this.logger, this);
      this.retiredCellsFile = islandFolder.resolve("deleted-cells.txt");
      this.purchaseJournal = new IslandPurchaseJournal(plugin.getDataFolder().toPath().resolve("data/island-purchase.yml"));
   }

   public void start() {
      int spacing = this.config.islandSpacing();
      this.persistence.execute(() -> {
         List<Island> loadedIslands;
         List<Island> persistedIslands;
         try {
            this.loadRetiredCells();
            loadedIslands = this.discardRetiredIslandRecords(new ArrayList(this.persistence.loadAll()), spacing);
            // Resolve purchase journals only against live islands; never charge for a tombstoned record.
            this.recoverPendingPurchase(loadedIslands);
            persistedIslands = loadedIslands;
         } catch (RuntimeException | IOException exception) {
            this.logger.log(Level.SEVERE, "Failed to load islands; island commands remain unavailable.", exception);
            return;
         }

         this.runCreationCallback(() -> this.recoverAndPublish(persistedIslands, spacing), "load island registry");
      });
   }

   private void recoverPendingPurchase(List<Island> persistedIslands) throws IOException {
      Optional<IslandPurchaseJournal.Pending> pending = this.purchaseJournal.load();
      if (pending.isEmpty()) return;
      IslandPurchaseJournal.Pending transaction = pending.get();
      Island island = persistedIslands.stream()
            .filter(candidate -> candidate.islandId().equals(transaction.islandId()))
            .findFirst().orElseThrow(() -> new IOException("Pending island purchase refers to missing island " + transaction.islandId()));
      PlayerProfile profile;
      try {
         profile = this.playerData.loadForPersistence(transaction.playerId()).get(10L, TimeUnit.SECONDS)
               .orElseThrow(() -> new IOException("Cannot load profile for pending island purchase " + transaction.id()));
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while loading profile for island purchase " + transaction.id(), exception);
      } catch (ExecutionException | TimeoutException exception) {
         throw new IOException("Could not load profile for island purchase " + transaction.id(), exception);
      }
      if (!transaction.id().equals(profile.lastIslandPurchaseId())) {
         profile.lastIslandPurchaseId(transaction.id());
         ((CoreMCPlugin)this.plugin).economy().setBalance(profile, Currency.SKY_TOKENS, transaction.balanceAfter());
      }
      if (transaction.kind().equals("upgrade")) {
         int current = island.upgrades().getOrDefault(transaction.key(), 0);
         if (current < transaction.tier()) island.setUpgradeTier(transaction.key(), transaction.tier());
      } else {
         int current = island.buffs().getOrDefault(transaction.key(), 0);
         if (current < transaction.tier()) island.setBuffTier(transaction.key(), transaction.tier());
      }
      try {
         if (!this.playerData.persistAndWait(profile).get(10L, TimeUnit.SECONDS)) {
            throw new IOException("Profile save failed while recovering purchase " + transaction.id());
         }
         this.persistence.saveSnapshot(island.toMap());
         this.purchaseJournal.clear();
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while recovering island purchase " + transaction.id(), exception);
      } catch (ExecutionException | TimeoutException exception) {
         throw new IOException("Could not save profile while recovering island purchase " + transaction.id(), exception);
      }
      this.logger.info("Recovered interrupted island purchase " + transaction.id() + ".");
   }
   private void recoverAndPublish(List<Island> persistedIslands, int spacing) {
      try {
         validatePersistedIslands(persistedIslands, spacing);
      } catch (IllegalStateException exception) {
         this.logger.log(Level.SEVERE, "Island data contains conflicting owners, members, IDs, or grid cells; island commands remain unavailable until the data is repaired.", exception);
         return;
      }

      List<Island> pending = persistedIslands.stream().filter(Island::creationPending).toList();

      try {
         for(Island island : pending) {
            World world = Bukkit.getWorld(island.worldName());
            if (world == null) {
               throw new IllegalStateException("Island world '" + island.worldName() + "' is not loaded");
            }

            IslandTheme theme = this.themes.theme(island.theme()).orElse(null);
            if (theme == null) {
               Logger var10000 = this.logger;
               String var10001 = island.theme();
               var10000.warning("Missing theme '" + var10001 + "' while recovering island " + String.valueOf(island.islandId()) + "; using the default starter platform.");
            }

            this.platformBuilder.generate(world, island, theme);
            island.creationPending(false);
         }
      } catch (RuntimeException exception) {
         this.logger.log(Level.SEVERE, "Could not recover pending island creation; island commands remain unavailable.", exception);
         return;
      }

      if (pending.isEmpty()) {
         this.publishLoadedIslands(persistedIslands, spacing);
      } else {
         try {
            this.persistence.execute(() -> {
               try {
                  for(Island island : pending) {
                     this.persistence.save(island);
                  }
               } catch (IOException exception) {
                  pending.forEach((islandx) -> islandx.creationPending(true));
                  this.logger.log(Level.SEVERE, "Could not persist recovered island creations; island commands remain unavailable.", exception);
                  return;
               }

               this.runCreationCallback(() -> this.publishLoadedIslands(persistedIslands, spacing), "publish recovered island registry");
            });
         } catch (RejectedExecutionException exception) {
            pending.forEach((islandx) -> islandx.creationPending(true));
            this.logger.log(Level.WARNING, "Island storage stopped before recovered islands could be saved.", exception);
         }

      }
   }

   private void publishLoadedIslands(List<Island> persistedIslands, int spacing) {
      for(Island island : persistedIslands) {
         this.registry.register(island, spacing);
      }

      Logger var10000 = this.logger;
      int var10001 = this.registry.size();
      var10000.info("Loaded " + var10001 + " island(s) (" + this.retiredCells.size() + " retired cell(s) reserved).");
      this.loaded = true;

      for(Player player : Bukkit.getOnlinePlayers()) {
         this.playerData.profileOf(player.getUniqueId()).ifPresent(this::reconcileAssociation);
      }

   }

   private static void validatePersistedIslands(List<Island> persistedIslands, int spacing) {
      Set<UUID> islandIds = new HashSet();
      Set<String> cells = new HashSet();
      Map<UUID, UUID> playerAssignments = new HashMap();

      for(Island island : persistedIslands) {
         if (!islandIds.add(island.islandId())) {
            throw new IllegalStateException("Duplicate island ID " + String.valueOf(island.islandId()));
         }

         int[] cell = island.gridCell(spacing);
         String cellKey = GridAssigner.key(cell[0], cell[1], island.worldName());
         if (!cells.add(cellKey)) {
            throw new IllegalStateException("Multiple islands resolve to grid cell " + cellKey);
         }

         UUID ownerConflict = (UUID)playerAssignments.putIfAbsent(island.owner(), island.islandId());
         if (ownerConflict != null) {
            String var13 = String.valueOf(island.owner());
            throw new IllegalStateException("Player " + var13 + " owns multiple islands (" + String.valueOf(ownerConflict) + ", " + String.valueOf(island.islandId()) + ")");
         }

         for(UUID member : island.members()) {
            UUID assignmentConflict = (UUID)playerAssignments.putIfAbsent(member, island.islandId());
            if (assignmentConflict != null) {
               String var10002 = String.valueOf(member);
               throw new IllegalStateException("Player " + var10002 + " is assigned to multiple islands (" + String.valueOf(assignmentConflict) + ", " + String.valueOf(island.islandId()) + ")");
            }
         }
      }

   }

   public boolean isIslandWorld(String worldName) {
      return this.config.islandWorldName().equals(worldName);
   }

   private void loadRetiredCells() throws IOException {
      if (Files.exists(this.retiredCellsFile, new LinkOption[0])) {
         for(String line : Files.readAllLines(this.retiredCellsFile)) {
            if (!line.isBlank()) {
               if (line.startsWith("island:")) {
                  try {
                     this.deletedIslandIds.add(UUID.fromString(line.substring("island:".length())));
                  } catch (IllegalArgumentException exception) {
                     throw new IOException("Invalid island tombstone in " + String.valueOf(this.retiredCellsFile) + ": " + line, exception);
                  }
               } else {
                  this.retiredCells.add(line);
               }
            }
         }

      }
   }

   private List<Island> discardRetiredIslandRecords(List<Island> persisted, int spacing) {
      List<Island> retained = new ArrayList(persisted.size());

      for(Island island : persisted) {
         int[] cell = island.gridCell(spacing);
         String cellKey = GridAssigner.key(cell[0], cell[1], island.worldName());
         if (!this.deletedIslandIds.contains(island.islandId()) && !this.retiredCells.contains(cellKey)) {
            retained.add(island);
         } else {
            try {
               this.persistence.delete(island.owner());
               Logger var10000 = this.logger;
               String var10001 = String.valueOf(island.islandId());
               var10000.info("Removed tombstoned island file " + var10001 + " for retired cell " + cellKey);
            } catch (IOException exception) {
               this.logger.log(Level.WARNING, "Could not remove tombstoned island file " + String.valueOf(island.islandId()), exception);
            }
         }
      }

      return retained;
   }

   private void persistRetiredCells() throws IOException {
      Path parent = this.retiredCellsFile.getParent();
      if (parent != null) {
         Files.createDirectories(parent);
      }

      Path temporary = this.retiredCellsFile.resolveSibling(String.valueOf(this.retiredCellsFile.getFileName()) + ".tmp");
      List<String> sorted = new ArrayList(this.retiredCells);

      for(UUID deletedIslandId : this.deletedIslandIds) {
         sorted.add("island:" + String.valueOf(deletedIslandId));
      }

      Collections.sort(sorted);
      String contents = sorted.isEmpty() ? "" : String.join(System.lineSeparator(), sorted) + System.lineSeparator();
      Files.writeString(temporary, contents, StandardCharsets.UTF_8);

      try {
         Files.move(temporary, this.retiredCellsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException var6) {
         Files.move(temporary, this.retiredCellsFile, StandardCopyOption.REPLACE_EXISTING);
      }

   }

   public boolean isLoaded() {
      return this.loaded;
   }

   public Optional<Island> ownedIsland(UUID owner) {
      return this.registry.ownedIsland(owner);
   }

   public Optional<Island> islandOf(UUID player) {
      return this.registry.islandOf(player);
   }

   public Optional<Island> islandById(UUID islandId) {
      return this.registry.islandById(islandId);
   }

   public Optional<Island> islandAt(String worldName, int x, int z) {
      int spacing = this.config.islandSpacing();
      return this.registry.islandAt(worldName, x, z, spacing, island -> this.containsBlock(island, x, z));
   }

   public boolean isCreationPending(Island island) {
      if (island.creationPending()) return true;
      return this.creationCoordinator.isPending(island);
   }

   public Optional<Island> islandAtForProtection(String worldName, int x, int z) {
      Optional<Island> published = this.islandAt(worldName, x, z);
      if (published.isPresent()) {
         return published;
      }
      return Optional.ofNullable(this.creationCoordinator.pendingIslandAt(worldName, x, z));
   }
   static List<int[]> candidateCells(int x, int z, int spacing) {
      int i = Math.floorDiv(x, spacing);
      int j = Math.floorDiv(z, spacing);
      return List.of(new int[]{i, j}, new int[]{i + 1, j}, new int[]{i, j + 1}, new int[]{i + 1, j + 1});
   }

   public int islandCount() {
      return this.registry.size();
   }

   public List<Island> allIslands() {
      return this.registry.allIslands();
   }

   public Optional<World> islandWorld() {
      return Optional.ofNullable(Bukkit.getWorld(this.config.islandWorldName()));
   }

   @Override
   public boolean registryReady() {
      return this.loaded;
   }

   @Override
   public boolean hasIsland(UUID owner) {
      return this.islandOf(owner).isPresent();
   }

   @Override
   public boolean hasPendingMembershipWrite(UUID owner) {
      return this.pendingKicks.contains(owner) || this.pendingLeaves.contains(owner);
   }

   @Override
   public Set<String> occupiedCells() {
      Set<String> occupied = new HashSet(this.registry.occupiedCells());
      occupied.addAll(this.retiredCells);
      return occupied;
   }

   @Override
   public void registerCreated(Island island) {
      this.registry.register(island, this.config.islandSpacing());
   }

   @Override
   public void associateOwner(Island island) {
      this.setAssociation(island.owner(), island.islandId());
   }

   @Override
   public void scheduleCreationCallback(Runnable callback, String operation) {
      this.runCreationCallback(callback, operation);
   }

   public int memberCapacity(Island island) {
      return this.config.islandMemberSlots() + (Integer)island.upgrades().getOrDefault("member-slots", 0);
   }

   public int effectiveBorder(Island island) {
      int tier = (Integer)island.upgrades().getOrDefault("border", 0);
      List<Integer> sizes = this.config.upgradeBorderSizes();
      return !sizes.isEmpty() ? (Integer)sizes.get(Math.min(tier, sizes.size() - 1)) : island.borderSize() + tier * this.config.upgradeBorderStepBlocks();
   }

   public boolean containsBlock(Island island, int x, int z) {
      int half = this.effectiveBorder(island) / 2;
      return x >= island.centerX() - half && x < island.centerX() + half && z >= island.centerZ() - half && z < island.centerZ() + half;
   }

   public boolean purchaseUpgrade(Player player, Island island, String upgradeId) {
      synchronized (island) {
         int tier = island.upgrades().getOrDefault(upgradeId, 0);
         int maxTier = this.config.upgradeMaxTier(upgradeId);
         if (tier >= maxTier) {
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.upgrade.maxed", Map.of());
            return false;
         }
         OptionalLong cost = this.config.upgradeCost(upgradeId, tier);
         if (cost.isEmpty()) {
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.upgrade.maxed", Map.of());
            return false;
         }
         for (Map.Entry<String, Integer> requirement : this.config.upgradeRequiresAt(upgradeId, tier + 1).entrySet()) {
            if (island.upgrades().getOrDefault(requirement.getKey(), 0) < requirement.getValue()) {
               ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.upgrade.locked", Map.of(
                     "track", UpgradeCatalog.displayOf(requirement.getKey()), "tier", String.valueOf(requirement.getValue())));
               return false;
            }
         }
         PlayerProfile profile = this.playerData.profileOf(player.getUniqueId()).orElse(null);
         if (profile == null) return false;
         int needIsland = this.config.upgradeRequiresIslandLevel(upgradeId);
         if (needIsland > 0 && island.level() < needIsland) {
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.upgrade.locked-island", Map.of(
                  "level", String.valueOf(needIsland), "yours", String.valueOf(island.level())));
            return false;
         }
         int needRole = this.config.upgradeRequiresRoleLevel(upgradeId);
         if (needRole > 0) {
            int haveRole = ((CoreMCPlugin)this.plugin).roles().maxRoleLevel(profile);
            if (haveRole < needRole) {
               ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.upgrade.locked-role", Map.of(
                     "level", String.valueOf(needRole), "yours", String.valueOf(haveRole)));
               return false;
            }
         }
         long price = cost.getAsLong();
         if (price < 0L) {
            this.logger.warning("Ignoring negative configured island upgrade price for " + upgradeId);
            return false;
         }
         return this.queuePurchase(player, profile, island, "upgrade", upgradeId, tier + 1, price, () -> {
            ((CoreMCPlugin)this.plugin).upgradeEffects().onPurchased(island, upgradeId, tier + 1);
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.upgrade.bought", Map.of(
                  "tier", String.valueOf(tier + 1), "max", String.valueOf(maxTier), "price", String.valueOf(price)));
            if (player.isOnline()) ((CoreMCPlugin)this.plugin).gui().refresh(player);
         });
      }
   }

   public boolean purchaseBuff(Player player, Island island, String buffId) {
      synchronized (island) {
         int tier = island.buffs().getOrDefault(buffId, 0);
         int maxTier = this.config.buffMaxTier(buffId);
         if (tier >= maxTier) {
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.buff.maxed", Map.of());
            return false;
         }
         OptionalLong cost = this.config.buffCost(buffId, tier);
         if (cost.isEmpty()) {
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.buff.maxed", Map.of());
            return false;
         }
         PlayerProfile profile = this.playerData.profileOf(player.getUniqueId()).orElse(null);
         if (profile == null) return false;
         long price = cost.getAsLong();
         if (price < 0L) {
            this.logger.warning("Ignoring negative configured island buff price for " + buffId);
            return false;
         }
         return this.queuePurchase(player, profile, island, "buff", buffId, tier + 1, price, () -> {
            ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.buff.bought", Map.of(
                  "buff", BuffCatalog.displayOf(buffId), "tier", String.valueOf(tier + 1),
                  "max", String.valueOf(maxTier), "price", String.valueOf(price)));
            if (player.isOnline()) ((CoreMCPlugin)this.plugin).gui().refresh(player);
         });
      }
   }
   private boolean queuePurchase(Player player, PlayerProfile profile, Island island, String kind,
                                 String key, int tier, long price, Runnable onCommitted) {
      if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Island purchases must start on the main thread");
      if (!this.purchaseInProgress.compareAndSet(false, true)) {
         ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.purchase-pending", Map.of());
         return false;
      }
      String transactionId = UUID.randomUUID().toString();
      long balanceAfter;
      Map<String, Object> snapshot;
      synchronized (island) {
         synchronized (profile) {
            long balance = profile.balanceOf(Currency.SKY_TOKENS);
            if (balance < price) {
               this.purchaseInProgress.set(false);
               ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player,
                     kind.equals("upgrade") ? "island.upgrade.insufficient" : "island.buff.insufficient",
                     Map.of("price", String.valueOf(price)));
               return false;
            }
            balanceAfter = balance - price;
            try {
               this.purchaseJournal.write(new IslandPurchaseJournal.Pending(transactionId, player.getUniqueId(),
                     island.islandId(), kind, key, tier, balanceAfter));
            } catch (IOException exception) {
               this.purchaseInProgress.set(false);
               this.logger.log(Level.SEVERE, "Could not write island purchase journal; purchase was not applied.", exception);
               ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.purchase-save-failed", Map.of());
               return false;
            }
            profile.lastIslandPurchaseId(transactionId);
            ((CoreMCPlugin)this.plugin).economy().setBalance(profile, Currency.SKY_TOKENS, balanceAfter);
            if (kind.equals("upgrade")) island.setUpgradeTier(key, tier);
            else island.setBuffTier(key, tier);
            snapshot = island.toMap();
         }
      }
      ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.purchase-pending", Map.of());
      try {
         this.persistence.execute(() -> {
            IOException lastFailure = null;
            boolean committed = false;
            for (int attempt = 1; attempt <= 3 && !committed; attempt++) {
               try {
                  if (!this.playerData.persistAndWait(profile).get(10L, TimeUnit.SECONDS)) {
                     throw new IOException("Player profile write failed for island purchase " + transactionId);
                  }
                  this.persistence.saveSnapshot(snapshot);
                  this.purchaseJournal.clear();
                  committed = true;
               } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  lastFailure = new IOException("Interrupted while saving island purchase " + transactionId, exception);
                  break;
               } catch (ExecutionException | TimeoutException | IOException exception) {
                  lastFailure = exception instanceof IOException ioException ? ioException
                        : new IOException("Could not durably save island purchase " + transactionId, exception);
               }
            }
            if (committed) {
               this.purchaseInProgress.set(false);
               this.runCreationCallback(onCommitted, "finish island purchase");
            } else {
               this.logger.log(Level.SEVERE, "Island purchase remains journaled and will be recovered at restart: " + transactionId, lastFailure);
               this.runCreationCallback(() -> ((CoreMCPlugin)this.plugin).messages().sendPrefixed(
                     player, "island.purchase-recovery-pending", Map.of()), "report pending island purchase recovery");
            }
         });
      } catch (RejectedExecutionException exception) {
         this.logger.log(Level.SEVERE, "Island purchase remains journaled and will be recovered at restart: " + transactionId, exception);
         ((CoreMCPlugin)this.plugin).messages().sendPrefixed(player, "island.purchase-recovery-pending", Map.of());
      }
      return true;
   }
   public Map<String, String> placeholdersOf(UUID player) {
      Optional<Island> island = this.islandOf(player);
      if (island.isEmpty()) {
         return Map.of("island_level", "-", "island_border", "-", "island_members", "-", "island_owner", "-");
      } else {
         Island value = (Island)island.get();
         return Map.of("island_level", String.valueOf(value.level()), "island_border", this.effectiveBorder(value) + "x" + this.effectiveBorder(value), "island_members", String.valueOf(value.members().size()), "island_owner", this.nameOf(value.owner()));
      }
   }

   private String nameOf(UUID uuid) {
      Player online = Bukkit.getPlayer(uuid);
      if (online != null) {
         return online.getName();
      } else {
         OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
         return offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
      }
   }

   public CreateResult createIsland(Player player, Consumer<Island> onCreated) {
      return this.createIsland(player, (IslandTheme)null, onCreated);
   }

   public CreateResult createIsland(Player player, IslandTheme theme, Consumer<Island> onCreated) {
      return this.createIsland(player, theme, onCreated, (failure) -> this.logger.log(Level.SEVERE, "Failed to create island for " + String.valueOf(player.getUniqueId()), failure));
   }

   public CreateResult createIsland(Player player, IslandTheme theme, Consumer<Island> onCreated, Consumer<Throwable> onFailure) {
      return this.creationCoordinator.createIsland(player, theme, onCreated, onFailure);
   }

   private void runCreationCallback(Runnable callback, String operation) {
      try {
         this.taskService.run(callback);
      } catch (RuntimeException exception) {
         this.logger.log(Level.WARNING, "Could not schedule island creation callback to " + operation, exception);
      }

   }

   public void deleteIsland(UUID owner) throws IOException {
      if (this.purchaseInProgress.get()) {
         throw new IOException("An island purchase is still being saved; try deleting the island again shortly.");
      }
      Island removed = (Island)this.registry.ownedIsland(owner).orElse(null);
      if (removed == null) {
         throw new IllegalStateException("Owner has no island");
      } else {
         int spacing = this.config.islandSpacing();
         int[] cell = removed.gridCell(spacing);
         String cellKey = GridAssigner.key(cell[0], cell[1], removed.worldName());
         boolean newlyRetired = this.retiredCells.add(cellKey);
         boolean newlyDeleted = this.deletedIslandIds.add(removed.islandId());

         try {
            this.persistRetiredCells();
         } catch (IOException exception) {
            if (newlyRetired) {
               this.retiredCells.remove(cellKey);
            }

            if (newlyDeleted) {
               this.deletedIslandIds.remove(removed.islandId());
            }

            throw exception;
         }

         if (!this.registry.remove(removed, spacing)) {
            throw new IllegalStateException("Island changed while deletion was being committed");
         } else {
            this.invites.purgeIsland(removed.islandId());
            ((CoreMCPlugin)this.plugin).islandActivity().purgeIsland(removed.islandId());
            this.dirtyIslands.remove(removed.islandId());
            List<UUID> scrubTargets = new ArrayList();
            scrubTargets.add(owner);
            scrubTargets.addAll(removed.members());
            Player ownerPlayer = Bukkit.getPlayer(owner);
            if (ownerPlayer != null) {
               this.rescueIfStranded(ownerPlayer, removed);
            }

            for(UUID member : removed.members()) {
               Player online = Bukkit.getPlayer(member);
               if (online != null) {
                  this.rescueIfStranded(online, removed);
                  ((CoreMCPlugin)this.plugin).messages().sendPrefixed(online, "island.deleted-member", Map.of());
               }
            }

            try {
               this.persistence.execute(() -> {
                  for(UUID player : scrubTargets) {
                     this.playerData.clearIslandAssociationIfMatches(player, removed.islandId());
                  }

                  try {
                     this.persistence.delete(owner);
                     Logger var10000 = this.logger;
                     String var10001 = String.valueOf(removed.islandId());
                     var10000.info("Deleted island " + var10001 + " of " + String.valueOf(owner) + " (" + scrubTargets.size() + " association(s) scrubbed, cell retired).");
                  } catch (IOException exception) {
                     this.logger.log(Level.SEVERE, "Failed to remove tombstoned island file of " + String.valueOf(owner), exception);
                  }

               });
            } catch (RejectedExecutionException exception) {
               this.logger.log(Level.WARNING, "Island cleanup queued after shutdown; tombstone remains durable.", exception);
            }

         }
      }
   }

   public InviteResult invite(Player inviter, Player target) {
      Optional<Island> island = this.ownedIsland(inviter.getUniqueId());
      if (island.isEmpty()) {
         return IslandService.InviteResult.SENDER_NOT_OWNER;
      } else if (this.islandOf(target.getUniqueId()).isPresent()) {
         return IslandService.InviteResult.TARGET_HAS_ISLAND;
      } else if (((Island)island.get()).members().size() >= this.memberCapacity((Island)island.get())) {
         return IslandService.InviteResult.TEAM_FULL;
      } else {
         long expiresAt = System.currentTimeMillis() + this.config.islandInviteExpirySeconds() * 1000L;
         this.invites.invite(target.getUniqueId(), ((Island)island.get()).islandId(), inviter.getUniqueId(), expiresAt);
         return IslandService.InviteResult.SENT;
      }
   }

   public AcceptResult acceptInvite(Player player) {
      MemberInviteLedger.Pending invite = this.invites.take(player.getUniqueId());
      if (invite == null) {
         return IslandService.AcceptResult.NO_INVITE;
      } else if (invite.expiresAtMillis() < System.currentTimeMillis()) {
         return IslandService.AcceptResult.EXPIRED;
      } else {
         Island island = (Island)this.registry.islandById(invite.islandId()).orElse(null);
         if (island == null) {
            return IslandService.AcceptResult.ISLAND_GONE;
         } else if ((this.pendingKicks.contains(player.getUniqueId()) || this.pendingLeaves.contains(player.getUniqueId())) || this.islandOf(player.getUniqueId()).isPresent()) {
            return IslandService.AcceptResult.ALREADY_ISLAND;
         } else if (island.members().size() >= this.memberCapacity(island)) {
            return IslandService.AcceptResult.TEAM_FULL;
         } else {
            island.addMember(player.getUniqueId());
            this.registry.putMember(player.getUniqueId(), island);
            this.setAssociation(player.getUniqueId(), island.islandId());
            this.flush(island);
            return IslandService.AcceptResult.ACCEPTED;
         }
      }
   }

   public boolean hasPendingInvite(UUID player) {
      MemberInviteLedger.Pending invite = this.invites.peek(player);
      return invite != null && invite.expiresAtMillis() >= System.currentTimeMillis();
   }

   public LeaveResult leave(Player player, Consumer<LeaveResult> completion) {
      if (!Bukkit.isPrimaryThread()) {
         throw new IllegalStateException("Island leave must run on the main thread");
      }
      UUID playerId = player.getUniqueId();
      Island island = (Island)this.registry.islandOf(playerId).orElse(null);
      if (island == null || island.roleOf(playerId) != IslandRole.MEMBER) {
         return LeaveResult.NOT_A_MEMBER;
      }
      if (!this.pendingLeaves.add(playerId)) return LeaveResult.PENDING;
      if (!island.removeMember(playerId)) {
         this.pendingLeaves.remove(playerId);
         return LeaveResult.NOT_A_MEMBER;
      }
      this.registry.removeMember(playerId, island);
      Map<String, Object> snapshot = island.toMap();
      try {
         this.persistence.execute(() -> {
            Throwable failure = null;
            try {
               this.persistence.saveSnapshot(snapshot);
               this.playerData.clearIslandAssociationIfMatches(playerId, island.islandId());
            } catch (IOException | RuntimeException exception) { failure = exception; }
            Throwable result = failure;
            this.runCreationCallback(() -> {
               this.pendingLeaves.remove(playerId);
               if (result == null) {
                  this.setAssociation(playerId, null);
                  Player online = Bukkit.getPlayer(playerId);
                  if (online != null) this.rescueIfStranded(online, island);
                  completion.accept(LeaveResult.LEFT);
               } else {
                  if (this.registry.islandById(island.islandId()).orElse(null) == island) {
                     island.addMember(playerId);
                     this.registry.putMember(playerId, island);
                     this.dirtyIslands.add(island.islandId());
                  }
                  this.logger.log(Level.SEVERE, "Failed to persist island leave for " + String.valueOf(playerId), result);
                  completion.accept(LeaveResult.SAVE_FAILED);
               }
            }, "finish island leave");
         });
      } catch (RejectedExecutionException exception) {
         this.pendingLeaves.remove(playerId);
         island.addMember(playerId);
         this.registry.putMember(playerId, island);
         return LeaveResult.SAVE_FAILED;
      }
      return LeaveResult.PENDING;
   }
   public KickResult kick(Player ownerActor, UUID target, Consumer<KickResult> completion) {
      if (!Bukkit.isPrimaryThread()) {
         throw new IllegalStateException("Island kick must run on the main thread");
      }
      Optional<Island> island = this.ownedIsland(ownerActor.getUniqueId());
      if (island.isEmpty()) return KickResult.NOT_OWNER;
      if (target.equals(ownerActor.getUniqueId())) return KickResult.CANNOT_KICK_OWNER;
      Island team = island.get();
      if (team.roleOf(target) != IslandRole.MEMBER) return KickResult.TARGET_NOT_MEMBER;
      if (!this.pendingKicks.add(target)) return KickResult.PENDING;
      if (!team.removeMember(target)) {
         this.pendingKicks.remove(target);
         return KickResult.TARGET_NOT_MEMBER;
      }
      this.registry.removeMember(target, team);
      Map<String, Object> snapshot = team.toMap();
      try {
         this.persistence.execute(() -> {
            Throwable failure = null;
            try {
               this.persistence.saveSnapshot(snapshot);
               this.playerData.clearIslandAssociationIfMatches(target, team.islandId());
            } catch (IOException | RuntimeException exception) { failure = exception; }
            Throwable result = failure;
            this.runCreationCallback(() -> {
               this.pendingKicks.remove(target);
               if (result == null) {
                  this.setAssociation(target, null);
                  Player kicked = Bukkit.getPlayer(target);
                  if (kicked != null) this.rescueIfStranded(kicked, team);
                  completion.accept(KickResult.KICKED);
               } else {
                  if (this.registry.islandById(team.islandId()).orElse(null) == team) {
                     team.addMember(target);
                     this.registry.putMember(target, team);
                     this.dirtyIslands.add(team.islandId());
                  }
                  this.logger.log(Level.SEVERE, "Failed to persist island kick for " + String.valueOf(target), result);
                  completion.accept(KickResult.SAVE_FAILED);
               }
            }, "finish island kick");
         });
      } catch (RejectedExecutionException exception) {
         this.pendingKicks.remove(target);
         team.addMember(target);
         this.registry.putMember(target, team);
         return KickResult.SAVE_FAILED;
      }
      return KickResult.PENDING;
   }

   public Location safeSpawn() {
      return ((World)Bukkit.getWorlds().get(0)).getSpawnLocation();
   }

   private void rescueIfStranded(Player player, Island island) {
      Location at = player.getLocation();
      if (at.getWorld() != null && at.getWorld().getName().equals(island.worldName())) {
         if (this.containsBlock(island, at.getBlockX(), at.getBlockZ())) {
            player.teleport(this.safeSpawn());
         }

      }
   }

   public void markDirty(Island island) {
      this.dirtyIslands.add(island.islandId());
   }

   public void flushDirty() {
      for(UUID islandId : new ArrayList<UUID>(this.dirtyIslands)) {
         if (this.dirtyIslands.remove(islandId)) {
            Island island = (Island)this.registry.islandById(islandId).orElse(null);
            if (island != null) {
               this.flush(island);
            }
         }
      }

   }

   public void flush(Island island) {
      Map<String, Object> snapshot = island.toMap();
      UUID islandId = island.islandId();

      try {
         this.persistence.execute(() -> {
            try {
               this.persistence.saveSnapshot(snapshot);
            } catch (IOException exception) {
               if (this.registry.islandById(islandId).orElse(null) == island) {
                  this.dirtyIslands.add(islandId);
               }

               this.logger.log(Level.SEVERE, "Failed to save island " + String.valueOf(islandId) + "; it will be retried.", exception);
            }

         });
      } catch (RejectedExecutionException exception) {
         if (this.registry.islandById(islandId).orElse(null) == island) {
            this.dirtyIslands.add(islandId);
         }

         this.logger.log(Level.SEVERE, "Could not queue save for island " + String.valueOf(islandId) + "; it remains dirty.", exception);
      }

   }

   public void reconcileAssociation(PlayerProfile profile) {
      if (profile.islandId() != null) {
         Island associatedIsland = (Island)this.registry.islandById(profile.islandId()).orElse(null);
         if (associatedIsland == null || associatedIsland.roleOf(profile.uuid()) == null) {
            Logger var10000 = this.logger;
            String var10001 = String.valueOf(profile.uuid());
            var10000.warning("Clearing stale island association on profile " + var10001 + " (island " + String.valueOf(profile.islandId()) + " does not list this player).");
            profile.islandId((UUID)null);
            this.playerData.persistAfterEconomyChange(profile, true);
         }

      }
   }

   private void setAssociation(UUID player, UUID islandId) {
      this.playerData.profileOf(player).ifPresent((profile) -> {
         profile.islandId(islandId);
         this.playerData.persistAfterEconomyChange(profile, true);
      });
   }

   public void teleportHome(Player player, Island island) {
      World world = Bukkit.getWorld(island.worldName());
      if (world == null) {
         throw new IllegalStateException("Island world '" + island.worldName() + "' is not loaded");
      } else {
         player.teleport(new Location(world, island.homeX(), island.homeY(), island.homeZ()));
      }
   }

   public void shutdown() {
      Map<UUID, Map<String, Object>> shutdownSnapshots = new LinkedHashMap();

      for(Island island : this.registry.allById()) {
         shutdownSnapshots.put(island.islandId(), island.toMap());
      }

      this.flushDirty();

      try {
         this.persistence.submit(() -> {
            for(Map.Entry<UUID, Map<String, Object>> entry : shutdownSnapshots.entrySet()) {
               UUID islandId = (UUID)entry.getKey();
               if (this.dirtyIslands.contains(islandId) && this.registry.islandById(islandId).isPresent()) {
                  try {
                     this.persistence.saveSnapshot((Map)entry.getValue());
                     this.dirtyIslands.remove(islandId);
                  } catch (IOException exception) {
                     this.logger.log(Level.SEVERE, "Final retry failed for island " + String.valueOf(islandId) + "; data remains dirty.", exception);
                  }
               }
            }

         }).get(10L, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         this.logger.log(Level.SEVERE, "Interrupted while retrying island saves during shutdown.", exception);
      } catch (TimeoutException | RejectedExecutionException | ExecutionException exception) {
         this.logger.log(Level.SEVERE, "Could not complete the bounded island save retry during shutdown.", exception);
      }

      if (!this.dirtyIslands.isEmpty()) {
         this.logger.severe("Island data remains dirty at shutdown for " + this.dirtyIslands.size() + " island(s).");
      }

      this.persistence.shutdown();

      try {
         if (!this.persistence.awaitTermination(10L, TimeUnit.SECONDS)) {
            this.logger.warning("Island executor did not terminate in 10s.");
         }
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         this.logger.log(Level.WARNING, "Interrupted while stopping island service", exception);
      }

      this.registry.clear();
      this.creationCoordinator.clearTransient();
      this.pendingKicks.clear();
      this.pendingLeaves.clear();
      this.invites.clear();
   }

   public static enum CreateResult {
      CREATED,
      ALREADY_ISLAND,
      CREATION_PENDING,
      WORLD_MISSING,
      CREATION_FAILED;

      // $FF: synthetic method
      private static CreateResult[] $values() {
         return new CreateResult[]{CREATED, ALREADY_ISLAND, CREATION_PENDING, WORLD_MISSING, CREATION_FAILED};
      }
   }

   public static enum InviteResult {
      SENT,
      TARGET_HAS_ISLAND,
      SENDER_NOT_OWNER,
      TEAM_FULL;

      // $FF: synthetic method
      private static InviteResult[] $values() {
         return new InviteResult[]{SENT, TARGET_HAS_ISLAND, SENDER_NOT_OWNER, TEAM_FULL};
      }
   }

   public static enum AcceptResult {
      ACCEPTED,
      NO_INVITE,
      EXPIRED,
      ISLAND_GONE,
      TEAM_FULL,
      ALREADY_ISLAND;

      // $FF: synthetic method
      private static AcceptResult[] $values() {
         return new AcceptResult[]{ACCEPTED, NO_INVITE, EXPIRED, ISLAND_GONE, TEAM_FULL, ALREADY_ISLAND};
      }
   }

   public static enum LeaveResult {
      LEFT,
      PENDING,
      SAVE_FAILED,
      NOT_A_MEMBER;

      // $FF: synthetic method
      private static LeaveResult[] $values() {
         return new LeaveResult[]{LEFT, PENDING, SAVE_FAILED, NOT_A_MEMBER};
      }
   }

   public static enum KickResult {
      KICKED,
      PENDING,
      SAVE_FAILED,
      NOT_OWNER,
      TARGET_NOT_MEMBER,
      CANNOT_KICK_OWNER;

      // $FF: synthetic method
      private static KickResult[] $values() {
         return new KickResult[]{KICKED, PENDING, SAVE_FAILED, NOT_OWNER, TARGET_NOT_MEMBER, CANNOT_KICK_OWNER};
      }
   }
}

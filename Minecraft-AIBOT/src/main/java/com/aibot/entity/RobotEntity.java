package com.aibot.entity;

import com.aibot.ai.ActionTypes;
import com.aibot.config.BotConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The AI Robot entity — a companion that follows, fights, builds, and mines.
 * Controlled via natural language through the DeepSeek API.
 */
public class RobotEntity extends PathAwareEntity {

    // --- Static reference cache (fallback when chunk unloads) ---
    /** Cached reference to the most recent robot instance. Used as fallback in findRobotInWorld(). */
    public static java.lang.ref.SoftReference<RobotEntity> cachedRef;

    /** Convenience getter for the cached robot instance. */
    @Nullable
    public static RobotEntity getCachedInstance() {
        return cachedRef != null ? cachedRef.get() : null;
    }

    // --- DataTracker keys ---
    private static final TrackedData<Integer> ROBOT_MODE = DataTracker.registerData(RobotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> HUNGER_LEVEL = DataTracker.registerData(RobotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    // OPTIONAL_UUID handler was removed in 1.21.2+; use STRING with empty string for "no owner"
    private static final TrackedData<String> OWNER_UUID_STR = DataTracker.registerData(RobotEntity.class, TrackedDataHandlerRegistry.STRING);

    // --- Inventory ---
    private final SimpleInventory inventory = new SimpleInventory(27); // 27 slots

    // --- Action queue ---
    private final Deque<RobotAI.QueuedAction> actionQueue = new ArrayDeque<>();
    private RobotAI robotAI;

    // --- Internal state ---
    private int hungerCooldown = 0;
    private int actionCooldown = 0;
    private BlockPos currentTargetBlock = null;
    private LivingEntity currentTargetEntity = null;

    // --- Proactive chat ---
    private int proactiveChatTimer = 0;
    private int proactiveChatInterval = 6000; // ticks (5 min)
    private int combatChatterCooldown = 0; // prevent spamming during combat

    // --- Building state ---
    private List<HouseBuilder.BuildStep> currentBuild = null;
    private int buildIndex = 0;
    private int buildCooldown = 0;
    private BlockPos buildOrigin = null;
    private int buildLastReportIndex = 0;
    private int buildSkippedCount = 0;
    private boolean buildMissingWarningSent = false;
    // Resume support: saved build parameters + material waiting
    private String buildStructure = null;
    private String buildSize = null;
    private String buildMaterial = null;
    private int buildMissingWaitTicks = 0;
    private int buildResumeAttempts = 0; // guard against infinite resume loops

    public RobotEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        if (!world.isClient()) {
            cachedRef = new java.lang.ref.SoftReference<>(this);
            // Show name tag above the robot at all times
            this.setCustomName(Text.literal("§8[§d✦§8] §5AI-Bot"));
            this.setCustomNameVisible(true);
        }
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(ROBOT_MODE, RobotMode.FOLLOW.ordinal());
        builder.add(HUNGER_LEVEL, 20);
        builder.add(OWNER_UUID_STR, "");
    }

    @Override
    protected void initGoals() {
        BotConfig cfg = BotConfig.load();

        // Priority 0: urgent survival (swim, panic)
        this.goalSelector.add(0, new SwimGoal(this));

        // Priority 1: look at player / interact
        this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));

        // Priority 2: AI-driven combat with strafing (no friendly fire)
        if (cfg.isCombatEnabled()) {
            this.goalSelector.add(2, new AICombatGoal(this, 1.0D));
        }

        // Priority 3: follow owner
        this.goalSelector.add(3, new FollowOwnerGoal(this, 1.3D, 4.0F, 2.0F));

        // Priority 4: execute queued AI actions
        this.goalSelector.add(4, new ExecuteActionGoal(this));

        // Priority 5: collect nearby items
        if (cfg.isAutoCollectEnabled()) {
            this.goalSelector.add(5, new CollectItemsGoal(this, cfg.getAutoCollectRange()));
        }

        // Priority 6: wander around
        this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.8D));

        // Targeting: hostile mobs (AI-prioritized)
        if (cfg.isCombatEnabled()) {
            this.targetSelector.add(1, new AITargetGoal(this));
        }
    }

    public static DefaultAttributeContainer.Builder createRobotAttributes() {
        BotConfig config = BotConfig.load();
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, config.getRobotMaxHealth())
            .add(EntityAttributes.MOVEMENT_SPEED, 0.45)
            .add(EntityAttributes.ATTACK_DAMAGE, config.getRobotAttackDamage())
            .add(EntityAttributes.FOLLOW_RANGE, 32.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 0.5);
    }

    // ==================== Lifecycle ====================

    @Override
    public void tick() {
        super.tick();

        if (!this.getEntityWorld().isClient()) {
            // Load config once per tick (not per-call) to avoid repeated file I/O
            BotConfig cfg = BotConfig.load();

            // Persist robot position every ~5 seconds (survives chunk unloads)
            if (this.age % 100 == 0) {
                RobotWorldData data = RobotWorldData.getOrCreate((ServerWorld) this.getEntityWorld());
                data.updateRobot(this.getUuid(), this.getX(), this.getY(), this.getZ(),
                    this.getEntityWorld().getRegistryKey().getValue().toString());
                data.markDirty();
            }

            // Tick active building
            tickBuilding();

            // Hunger system
            if (cfg.isHungerEnabled() && hungerCooldown <= 0) {
                hungerCooldown = 200; // ~10 seconds
                int hunger = getHungerLevel();
                if (hunger > 0) {
                    setHungerLevel(hunger - 1);
                }
                // Damage from starvation (only if not invulnerable)
                if (getHungerLevel() <= 0 && this.age % 80 == 0 && !this.isInvulnerable()) {
                    ServerWorld serverWorld = (ServerWorld) this.getEntityWorld();
                    this.damage(serverWorld, this.getDamageSources().starve(), 1.0F);
                }
            }
            if (hungerCooldown > 0) hungerCooldown--;

            // Process action queue
            if (actionCooldown > 0) {
                actionCooldown--;
            } else if (!actionQueue.isEmpty()) {
                RobotAI.QueuedAction action = actionQueue.poll();
                executeQueuedAction(action);
                actionCooldown = 10; // small delay between actions
            }

            // Try to eat if hungry and food available
            if (cfg.isHungerEnabled() && getHungerLevel() < 15 && this.age % 100 == 0) {
                tryEatFood();
            }

            // Reset sprint when idle
            if (this.isSprinting() && this.getNavigation().isIdle()
                && this.getMode() != RobotMode.FOLLOW && this.getMode() != RobotMode.COMBAT) {
                this.setSprinting(false);
            }
            if (this.getHealth() < this.getMaxHealth() * 0.4 && this.age % 80 == 0) {
                tryEatFood();
            }

            // ── Proactive chat ──
            if (cfg.isProactiveChatEnabled()) {
                int interval = Math.max(1200, cfg.getProactiveChatInterval() * 20); // min 60s
                // Count down combat chatter cooldown
                if (combatChatterCooldown > 0) combatChatterCooldown--;

                // Event-based chatter: react to entering combat (not just idle)
                if (this.getMode() == RobotMode.COMBAT && this.getTarget() != null
                    && combatChatterCooldown <= 0) {
                    combatChatterCooldown = 600; // 30s combat chatter cooldown
                    triggerEventChat("combat", "Engaging " + this.getTarget().getName().getString());
                } else if (actionQueue.isEmpty()) {
                    // Normal idle proactive chat
                    if (proactiveChatTimer <= 0) {
                        proactiveChatTimer = interval;
                        triggerProactiveChat();
                    } else {
                        proactiveChatTimer--;
                    }
                }
            }
        } else {
            // Client-side particles for "thinking" state
            if (!actionQueue.isEmpty() && this.age % 10 == 0) {
                this.getEntityWorld().addParticleClient(
                    ParticleTypes.ELECTRIC_SPARK,
                    this.getX() + (this.random.nextDouble() - 0.5) * 0.5,
                    this.getY() + this.getHeight() + 0.2,
                    this.getZ() + (this.random.nextDouble() - 0.5) * 0.5,
                    0, 0.05, 0
                );
            }
        }
    }

    // ==================== Owner Management ====================

    public void setOwner(@Nullable PlayerEntity player) {
        if (player != null) {
            this.dataTracker.set(OWNER_UUID_STR, player.getUuid().toString());
        } else {
            this.dataTracker.set(OWNER_UUID_STR, "");
        }
    }

    /**
     * Registers this robot in the world's persistent state.
     * Called once after spawning, so the robot can be found even if its chunk unloads.
     */
    public void registerInWorld() {
        if (!this.getEntityWorld().isClient()) {
            RobotWorldData data = RobotWorldData.getOrCreate((ServerWorld) this.getEntityWorld());
            data.updateRobot(this.getUuid(), this.getX(), this.getY(), this.getZ(),
                this.getEntityWorld().getRegistryKey().getValue().toString());
            data.markDirty();
        }
    }

    @Nullable
    public UUID getOwnerUuid() {
        String uuidStr = this.dataTracker.get(OWNER_UUID_STR);
        if (uuidStr == null || uuidStr.isEmpty()) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    public PlayerEntity getOwner() {
        UUID uuid = getOwnerUuid();
        if (uuid == null) return null;
        Entity entity = this.getEntityWorld().getEntity(uuid);
        return entity instanceof PlayerEntity player ? player : null;
    }

    public boolean isOwnedBy(PlayerEntity player) {
        UUID uuid = getOwnerUuid();
        return uuid != null && uuid.equals(player.getUuid());
    }

    // ==================== Mode ====================

    public RobotMode getMode() {
        int idx = this.dataTracker.get(ROBOT_MODE);
        if (idx < 0 || idx >= RobotMode.values().length) return RobotMode.FOLLOW;
        return RobotMode.values()[idx];
    }

    public void setMode(RobotMode mode) {
        this.dataTracker.set(ROBOT_MODE, mode.ordinal());
    }

    // ==================== Hunger ====================

    public int getHungerLevel() {
        return this.dataTracker.get(HUNGER_LEVEL);
    }

    public void setHungerLevel(int level) {
        this.dataTracker.set(HUNGER_LEVEL, Math.max(0, Math.min(20, level)));
    }

    private void tryEatFood() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            FoodComponent foodComponent = stack.get(DataComponentTypes.FOOD);
            if (foodComponent != null) {
                // Eat the food
                this.setHungerLevel(Math.min(20, getHungerLevel() + foodComponent.nutrition()));
                stack.decrement(1);
                this.playSound(SoundEvents.ENTITY_GENERIC_EAT.value(), 1.0F, 1.0F);
                return;
            }
        }
    }

    // ==================== Action Queue ====================

    public void queueActions(List<RobotAI.QueuedAction> actions) {
        if (actions != null && !actions.isEmpty()) {
            this.actionQueue.addAll(actions);
        }
    }

    public int getQueueSize() {
        return actionQueue.size();
    }

    private void executeQueuedAction(RobotAI.QueuedAction action) {
        if (action == null || this.getEntityWorld().isClient()) return;

        ServerWorld serverWorld = (ServerWorld) this.getEntityWorld();
        ActionTypes type = action.type();

        switch (type) {
            case FOLLOW -> {
                setMode(RobotMode.FOLLOW);
                LivingEntity target = getOwner();
                if (target != null) {
                    this.getNavigation().startMovingTo(target, 1.0);
                }
            }
            case STAY -> {
                setMode(RobotMode.STAY);
                this.getNavigation().stop();
            }
            case ATTACK -> {
                setMode(RobotMode.COMBAT);
                LivingEntity attackTarget = findAttackTarget(action);
                if (attackTarget != null) {
                    this.setTarget(attackTarget);
                    this.getNavigation().startMovingTo(attackTarget, 1.2);
                } else {
                    // Try to find nearest hostile
                    Box box = this.getBoundingBox().expand(16);
                    List<HostileEntity> hostiles = serverWorld.getOtherEntities(this, box, e -> e instanceof HostileEntity)
                        .stream().map(e -> (HostileEntity) e).collect(java.util.stream.Collectors.toList());
                    if (!hostiles.isEmpty()) {
                        hostiles.sort(Comparator.comparingDouble(this::distanceTo));
                        this.setTarget(hostiles.get(0));
                    }
                }
            }
            case MINE -> {
                setMode(RobotMode.MINE);
                BlockPos pos = action.pos();
                if (pos == null) pos = this.getBlockPos().down(); // mine below by default
                currentTargetBlock = pos;
                // Auto teleport if target is too far
                double dist = this.squaredDistanceTo(Vec3d.ofCenter(pos));
                BotConfig cfg = BotConfig.load();
                if (cfg.isAutoTeleportEnabled() && dist > 400) { // >20 blocks
                    ServerWorld targetWorld = (ServerWorld) this.getEntityWorld();
                    this.teleport(targetWorld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                        java.util.Set.of(), this.getYaw(), this.getPitch(), true);
                }
                // Equip best tool for the job
                equipBestTool(pos);
                this.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.2);
                this.setSprinting(true);
            }
            case PLACE -> {
                setMode(RobotMode.BUILD);
                BlockPos pos = action.pos();
                if (pos == null) pos = this.getBlockPos().down();
                currentTargetBlock = pos;
                this.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.2);
                this.setSprinting(true);
            }
            case COLLECT -> {
                Box box = this.getBoundingBox().expand(8);
                List<ItemEntity> items = serverWorld.getOtherEntities(this, box, e -> e instanceof ItemEntity)
                    .stream().map(e -> (ItemEntity) e).toList();
                for (ItemEntity item : items) {
                    loot(item);
                }
            }
            case EAT -> tryEatFood();
            case GOTO -> {
                BlockPos pos = action.pos();
                if (pos != null) {
                    // Auto teleport if target is too far
                    double dist = this.squaredDistanceTo(Vec3d.ofCenter(pos));
                    BotConfig cfg = BotConfig.load();
                    if (cfg.isAutoTeleportEnabled() && dist > 400) { // >20 blocks
                        ServerWorld targetWorld = (ServerWorld) this.getEntityWorld();
                        this.teleport(targetWorld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                            java.util.Set.of(), this.getYaw(), this.getPitch(), true);
                    }
                    this.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.2);
                    this.setSprinting(true);
                }
            }
            case SCAN -> {
                // Reporting is handled by the chat handler after scan data is collected
                // For now, the robot faces the player
                PlayerEntity owner = getOwner();
                if (owner != null) {
                    this.getLookControl().lookAt(owner);
                }
            }
            case EQUIP -> {
                String itemType = action.itemName();
                equipItemByType(itemType);
            }
            case CHAT, IDLE -> {
                // Chat messages are sent by the ChatHandler, no entity action needed
            }
            case CRAFT -> {
                // Basic crafting: check if we have materials, can be extended
                String item = action.itemName();
                if (item != null) {
                    attemptCraft(item);
                }
            }
            case BUILD -> {
                startBuilding(action);
            }
            case COMMAND -> {
                // Commands are executed by ChatHandler before queuing
            }
            case TP -> {
                // Teleport to a player (by name in target) or to specified coordinates
                PlayerEntity targetPlayer = null;
                if (action.target() != null && !action.target().isEmpty()
                    && !action.target().equalsIgnoreCase("owner")) {
                    targetPlayer = serverWorld.getPlayers().stream()
                        .filter(p -> p.getName().getString().equalsIgnoreCase(action.target())
                            || p.getUuid().toString().equals(action.target()))
                        .findFirst().orElse(null);
                }
                if (targetPlayer == null) {
                    targetPlayer = getOwner(); // fallback to owner
                }
                if (targetPlayer != null) {
                    ServerWorld targetWorld = (ServerWorld) targetPlayer.getEntityWorld();
                    this.teleport(targetWorld, targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(),
                        Set.of(), targetPlayer.getYaw(), targetPlayer.getPitch(), true);
                } else if (action.pos() != null) {
                    BlockPos pos = action.pos();
                    this.teleport(serverWorld, pos.getX() + 0.5, pos.getY(), pos.getZ(),
                        Set.of(), this.getYaw(), this.getPitch(), true);
                }
            }
        }
    }

    private LivingEntity findAttackTarget(RobotAI.QueuedAction action) {
        ServerWorld serverWorld = (ServerWorld) this.getEntityWorld();
        String targetStr = action.target();
        if (targetStr == null || targetStr.equals("nearest_hostile")) {
            Box box = this.getBoundingBox().expand(16);
            List<HostileEntity> hostiles = serverWorld.getOtherEntities(this, box, e -> e instanceof HostileEntity)
                .stream().map(e -> (HostileEntity) e).toList();
            return hostiles.isEmpty() ? null : hostiles.stream().min(Comparator.comparingDouble(this::distanceTo)).orElse(null);
        }

        // Try to find by name or type
        Box box = this.getBoundingBox().expand(16);
        List<LivingEntity> entities = serverWorld.getOtherEntities(this, box,
            e -> (e instanceof LivingEntity living) && living != this && !(e instanceof PlayerEntity) && !(e instanceof RobotEntity)
        ).stream().map(e -> (LivingEntity) e).toList();

        for (LivingEntity e : entities) {
            String name = e.getName().getString().toLowerCase();
            String typeName = e.getType().getName().getString().toLowerCase();
            if (name.contains(targetStr.toLowerCase()) || typeName.contains(targetStr.toLowerCase())) {
                return e;
            }
        }
        return null;
    }

        

    /**
     * Finds and equips the best weapon (sword/axe) from inventory to main hand.
     * The weapon will be VISIBLE in the robot's right hand.
     */
    private void equipBestWeapon() {
        int bestSlot = -1;
        double bestDamage = 1.0;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            int maxDmg = stack.getMaxDamage();
            double dmg = 1.0;
            net.minecraft.item.Item item = stack.getItem();
            var itemKey = net.minecraft.registry.Registries.ITEM.getId(item);
            if (itemKey != null) {
                String name = itemKey.getPath();
                if (name.contains("sword")) {
                    if (name.contains("netherite")) dmg = 8.0;
                    else if (name.contains("diamond")) dmg = 7.0;
                    else if (name.contains("iron")) dmg = 6.0;
                    else dmg = 5.0;
                } else if (name.contains("axe") && !name.contains("pickaxe")) {
                    if (name.contains("netherite")) dmg = 10.0;
                    else if (name.contains("diamond")) dmg = 9.0;
                    else if (name.contains("iron")) dmg = 9.0;
                    else dmg = 7.0;
                }
            }
            if (dmg <= 1.0) continue; // not a weapon

            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestSlot = i;
            }
        }

        if (bestSlot > 0) {
            ItemStack weapon = inventory.getStack(bestSlot);
            inventory.setStack(bestSlot, inventory.getStack(0));
            inventory.setStack(0, weapon);
            this.equipStack(EquipmentSlot.MAINHAND, inventory.getStack(0).copy());
        } else if (bestSlot == 0) {
            this.equipStack(EquipmentSlot.MAINHAND, inventory.getStack(0).copy());
        }
    }
    private void equipBestTool(BlockPos pos) {
        BlockState state = this.getEntityWorld().getBlockState(pos);
        // Find best tool in inventory (skip broken/nearly-broken tools)
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                // Skip broken tools (survival durability check)
                int maxDmg = stack.getMaxDamage();
                if (maxDmg > 0 && stack.getDamage() >= maxDmg - 1) continue; // broken
                float speed = stack.getMiningSpeedMultiplier(state);
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot > 0) {
            // Swap to main hand (slot 0 acts as equipped hand)
            ItemStack tool = inventory.getStack(bestSlot);
            inventory.setStack(bestSlot, inventory.getStack(0));
            inventory.setStack(0, tool);
            // Visibly equip to main hand so the item appears in the robot's hand
            this.equipStack(EquipmentSlot.MAINHAND, inventory.getStack(0).copy());
        } else if (bestSlot == 0) {
            // Already in slot 0 — still need visual equip
            this.equipStack(EquipmentSlot.MAINHAND, inventory.getStack(0).copy());
        } else if (bestSlot < 0) {
            // No usable tool — equip any non-broken tool from inventory
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty()) {
                    int maxDmg = stack.getMaxDamage();
                    if (maxDmg > 0 && stack.getDamage() >= maxDmg - 1) continue;
                    if (i > 0) {
                        inventory.setStack(i, inventory.getStack(0));
                        inventory.setStack(0, stack);
                    }
                    this.equipStack(EquipmentSlot.MAINHAND, inventory.getStack(0).copy());
                    break;
                }
            }
        }
    }

    private void equipItemByType(String itemType) {
        if (itemType == null) return;
        String lower = itemType.toLowerCase();

        EquipmentSlot targetSlot = null;
        if (lower.contains("sword") || lower.contains("pickaxe") || lower.contains("axe") || lower.contains("shovel")) {
            targetSlot = EquipmentSlot.MAINHAND;
        } else if (lower.contains("helmet") || lower.contains("head")) {
            targetSlot = EquipmentSlot.HEAD;
        } else if (lower.contains("chestplate") || lower.contains("chest") || lower.contains("body")) {
            targetSlot = EquipmentSlot.CHEST;
        } else if (lower.contains("leggings") || lower.contains("legs")) {
            targetSlot = EquipmentSlot.LEGS;
        } else if (lower.contains("boots") || lower.contains("feet")) {
            targetSlot = EquipmentSlot.FEET;
        }

        if (targetSlot != null) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty() && stack.getItem().getName().getString().toLowerCase().contains(lower)) {
                    this.equipStack(targetSlot, stack.copy());
                    break;
                }
            }
        }
    }

    private void attemptCraft(String itemName) {
        // Basic crafting attempt — in practice this is complex
        // For now, if we have a crafting table nearby, try to craft basic items
        this.playSound(SoundEvents.BLOCK_WOOD_PLACE, 0.5F, 1.0F);
    }

    // ==================== Building System ====================

    /**
     * Start building a structure. Generates the build plan and begins placement.
     * Searches nearby for a flat area to avoid building on steep terrain.
     * If the structure type doesn't match a template, tries custom generation.
     * If the AI also sent commands, executes them for custom detail work.
     */
    private void startBuilding(RobotAI.QueuedAction action) {
        // Try to find flat terrain within 10 blocks
        BlockPos origin = findFlatTerrain(this.getBlockPos(), 10);
        if (origin == null) origin = this.getBlockPos(); // fallback to current position

        // First: try the named structure
        currentBuild = HouseBuilder.generate(action.structure(), action.size(), action.material(), origin);

        // Second: if no match, try custom generation from description
        if ((currentBuild == null || currentBuild.isEmpty()) && action.description() != null) {
            currentBuild = HouseBuilder.generateCustom(action.description(), action.size(), action.material(), origin);
        }

        // Third: if still no plan, this is a fully custom build — use commands only
        if (currentBuild == null || currentBuild.isEmpty()) {
            // Execute any commands the AI sent (custom builds via /fill, etc.)
            if (action.commands() != null && !action.commands().isEmpty()) {
                ServerWorld world = (ServerWorld) this.getEntityWorld();
                PlayerEntity owner = getOwner();
                int successCount = 0;
                for (String cmd : action.commands()) {
                    if (cmd != null && !cmd.isBlank()) {
                        executeBuildCommand(world, owner, cmd);
                        successCount++;
                    }
                }
                if (owner != null) {
                    if (successCount > 0) {
                        owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §a✅ 自定义建造完成 §7(已执行 "
                            + successCount + " 条指令)"), false);
                    } else {
                        owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §c⚠ 建造失败：没有可执行的指令"), false);
                    }
                }
            } else if (getOwner() != null) {
                // No template matched and no commands provided — tell the player
                getOwner().sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §c⚠ 无法生成建造方案：结构类型「"
                    + (action.structure() != null ? action.structure() : "未知")
                    + "」不支持，且未提供建造指令"), false);
                getOwner().sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7💡 尝试使用更具体的描述，或使用 §d/bot §7+ 具体指令"), false);
            }
            return;
        }

        buildIndex = 0;
        buildCooldown = 0;
        buildOrigin = origin;
        buildLastReportIndex = 0;
        buildSkippedCount = 0;
        buildMissingWarningSent = false;
        buildStructure = action.structure();
        buildSize = action.size();
        buildMaterial = action.material();
        buildMissingWaitTicks = 0;
        buildResumeAttempts = 0;

        // Execute any supplementary commands (custom details on top of template)
        if (action.commands() != null && !action.commands().isEmpty()) {
            ServerWorld world = (ServerWorld) this.getEntityWorld();
            PlayerEntity owner = getOwner();
            for (String cmd : action.commands()) {
                if (cmd != null && !cmd.isBlank()) {
                    executeBuildCommand(world, owner, cmd);
                }
            }
        }

        // Set mode to BUILD so the robot doesn't wander
        setMode(RobotMode.BUILD);
        this.getNavigation().stop();

        System.out.println("[AIBot] Started building " + action.structure()
            + " (" + action.size() + ", " + action.material() + ")"
            + " at " + origin.toShortString()
            + " — " + currentBuild.size() + " blocks");
    }

    /**
     * Executes a Minecraft command for building purposes.
     * Uses the robot's command source so ~ coordinates resolve relative to the robot.
     * Handles coordinate placeholders: ~x~, ~y~, ~z~ = robot position.
     */
    private void executeBuildCommand(ServerWorld world, PlayerEntity owner, String cmdLine) {
        if (world == null || world.getServer() == null || cmdLine == null || cmdLine.isBlank()) return;

        String playerName = owner != null ? owner.getName().getString() : "@p";
        String cmd = cmdLine.replace("@p", playerName);

        // Resolve ~ coordinates relative to robot position
        cmd = resolveTildeCoords(cmd,
            String.valueOf((int) this.getX()),
            String.valueOf((int) this.getY()),
            String.valueOf((int) this.getZ()));

        // Security check
        String cmdLower = cmd.toLowerCase().trim();
        String commandName = cmdLower.contains(" ") ? cmdLower.substring(0, cmdLower.indexOf(' ')) : cmdLower;
        if (java.util.Set.of("op", "deop", "ban", "ban-ip", "kick", "stop", "restart",
            "tp", "teleport").contains(commandName)) {
            System.err.println("[AIBot] BLOCKED dangerous build command: /" + cmd);
            return;
        }

        // Execute as console
        world.getServer().getCommandManager().parseAndExecute(
            world.getServer().getCommandSource(), cmd);
    }

    /**
     * Replaces bare ~ (tilde) coordinates with absolute coords so commands like
     * /fill ~ ~ ~ ~5 ~3 ~5 work when executed from server console.
     */
    private static String resolveTildeCoords(String cmd, String rx, String ry, String rz) {
        int coordAxis = 0;
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < cmd.length()) {
            if (cmd.charAt(i) == '~') {
                int j = i + 1;
                int offset = 0;
                boolean negative = false;
                if (j < cmd.length() && cmd.charAt(j) == '-') {
                    negative = true;
                    j++;
                }
                while (j < cmd.length() && Character.isDigit(cmd.charAt(j))) {
                    offset = offset * 10 + (cmd.charAt(j) - '0');
                    j++;
                }
                if (negative) offset = -offset;

                int base = switch (coordAxis) {
                    case 0 -> Integer.parseInt(rx);
                    case 1 -> Integer.parseInt(ry);
                    default -> Integer.parseInt(rz);
                };
                result.append(base + offset);

                coordAxis = (coordAxis + 1) % 3;
                i = j;
            } else {
                result.append(cmd.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    /**
     * Searches for a reasonably flat 5×5 area within the given radius.
     * Returns null if no flat area is found.
     */
    @Nullable
    private BlockPos findFlatTerrain(BlockPos center, int radius) {
        ServerWorld world = (ServerWorld) this.getEntityWorld();
        int bestScore = Integer.MAX_VALUE;
        BlockPos bestPos = null;

        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                BlockPos candidate = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                // Find actual ground level at this XZ
                BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, candidate);

                // Check the 5×5 area around this candidate for height variance
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (int sx = -2; sx <= 2; sx++) {
                    for (int sz = -2; sz <= 2; sz++) {
                        BlockPos sample = new BlockPos(ground.getX() + sx, ground.getY(), ground.getZ() + sz);
                        BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sample);
                        minY = Math.min(minY, top.getY());
                        maxY = Math.max(maxY, top.getY());
                    }
                }
                int variance = maxY - minY;
                if (variance < bestScore) {
                    bestScore = variance;
                    bestPos = ground;
                    if (variance == 0) return bestPos; // perfectly flat
                }
            }
        }
        // Accept if variance is <= 2 blocks
        return bestScore <= 2 ? bestPos : null;
    }

    /**
     * Called every tick on the server. Places one block every few ticks.
     * Consumes blocks from the robot's inventory (survival-compatible).
     * Reports progress every ~10 blocks and warns on missing materials.
     */
    private void tickBuilding() {
        if (this.getMode() != RobotMode.BUILD) return;
        if (currentBuild == null || buildIndex >= currentBuild.size()) {
            // Building complete or no build in progress
            if (currentBuild != null && buildIndex >= currentBuild.size()) {
                PlayerEntity owner = getOwner();
                System.out.println("[AIBot] Building complete! Placed " + buildIndex + " blocks.");
                if (owner != null) {
                    String structName = buildStructure != null && !buildStructure.isBlank()
                        ? buildStructure : "custom";
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §a✅ 建造完成！ §7「"
                        + structName + "」共 " + buildIndex + " 块"), false);
                }
                clearBuildState(RobotMode.FOLLOW);
            } else if (currentBuild == null) {
                // Stuck in BUILD mode with no build plan — reset
                System.err.println("[AIBot] Stuck in BUILD mode with null build plan — resetting");
                clearBuildState(RobotMode.FOLLOW);
                PlayerEntity owner = getOwner();
                if (owner != null) {
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §e⚠ 建造状态已重置（无建造计划）"), false);
                }
            }
            return;
        }

        // Cooldown between block placements
        if (buildCooldown > 0) {
            buildCooldown--;
            return;
        }

        HouseBuilder.BuildStep step = currentBuild.get(buildIndex);
        ServerWorld world = (ServerWorld) this.getEntityWorld();
        int totalBlocks = currentBuild.size();
        PlayerEntity owner = getOwner();

        // ⭐ CREATIVE MODE CHECK: If owner is in creative, place blocks freely!
        boolean ownerIsCreative = owner != null && owner.isCreative();

        if (ownerIsCreative) {
            // Creative mode: place block directly without consuming inventory
            world.setBlockState(step.pos(), step.state());
            buildIndex++;
            buildMissingWarningSent = false;
            buildCooldown = 1; // fast placement in creative
        } else {
            // Survival mode: find and consume the matching block from inventory
            Block targetBlock = step.state().getBlock();
            int foundSlot = -1;
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                    if (bi.getBlock() == targetBlock) {
                        foundSlot = i;
                        break;
                    }
                }
            }

            if (foundSlot >= 0) {
                // Consume one block from inventory and place
                ItemStack stack = inventory.getStack(foundSlot);
                stack.decrement(1);
                world.setBlockState(step.pos(), step.state());
                buildIndex++;
                buildMissingWarningSent = false;
                buildMissingWaitTicks = 0; // reset wait counter on success
            } else {
                // ⚠️  Material missing — pause and wait (never consume wrong blocks as fallback — survival dupe bug)
                buildMissingWaitTicks++;
                if (!buildMissingWarningSent && owner != null) {
                    String blockName = targetBlock.getName().getString();
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §e⚠ 缺少材料：「"
                        + blockName + "」§7，放入我的背包后自动继续"), false);
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7💡 进度: "
                        + buildIndex + "/" + totalBlocks + " §8("
                        + (buildIndex * 100 / Math.max(1, totalBlocks)) + "%)"), false);
                    buildMissingWarningSent = true;
                } else if (owner != null && buildMissingWaitTicks > 200 && buildMissingWaitTicks % 200 == 0) {
                    String blockName = targetBlock.getName().getString();
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §e⏳ 仍在等待：「"
                        + blockName + "」§7(已等待 " + (buildMissingWaitTicks / 20) + " 秒)"), false);
                }
                // ⏱️ TIMEOUT: after 60 seconds of waiting, give up and tell the player
                if (buildMissingWaitTicks > 1200) {
                    if (owner != null) {
                        String blockName = targetBlock.getName().getString();
                        owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §c⏰ 等待超时（60秒），建造中止。缺少：「"
                            + blockName + "」"), false);
                        owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7💡 已放置 " + buildIndex + "/" + totalBlocks
                            + " 块，补充材料后输入建造指令继续"), false);
                    }
                    clearBuildState(RobotMode.FOLLOW);
                    return;
                }
                // DO NOT increment buildIndex — retry same block next tick
                buildCooldown = 20;
                return;
            }

            buildCooldown = 1;
        }

        // ── Progress report every ~10 blocks ──
        if (buildIndex - buildLastReportIndex >= 10 && owner != null) {
            int pct = buildIndex * 100 / Math.max(1, totalBlocks);
            int barWidth = 10;
            int filled = Math.min(barWidth, buildIndex * barWidth / Math.max(1, totalBlocks));
            String bar = "§a" + "█".repeat(filled) + "§8" + "░".repeat(barWidth - filled);
            owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7建造中 [" + bar + "§7] §d"
                + buildIndex + "§7/§d" + totalBlocks + " §8(" + pct + "%)"), false);
            buildLastReportIndex = buildIndex;
        }

        // Keep robot near the build site
        if (buildOrigin != null && this.squaredDistanceTo(Vec3d.ofCenter(buildOrigin)) > 25.0) {
            this.getNavigation().startMovingTo(buildOrigin.getX() + 0.5, buildOrigin.getY(), buildOrigin.getZ() + 0.5, 1.0);
        }
    }

    /**
     * Clears all building state and optionally switches mode.
     * Keeps resume parameters (buildStructure/size/material) for potential resume.
     */
    private void clearBuildState(RobotMode newMode) {
        currentBuild = null;
        buildIndex = 0;
        buildOrigin = null;
        buildLastReportIndex = 0;
        buildSkippedCount = 0;
        buildMissingWarningSent = false;
        buildMissingWaitTicks = 0;
        buildResumeAttempts = 0;
        // NOTE: buildStructure/buildSize/buildMaterial are NOT cleared
        // so the build can be resumed later with "resume" or same params
        setMode(newMode);
    }

    /**
     * Resumes a previous interrupted build if parameters were saved.
     * Gives up after 3 failed attempts to prevent infinite loops.
     */
    private boolean tryResumeBuild() {
        if (buildStructure == null || buildOrigin == null) return false;
        buildResumeAttempts++;
        if (buildResumeAttempts > 3) {
            // Give up resuming — world was modified too much
            PlayerEntity owner = getOwner();
            if (owner != null) {
                owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §c⚠ 恢复建造失败（已尝试 "
                    + buildResumeAttempts + " 次），世界变动过大"), false);
                owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7💡 请使用新的建造指令重新开始"), false);
            }
            buildStructure = null;
            buildResumeAttempts = 0;
            clearBuildState(RobotMode.FOLLOW);
            return false;
        }
        currentBuild = HouseBuilder.generate(buildStructure, buildSize, buildMaterial, buildOrigin);
        if (currentBuild == null || currentBuild.isEmpty()) {
            currentBuild = null;
            return false;
        }
        // Recalculate buildIndex: count already-placed blocks by checking the world
        // Start from the beginning and skip blocks that already exist
        buildIndex = 0;
        for (int i = 0; i < currentBuild.size(); i++) {
            var step = currentBuild.get(i);
            var existing = this.getEntityWorld().getBlockState(step.pos());
            if (existing.equals(step.state())) {
                buildIndex = i + 1;
            } else {
                break; // first missing block
            }
        }
        buildCooldown = 0;
        buildLastReportIndex = buildIndex;
        buildSkippedCount = 0;
        buildMissingWarningSent = false;
        buildResumeAttempts = 0;
        setMode(RobotMode.BUILD);
        PlayerEntity owner = getOwner();
        if (owner != null) {
            int remaining = currentBuild.size() - buildIndex;
            owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §a🔄 建造已恢复！剩余 "
                + remaining + " 块 §7(第 " + (buildIndex + 1) + "/" + currentBuild.size() + " 块)"), false);
        }
        return buildIndex < currentBuild.size();
    }

    // ==================== Proactive Chat ====================

    /**
     * Triggers a quick event-driven chat (combat, discovery, task completion).
     * Bypasses the normal proactive timer so the robot reacts immediately.
     */
    private void triggerEventChat(String event, String context) {
        PlayerEntity owner = getOwner();
        if (owner == null) return;
        BotConfig config = BotConfig.load();
        if (!config.hasApiKey()) return;

        boolean zh = config.getCommandLanguage().equals("zh");
        String prompt = switch (event) {
            case "combat" -> zh
                ? "你正在战斗，说一句简短的战斗口号或提醒玩家注意安全（1句话）。返回格式：{\"actions\":[{\"type\":\"chat\",\"message\":\"...\"}]}"
                : "You just entered combat. Say a short battle cry or warn the player. 1 sentence. Return: {\"actions\":[{\"type\":\"chat\",\"message\":\"...\"}]}";
            default -> zh
                ? "说一句话回应当前情况：" + context + "。1句话。返回格式：{\"actions\":[{\"type\":\"chat\",\"message\":\"...\"}]}"
                : "Say one sentence reacting to: " + context + ". Return: {\"actions\":[{\"type\":\"chat\",\"message\":\"...\"}]}";
        };

        com.aibot.ai.DeepSeekClient.sendProactiveChat(owner, prompt, this)
            .thenAcceptAsync(response -> {
                if (response != null && !response.isEmpty()) {
                    var parsed = com.aibot.ai.CommandParser.parse(response);
                    for (var a : parsed) {
                        if (a.type == com.aibot.ai.ActionTypes.CHAT && a.message != null) {
                            String safeMsg = com.aibot.chat.ChatFormatter.stripFormatCodes(a.message);
                            owner.sendMessage(com.aibot.chat.ChatFormatter.msg(config, "§7" + safeMsg), false);
                            return;
                        }
                    }
                }
            }, runnable -> {
                var w = this.getEntityWorld();
                if (w instanceof ServerWorld sw && sw.getServer() != null) {
                    sw.getServer().execute(runnable);
                }
            });
    }

    /**
     * Triggers a proactive chat message. Uses AI if available, otherwise falls back
     * to time-based greetings.
     */
    private void triggerProactiveChat() {
        PlayerEntity owner = getOwner();
        if (owner == null) return;

        BotConfig config = BotConfig.load();

        // Check time of day for simple greetings
        long timeOfDay = this.getEntityWorld().getTimeOfDay() % 24000;
        String timeGreeting = getTimeGreeting(timeOfDay, config);

        if (config.hasApiKey() && config.isProactiveChatEnabled()) {
            // Use AI for smart proactive chat
            String prompt = "Say something natural. Current time: " + timeGreeting + ". "
                + "Recent context: I'm at " + this.getBlockPos().toShortString() + ". "
                + "Greet, encourage, or give a tip. Keep it 1-2 sentences. "
                + "Respond as a chat action like {\"actions\":[{\"type\":\"chat\",\"message\":\"...\"}]}";

            com.aibot.ai.DeepSeekClient.sendProactiveChat(owner, prompt, this)
                .thenAcceptAsync(response -> {
                    if (response != null && !response.isEmpty()) {
                        String msg = response.trim();
                        if (msg.contains("\"message\"")) {
                            var parsed = com.aibot.ai.CommandParser.parse(response);
                            for (var a : parsed) {
                                if (a.type == com.aibot.ai.ActionTypes.CHAT && a.message != null) {
                                    String safeMsg = com.aibot.chat.ChatFormatter.stripFormatCodes(a.message);
                                    owner.sendMessage(com.aibot.chat.ChatFormatter.msg(config,
                                        "§7" + safeMsg), false);
                                    return;
                                }
                            }
                        }
                        if (!msg.equals("{}") && !msg.startsWith("{\"actions")) {
                            String safeMsg = com.aibot.chat.ChatFormatter.stripFormatCodes(msg);
                            owner.sendMessage(com.aibot.chat.ChatFormatter.msg(config,
                                "§7" + safeMsg), false);
                        }
                    }
                }, runnable -> {
                    var w = this.getEntityWorld();
                    if (w instanceof ServerWorld sw && sw.getServer() != null) {
                        sw.getServer().execute(runnable);
                    }
                });
        } else {
            // Offline mode: simple time-based greeting
            owner.sendMessage(com.aibot.chat.ChatFormatter.msg(config,
                "§7" + timeGreeting), false);
        }
    }

    /**
     * Returns a friendly greeting based on in-game time of day.
     */
    private static String getTimeGreeting(long timeOfDay, BotConfig config) {
        boolean zh = config.getCommandLanguage().equals("zh");
        if (timeOfDay < 1000) {
            return zh ? "早上好！新的一天开始了 ☀️" : "Good morning! A new day begins ☀️";
        } else if (timeOfDay < 6000) {
            return zh ? "上午好！有什么需要帮忙的吗？" : "Good morning! Need any help?";
        } else if (timeOfDay < 11000) {
            return zh ? "下午好！继续加油 💪" : "Good afternoon! Keep going 💪";
        } else if (timeOfDay < 13000) {
            return zh ? "傍晚了，注意安全哦 🌆" : "Evening! Stay safe 🌆";
        } else {
            return zh ? "晚上了！我会保护你的 🌙" : "Good night! I'll protect you 🌙";
        }
    }

    // ==================== Interaction ====================

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack heldItem = player.getStackInHand(hand);

        // Shift + right-click with empty hand to toggle ownership
        if (player.isSneaking() && heldItem.isEmpty()) {
            if (isOwnedBy(player)) {
                // Unbond: owner can release the robot
                setOwner(null);
                player.sendMessage(Text.literal("§eYou released the AI Robot."), true);
                this.playSound(SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.3F, 2.0F);
                return ActionResult.SUCCESS;
            } else if (getOwnerUuid() == null) {
                // Claim unowned robot
                setOwner(player);
                player.sendMessage(Text.literal("§aYou are now bonded with the AI Robot!"), true);
                this.playSound(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.3F, 2.0F);
                return ActionResult.SUCCESS;
            } else {
                // Already owned by someone else
                player.sendMessage(Text.literal("§cThis robot belongs to someone else!"), true);
                return ActionResult.FAIL;
            }
        }

        // Right-click with empty hand to open inventory (any player, no binding required)
        if (!player.isSneaking() && heldItem.isEmpty()) {
            if (!this.getEntityWorld().isClient()) {
                player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> new RobotScreenHandler(syncId, playerInv, this.inventory, this),
                    Text.literal("AI Robot")
                ));
            }
            return ActionResult.CONSUME;
        }

        // Right-click with item to deposit into robot's inventory (any player)
        if (!player.isSneaking() && !heldItem.isEmpty()) {
            for (int i = 0; i < inventory.size(); i++) {
                if (inventory.getStack(i).isEmpty()) {
                    inventory.setStack(i, heldItem.copy());
                    player.setStackInHand(hand, ItemStack.EMPTY);
                    this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.5F, 1.0F);
                    return ActionResult.CONSUME;
                }
            }
        }

        return super.interactMob(player, hand);
    }

    // ==================== Inventory ====================

    public SimpleInventory getInventory() {
        return inventory;
    }

    public boolean canPickUpLoot() {
        return true;
    }

    protected void loot(ItemEntity item) {
        ItemStack itemStack = item.getStack();
        if (!itemStack.isEmpty() && this.canPickUpLoot()) {
            ItemStack remainder = this.getInventory().addStack(itemStack);
            if (remainder.isEmpty()) {
                item.discard();
            } else {
                // Inventory full — put the remainder back on the item entity
                item.setStack(remainder);
            }
        }
    }

    // ==================== NBT (1.21.2+ WriteView/ReadView) ====================

    @Override
    public void writeData(WriteView writeView) {
        super.writeData(writeView);
        writeView.putInt("RobotMode", getMode().ordinal());
        writeView.putInt("HungerLevel", getHungerLevel());
        UUID ownerUuid = getOwnerUuid();
        if (ownerUuid != null) {
            writeView.putLong("OwnerUUIDMost", ownerUuid.getMostSignificantBits());
            writeView.putLong("OwnerUUIDLeast", ownerUuid.getLeastSignificantBits());
        }

        // Save inventory using codec list
        var listAppender = writeView.getListAppender("Inventory", ItemStack.CODEC);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                listAppender.add(stack);
            }
        }
    }

    @Override
    public void readData(ReadView readView) {
        super.readData(readView);
        if (readView.getOptionalInt("RobotMode").isPresent()) {
            setMode(RobotMode.values()[readView.getInt("RobotMode", 0)]);
        }
        int hunger = readView.getInt("HungerLevel", 20);
        setHungerLevel(hunger);

        if (readView.getOptionalLong("OwnerUUIDMost").isPresent()) {
            long most = readView.getLong("OwnerUUIDMost", 0);
            long least = readView.getLong("OwnerUUIDLeast", 0);
            this.dataTracker.set(OWNER_UUID_STR, new UUID(most, least).toString());
        }

        // Load inventory using codec list
        readView.getOptionalTypedListView("Inventory", ItemStack.CODEC).ifPresent(list -> {
            int slot = 0;
            for (ItemStack stack : list) {
                if (slot < inventory.size()) {
                    inventory.setStack(slot, stack);
                    slot++;
                }
            }
        });
    }

    // ==================== Combat ====================

    public boolean isInvulnerableTo(DamageSource damageSource) {
        return true;  // Immune to all damage; hunger uses the damage() override instead
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        // Let starvation bypass so the hunger system can deal damage when it calls this
        if (source.isOf(net.minecraft.entity.damage.DamageTypes.STARVE)) {
            return super.damage(world, source, amount);
        }
        return false;  // All other damage blocked
    }

    @Override
    public boolean tryAttack(ServerWorld world, Entity target) {
        boolean success = super.tryAttack(world, target);
        if (success) {
            // Trigger swing animation
            this.handSwinging = true;
            this.handSwingTicks = 10;
        }
        return success;
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        super.onDeath(damageSource);
        // Drop inventory on death
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                this.dropItem(stack, true, false);
            }
        }
        inventory.clear();
    }

    // ==================== Misc ====================

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false; // Never despawn
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    public BlockPos getCurrentTargetBlock() {
        return currentTargetBlock;
    }

    public void clearTargetBlock() {
        currentTargetBlock = null;
    }

    // ==================== Robot Mode Enum ====================

    public enum RobotMode {
        FOLLOW,
        STAY,
        COMBAT,
        BUILD,
        MINE
    }

    // ==================== Custom Goals ====================

    /**
     * Goal that executes queued AI actions from the action queue.
     */
    private static class ExecuteActionGoal extends Goal {
        private final RobotEntity robot;

        ExecuteActionGoal(RobotEntity robot) {
            this.robot = robot;
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            return robot.currentTargetBlock != null;
        }

        @Override
        public void tick() {
            if (robot.currentTargetBlock == null) return;
            BlockPos target = robot.currentTargetBlock;
            double dist = robot.squaredDistanceTo(Vec3d.ofCenter(target));

            if (dist < 9.0) { // Within 3 blocks
                RobotMode mode = robot.getMode();
                if (mode == RobotMode.MINE) {
                    // Equip best tool for the block before breaking (AI-driven)
                    if (BotConfig.load().isAutoToolEnabled()) {
                        robot.equipBestTool(target);
                    }
                    // Only break if there's actually a block there (not air)
                    ServerWorld serverWorld = (ServerWorld) robot.getEntityWorld();
                    if (!serverWorld.getBlockState(target).isAir()) {
                        serverWorld.breakBlock(target, true, robot, 0);
                        robot.playSound(SoundEvents.BLOCK_STONE_BREAK, 1.0F, 1.0F);
                    }
                    // Auto-collect nearby drops after mining
                    Box dropBox = new Box(target).expand(3);
                    var drops = serverWorld.getOtherEntities(robot, dropBox,
                        e -> e instanceof net.minecraft.entity.ItemEntity);
                    for (var drop : drops) {
                        if (drop instanceof net.minecraft.entity.ItemEntity item) {
                            robot.loot(item);
                        }
                    }
                } else if (mode == RobotMode.BUILD) {
                    // Place a block from inventory
                    for (int i = 0; i < robot.inventory.size(); i++) {
                        ItemStack stack = robot.inventory.getStack(i);
                        if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                            BlockPos placePos = target.up();
                            if (robot.getEntityWorld().getBlockState(placePos).isAir()) {
                                robot.getEntityWorld().setBlockState(placePos, blockItem.getBlock().getDefaultState());
                                stack.decrement(1);
                                robot.playSound(SoundEvents.BLOCK_STONE_PLACE, 1.0F, 1.0F);
                            }
                            break;
                        }
                    }
                }
                robot.clearTargetBlock();
            } else {
                robot.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            }
        }

        @Override
        public boolean shouldContinue() {
            return robot.currentTargetBlock != null;
        }
    }

    /**
     * Goal that follows the owner player.
     */
    private static class FollowOwnerGoal extends Goal {
        private final RobotEntity robot;
        private final double speed;
        private final float maxDistance;
        private final float minDistance;
        private int updateCountdownTicks;

        FollowOwnerGoal(RobotEntity robot, double speed, float maxDistance, float minDistance) {
            this.robot = robot;
            this.speed = speed;
            this.maxDistance = maxDistance;
            this.minDistance = minDistance;
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            if (robot.getMode() != RobotMode.FOLLOW) return false;
            PlayerEntity owner = robot.getOwner();
            if (owner == null || owner.isSpectator()) return false;
            if (robot.squaredDistanceTo(owner) < (double)(minDistance * minDistance)) return false;
            return true;
        }

        @Override
        public boolean shouldContinue() {
            if (robot.getNavigation().isIdle()) return false;
            return robot.getMode() == RobotMode.FOLLOW && robot.getOwner() != null
                && robot.squaredDistanceTo(robot.getOwner()) > (double)(minDistance * minDistance / 2);
        }

        @Override
        public void start() {
            updateCountdownTicks = 0;
        }

        @Override
        public void tick() {
            PlayerEntity owner = robot.getOwner();
            if (owner == null) return;

            robot.getLookControl().lookAt(owner, 10.0F, robot.getMaxLookPitchChange());

            // Always sprint when following and more than 2 blocks away (exceeds vanilla speed)
            robot.setSprinting(robot.distanceTo(owner) > 2.0);

            // Auto teleport if too far from owner
            BotConfig config = BotConfig.load();
            if (config.isAutoTeleportEnabled()) {
                double tpDist = config.getAutoTeleportDistance();
                if (robot.squaredDistanceTo(owner) > tpDist * tpDist) {
                    ServerWorld targetWorld = (ServerWorld) owner.getEntityWorld();
                    robot.teleport(targetWorld, owner.getX(), owner.getY(), owner.getZ(),
                        java.util.Set.of(), owner.getYaw(), owner.getPitch(), true);
                    robot.getNavigation().stop();
                    return;
                }
            }

            if (--updateCountdownTicks <= 0) {
                updateCountdownTicks = getTickCount(10);
                robot.getNavigation().startMovingTo(owner, speed);
            }
        }
    }

    /**
     * Goal that collects nearby dropped items.
     */
    private static class CollectItemsGoal extends Goal {
        private final RobotEntity robot;
        private final double range;
        private ItemEntity targetItem;

        CollectItemsGoal(RobotEntity robot, double range) {
            this.robot = robot;
            this.range = range;
            this.setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            if (robot.getMode() == RobotMode.STAY) return false;
            Box box = robot.getBoundingBox().expand(range);
            List<ItemEntity> items = robot.getEntityWorld().getOtherEntities(robot, box,
                e -> e instanceof ItemEntity item && item.isAlive() && !item.cannotPickup()
            ).stream().map(e -> (ItemEntity) e).toList();
            if (items.isEmpty()) return false;
            targetItem = items.stream().min(Comparator.comparingDouble(robot::distanceTo)).orElse(null);
            return targetItem != null;
        }

        @Override
        public boolean shouldContinue() {
            return targetItem != null && targetItem.isAlive() && !targetItem.cannotPickup()
                && robot.distanceTo(targetItem) > 1.5;
        }

        @Override
        public void tick() {
            if (targetItem != null) {
                robot.getNavigation().startMovingTo(targetItem, 1.0);
            }
        }

        @Override
        public void stop() {
            targetItem = null;
        }
    }

    // ==================== AI Combat Goal ====================

    /**
     * Enhanced AI combat with smarter positioning and reliable attacks.
     * Approaches aggressively, attacks at correct range, strafes to dodge.
     * NEVER hits players (friendly fire prevention).
     */
    private static class AICombatGoal extends Goal {
        private final RobotEntity robot;
        private final double speed;
        private int strafeTicks = 0;
        private boolean strafeRight = true;
        private int attackCooldown = 0;
        private int repathTicks = 0;
        private static final double ATTACK_RANGE_SQ = 8.0; // ~2.45 blocks

        AICombatGoal(RobotEntity robot, double speed) {
            this.robot = robot;
            this.speed = speed;
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public void start() {
            robot.equipBestWeapon();
        }

        @Override
        public boolean canStart() {
            LivingEntity target = robot.getTarget();
            if (robot.getMode() != RobotMode.COMBAT && robot.getMode() != RobotMode.FOLLOW) return false;
            if (target instanceof PlayerEntity || target instanceof RobotEntity) return false;
            return target != null && target.isAlive() && robot.squaredDistanceTo(target) < 400;
        }

        @Override
        public boolean shouldContinue() {
            LivingEntity target = robot.getTarget();
            if (target == null || !target.isAlive()) return false;
            if (target instanceof PlayerEntity || target instanceof RobotEntity) return false;
            if (robot.squaredDistanceTo(target) > 625) return false; // 25 blocks max
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = robot.getTarget();
            if (target == null || !target.isAlive()) {
                // Target gone — stop combat cleanly
                robot.setTarget(null);
                return;
            }

            double distSq = robot.squaredDistanceTo(target);
            robot.getLookControl().lookAt(target, 360.0F, 60.0F);

            // ── Attack when in range ──
            if (distSq < ATTACK_RANGE_SQ) {
                if (attackCooldown <= 0) {
                    ServerWorld sw = (ServerWorld) robot.getEntityWorld();
                    // Ensure facing target before each swing
                    robot.getLookControl().lookAt(target, 360.0F, 60.0F);
                    boolean hit = robot.tryAttack(sw, target);
                    attackCooldown = hit ? 3 : 2; // 0.5s on hit, quick retry on miss
                }
                attackCooldown--;

                // ── Smart strafing ──
                strafeTicks++;
                if (strafeTicks > 25) {
                    strafeRight = !strafeRight;
                    strafeTicks = 0;
                }
                // Strafe perpendicular to target direction
                double dx = target.getX() - robot.getX();
                double dz = target.getZ() - robot.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01) {
                    double sx = -dz / len * (strafeRight ? 1.2 : -1.2);
                    double sz = dx / len * (strafeRight ? 1.2 : -1.2);
                    // keep some forward pressure to stay in range
                    sx += dx / len * 0.5;
                    sz += dz / len * 0.5;
                    robot.getNavigation().startMovingTo(
                        robot.getX() + sx,
                        robot.getY(),
                        robot.getZ() + sz,
                        speed
                    );
                }
            } else if (distSq < 400) {
                // ── Rush toward target with sprint ──
                robot.setSprinting(true);
                repathTicks++;
                if (repathTicks > 5) {
                    robot.getNavigation().startMovingTo(target, speed * 1.1);
                    repathTicks = 0;
                }
            } else {
                robot.setTarget(null);
            }
        }

        @Override
        public void stop() {
            robot.setSprinting(false);
            robot.getNavigation().stop();
            strafeTicks = 0;
            attackCooldown = 0;
        }
    }

    // ==================== AI Target Selection ====================

    /**
     * AI-driven target prioritization. Selects the most threatening hostile mob.
     * Never targets players or non-hostile entities.
     */
    private static class AITargetGoal extends ActiveTargetGoal<HostileEntity> {
        AITargetGoal(RobotEntity robot) {
            super(robot, HostileEntity.class, true);
        }

        @Override
        protected void findClosestTarget() {
            // Use parent logic but filter out any players/robots
            super.findClosestTarget();
            LivingEntity target = this.mob.getTarget();
            if (target instanceof PlayerEntity || target instanceof RobotEntity) {
                this.mob.setTarget(null);
            }
        }
    }
}

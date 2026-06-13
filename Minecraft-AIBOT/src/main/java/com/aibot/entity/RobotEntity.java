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
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The AI Robot entity — a companion that follows, fights, builds, and mines.
 * Controlled via natural language through the DeepSeek API.
 */
public class RobotEntity extends PathAwareEntity {

    // --- Static reference cache (fallback when chunk unloads) ---
    /** Cached reference to the most recent robot instance. Used as fallback in findRobotInWorld(). */
    public static java.lang.ref.WeakReference<RobotEntity> cachedRef;

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

    public RobotEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        if (!world.isClient()) {
            cachedRef = new java.lang.ref.WeakReference<>(this);
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
        // Priority 0: urgent survival (swim, panic)
        this.goalSelector.add(0, new SwimGoal(this));

        // Priority 1: look at player / interact
        this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));

        // Priority 2: AI-driven combat with strafing (no friendly fire)
        this.goalSelector.add(2, new AICombatGoal(this, 1.0D));

        // Priority 3: follow owner
        this.goalSelector.add(3, new FollowOwnerGoal(this, 1.3D, 4.0F, 2.0F));

        // Priority 4: execute queued AI actions
        this.goalSelector.add(4, new ExecuteActionGoal(this));

        // Priority 5: collect nearby items
        this.goalSelector.add(5, new CollectItemsGoal(this, BotConfig.load().getAutoCollectRange()));

        // Priority 6: wander around
        this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.8D));

        // Targeting: hostile mobs (AI-prioritized)
        this.targetSelector.add(1, new AITargetGoal(this));
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
            if (BotConfig.load().isHungerEnabled() && hungerCooldown <= 0) {
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
            if (BotConfig.load().isHungerEnabled() && getHungerLevel() < 15 && this.age % 100 == 0) {
                tryEatFood();
            }
            // Auto-eat when health is low (survival healing)

            // Reset sprint when idle
            if (this.isSprinting() && this.getNavigation().isIdle()
                && this.getMode() != RobotMode.FOLLOW && this.getMode() != RobotMode.COMBAT) {
                this.setSprinting(false);
            }
            if (this.getHealth() < this.getMaxHealth() * 0.4 && this.age % 80 == 0) {
                tryEatFood();
            }

            // ── Proactive chat ──
            BotConfig cfg = BotConfig.load();
            if (cfg.isProactiveChatEnabled() && actionQueue.isEmpty()) {
                int interval = Math.max(1200, cfg.getProactiveChatInterval() * 20); // min 60s
                if (proactiveChatTimer <= 0) {
                    proactiveChatTimer = interval;
                    triggerProactiveChat();
                } else {
                    proactiveChatTimer--;
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
     */
    private void startBuilding(RobotAI.QueuedAction action) {
        // Find a flat area: use the block in front of the robot as the build origin
        BlockPos origin = this.getBlockPos();

        // Clear a small area around the origin
        currentBuild = HouseBuilder.generate(action.structure(), action.size(), action.material(), origin);
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

        if (currentBuild.isEmpty()) {
            currentBuild = null;
            return;
        }

        // Set mode to BUILD so the robot doesn't wander
        setMode(RobotMode.BUILD);
        this.getNavigation().stop();

        System.out.println("[AIBot] Started building " + action.structure()
            + " (" + action.size() + ", " + action.material() + ")"
            + " — " + currentBuild.size() + " blocks");
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
            if (currentBuild != null) {
                PlayerEntity owner = getOwner();
                System.out.println("[AIBot] Building complete! Placed " + buildIndex + " blocks.");
                if (owner != null) {
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §a建造完成 ✓ §7(共 "
                        + buildIndex + " 块)"), false);
                }
                clearBuildState(RobotMode.FOLLOW);
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
        } else {
            // ⚠️  Material missing — pause and wait (never consume wrong blocks as fallback — survival dupe bug)
            if (!buildMissingWarningSent && owner != null) {
                String blockName = targetBlock.getName().getString();
                owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §e缺少材料：需要 「"
                    + blockName + "」§7，放材料到我的背包后我会继续"), false);
                buildMissingWarningSent = true;
                buildMissingWaitTicks = 0;
            } else if (owner != null) {
                buildMissingWaitTicks++;
                if (buildMissingWaitTicks > 200) {
                    String blockName = targetBlock.getName().getString();
                    owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §e仍在等待材料：「"
                        + blockName + "」"), false);
                    buildMissingWaitTicks = 0;
                }
            }
            // DO NOT increment buildIndex — retry same block next tick
            buildCooldown = 20;
            return;
        }

        buildCooldown = 1;

        // ── Progress report every ~10 blocks ──
        if (buildIndex - buildLastReportIndex >= 10 && owner != null) {
            int pct = buildIndex * 100 / Math.max(1, totalBlocks);
            owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7🏗️ 建造中… §d"
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
        // NOTE: buildStructure/buildSize/buildMaterial are NOT cleared
        // so the build can be resumed later with "resume" or same params
        setMode(newMode);
    }

    /**
     * Resumes a previous interrupted build if parameters were saved.
     */
    private boolean tryResumeBuild() {
        if (buildStructure == null || buildOrigin == null) return false;
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
        setMode(RobotMode.BUILD);
        PlayerEntity owner = getOwner();
        if (owner != null) {
            owner.sendMessage(Text.literal("§8[§d✦ §5AI-Bot§8] §7恢复建造：第 "
                + (buildIndex + 1) + " / " + currentBuild.size() + " 块"), false);
        }
        return buildIndex < currentBuild.size();
    }

    // ==================== Proactive Chat ====================

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
                                    owner.sendMessage(com.aibot.chat.ChatFormatter.msg(config,
                                        "§7" + a.message), false);
                                    return;
                                }
                            }
                        }
                        if (!msg.equals("{}") && !msg.startsWith("{\"actions")) {
                            owner.sendMessage(com.aibot.chat.ChatFormatter.msg(config,
                                "§7" + msg), false);
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
        // Pick up item into robot's inventory
        ItemStack itemStack = item.getStack();
        if (!itemStack.isEmpty() && this.canPickUpLoot()) {
            ItemStack remainder = this.getInventory().addStack(itemStack);
            if (remainder.isEmpty()) {
                item.discard();
            } else {
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
        return true;  // Immune to ALL damage types including void, /kill, etc.
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;  // Robot is invulnerable — blocks all damage from any source
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
                    // Break the block
                    ServerWorld serverWorld = (ServerWorld) robot.getEntityWorld();
                    serverWorld.breakBlock(target, true, robot, 0);
                    robot.playSound(SoundEvents.BLOCK_STONE_BREAK, 1.0F, 1.0F);
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
            if (target == null) return;

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

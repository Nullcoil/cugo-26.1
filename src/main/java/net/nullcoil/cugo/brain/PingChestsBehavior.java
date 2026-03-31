package net.nullcoil.cugo.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.nullcoil.cugo.config.ConfigHandler;
import net.nullcoil.cugo.util.CugoNBTAccessor;
import net.nullcoil.cugo.util.Debug;
import net.nullcoil.cugo.util.DoubleChestHelper;
import net.nullcoil.cugo.util.StateMachine;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PingChestsBehavior implements CugoBehavior {

    private static final int SEARCH_RADIUS = ConfigHandler.getConfig().searchRadius;
    private static final DustParticleOptions REDSTONE_PARTICLES = new DustParticleOptions(16711680, 1.0F);
    private static final DustParticleOptions HOME_PARTICLES = new DustParticleOptions(16753920, 1.0F);

    private static final StateMachine STATE_MACHINE = new StateMachine();

    @Override
    public void tick(@NotNull CopperGolem golem, @NotNull ServerLevel level) {
        Debug.log("[PingChests] Starting chest scan...");

        CugoNBTAccessor accessor = (CugoNBTAccessor) golem;
        BlockPos home = accessor.cugo$getHome();
        Set<BlockPos> currentSeen = accessor.cugo$getSeenChests();

        Set<BlockPos> previouslySeen = new HashSet<>(currentSeen);
        Set<BlockPos> newSeen = new HashSet<>();

        // Track typed counts for diff report
        Map<StateMachine.Container, Integer> oldCounts = new EnumMap<>(StateMachine.Container.class);
        Map<StateMachine.Container, Integer> newCounts = new EnumMap<>(StateMachine.Container.class);

        // Count previous types by resolving block states
        for (BlockPos pos : previouslySeen) {
            BlockState state = level.getBlockState(pos);
            StateMachine.Container type = STATE_MACHINE.determineContainerType(state);
            oldCounts.merge(type, 1, Integer::sum);
        }

        // Chunk scan
        BlockPos golemPos = golem.blockPosition();
        ChunkPos startChunk = new ChunkPos(
                golemPos.offset(-SEARCH_RADIUS, 0, -SEARCH_RADIUS).getX(),
                golemPos.offset(-SEARCH_RADIUS, 0, -SEARCH_RADIUS).getZ()
        );
        ChunkPos endChunk = new ChunkPos(
                golemPos.offset(SEARCH_RADIUS, 0, SEARCH_RADIUS).getX(),
                golemPos.offset(SEARCH_RADIUS, 0, SEARCH_RADIUS).getZ()
        );

        for (int x = startChunk.x(); x <= endChunk.x(); x++) {
            for (int z = startChunk.z(); z <= endChunk.z(); z++) {
                if (level.hasChunk(x, z)) {
                    scanChunkForContainers(level.getChunk(x, z), level, golemPos, home, accessor, newSeen);
                }
            }
        }

        // Validate home
        if (home != null) {
            BlockState homeState = level.getBlockState(home);
            boolean isValidHome = homeState.getBlock() instanceof ChestBlock && homeState.is(BlockTags.COPPER_CHESTS);
            if (!isValidHome) {
                Debug.log("[PingChests] Home chest at " + home + " is invalid or destroyed. Clearing home.");
                accessor.cugo$setHome(null);
                home = null;
            }
        }

        // Count new types
        for (BlockPos pos : newSeen) {
            BlockState state = level.getBlockState(pos);
            StateMachine.Container type = STATE_MACHINE.determineContainerType(state);
            newCounts.merge(type, 1, Integer::sum);
        }

        // Update memory
        currentSeen.clear();
        currentSeen.addAll(newSeen);

        // Diff report
        if (ConfigHandler.getConfig().debugMode) {
            Set<BlockPos> added = new HashSet<>(newSeen);
            added.removeAll(previouslySeen);

            Set<BlockPos> removed = new HashSet<>(previouslySeen);
            removed.removeAll(newSeen);

            if (added.isEmpty() && removed.isEmpty()) {
                reportToAll(golem, level, "Same old, same old.");
            } else {
                if (!removed.isEmpty())
                    reportToAll(golem, level, "Chests removed: " + removed.size());
                if (!added.isEmpty())
                    reportToAll(golem, level, "Chests added: " + added.size());

                // Wooden chests (CHEST + DOUCHE)
                int oldWooden = oldCounts.getOrDefault(StateMachine.Container.CHEST, 0)
                        + oldCounts.getOrDefault(StateMachine.Container.DOUCHE, 0);
                int newWooden = newCounts.getOrDefault(StateMachine.Container.CHEST, 0)
                        + newCounts.getOrDefault(StateMachine.Container.DOUCHE, 0);
                reportToAll(golem, level, "Wooden chests: " + oldWooden + " -> " + newWooden);

                // Copper chests (COPPER + DOUCOP)
                int oldCopper = oldCounts.getOrDefault(StateMachine.Container.COPPER, 0)
                        + oldCounts.getOrDefault(StateMachine.Container.DOUCOP, 0);
                int newCopper = newCounts.getOrDefault(StateMachine.Container.COPPER, 0)
                        + newCounts.getOrDefault(StateMachine.Container.DOUCOP, 0);
                reportToAll(golem, level, "Copper chests: " + oldCopper + " -> " + newCopper);

                // Barrels (if enabled)
                if (ConfigHandler.getConfig().barrelAsOutput) {
                    int oldBarrels = oldCounts.getOrDefault(StateMachine.Container.BARREL, 0);
                    int newBarrels = newCounts.getOrDefault(StateMachine.Container.BARREL, 0);
                    reportToAll(golem, level, "Barrels: " + oldBarrels + " -> " + newBarrels);
                }

                // Shulkers (if enabled)
                if (ConfigHandler.getConfig().shulkerAsOutput) {
                    int oldShulkers = oldCounts.getOrDefault(StateMachine.Container.SHULKER, 0);
                    int newShulkers = newCounts.getOrDefault(StateMachine.Container.SHULKER, 0);
                    reportToAll(golem, level, "Shulker boxes: " + oldShulkers + " -> " + newShulkers);
                }
            }
        }

        // Particles for new discoveries
        Set<BlockPos> newlyDiscovered = new HashSet<>(newSeen);
        newlyDiscovered.removeAll(previouslySeen);
        if (!newlyDiscovered.isEmpty()) {
            spawnParticles(golem, level, accessor.cugo$getHome(), newlyDiscovered);
        }
    }

    private void reportToAll(CopperGolem golem, ServerLevel level, String message) {
        Debug.log("[PingChests] " + message);
        level.players().stream()
                .filter(p -> p.distanceTo(golem) < 32.0f)
                .forEach(p -> p.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("[CuGO] " + message)
                ));
    }

    private void scanChunkForContainers(LevelChunk chunk, ServerLevel level, BlockPos center, BlockPos currentHome, CugoNBTAccessor accessor, Set<BlockPos> newSeen) {
        // Get all BlockEntities in the chunk
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();

        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            BlockPos pos = entry.getKey();

            if (!pos.closerThan(center, SEARCH_RADIUS)) continue;

            BlockState state = level.getBlockState(pos);

            // --- Logic for Chests ---
            if (state.getBlock() instanceof ChestBlock) {
                BlockPos repPos = DoubleChestHelper.getRepresentativePos(level, pos);

                if (newSeen.contains(repPos)) continue;

                // Logic: Found a Copper Chest and don't have a home?
                if (currentHome == null && state.is(BlockTags.COPPER_CHESTS)) {
                    accessor.cugo$setHome(repPos);
                    currentHome = repPos;
                    Debug.log("[PingChests] ! FOUND HOME ! Assigned Copper Chest at " + repPos);
                    newSeen.add(repPos);
                } else {
                    newSeen.add(repPos);
                }
            } else if ((state.getBlock() instanceof BarrelBlock && ConfigHandler.getConfig().barrelAsOutput) ||
                       (state.getBlock() instanceof ShulkerBoxBlock && ConfigHandler.getConfig().shulkerAsOutput)) {
                newSeen.add(pos);
            }
        }
    }

    private void spawnParticles(CopperGolem golem, ServerLevel level, BlockPos home, Set<BlockPos> targets) {
        Vec3 eyePos = golem.getEyePosition();

        for (BlockPos pos : targets) {
            // Determine if this target is the home chest
            boolean isHome = (home != null && pos.equals(home));

            Vec3 target = Vec3.atCenterOf(pos);

            // Use different color for Home vs Regular
            DustParticleOptions particleColor = isHome ? HOME_PARTICLES : REDSTONE_PARTICLES;

            drawParticleLine(level, eyePos, target, particleColor);
        }
    }

    private void drawParticleLine(ServerLevel level, Vec3 start, Vec3 end, DustParticleOptions particle) {
        Vec3 direction = end.subtract(start);
        double distance = direction.length();

        if (distance > SEARCH_RADIUS) return;

        int particleCount = Mth.clamp((int) (distance * 2), 2, 64);

        for (int i = 0; i <= particleCount; i++) {
            double frac = (double) i / particleCount;
            Vec3 pos = start.add(direction.scale(frac));
            level.sendParticles(particle, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
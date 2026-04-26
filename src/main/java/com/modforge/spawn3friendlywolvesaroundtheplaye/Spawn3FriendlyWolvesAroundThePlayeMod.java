package com.modforge.spawn3friendlywolvesaroundtheplaye;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Spawn3FriendlyWolvesAroundThePlayeMod implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(Spawn3FriendlyWolvesAroundThePlayeMod.class);
	private static final int WOLF_COUNT = 3;
	private static final int COOLDOWN_TICKS = 20 * 10; // 10 seconds
	private static final double SPAWN_RADIUS = 2.5;
	private static final double MIN_SPAWN_DISTANCE = 1.2;

	private final Map<UUID, Integer> cooldownTicks = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		try {
			registerCooldownTicker();
			registerBoneCrouchListener();
			LOGGER.info("Spawn3FriendlyWolvesAroundThePlayeMod initialized");
		} catch (Exception e) {
			LOGGER.error("Failed to initialize mod", e);
		}
	}

	private void registerCooldownTicker() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
	}

	private void onEndServerTick(MinecraftServer server) {
		try {
			if (cooldownTicks.isEmpty()) return;
			Iterator<Map.Entry<UUID, Integer>> it = cooldownTicks.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<UUID, Integer> e = it.next();
				int v = e.getValue();
				v--;
				if (v <= 0) it.remove();
				else e.setValue(v);
			}
		} catch (Exception e) {
			LOGGER.error("Error ticking cooldowns", e);
		}
	}

	private void registerBoneCrouchListener() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			try {
				if (world.isClient()) {
					return TypedActionResult.pass(player.getStackInHand(hand));
				}
				if (!(player instanceof ServerPlayerEntity serverPlayer)) {
					return TypedActionResult.pass(player.getStackInHand(hand));
				}
				ItemStack stack = serverPlayer.getStackInHand(hand);
				if (!stack.isOf(Items.BONE)) {
					return TypedActionResult.pass(stack);
				}
				if (!serverPlayer.isSneaking()) {
					return TypedActionResult.pass(stack);
				}

				UUID id = serverPlayer.getUuid();
				if (cooldownTicks.containsKey(id)) {
					int remaining = cooldownTicks.getOrDefault(id, 0);
					if (remaining > 0) {
						serverPlayer.sendMessage(Text.literal("Wolves are on cooldown (" + (remaining / 20) + "s)"), true);
						return TypedActionResult.fail(stack);
					}
				}

				ServerWorld serverWorld = (ServerWorld) world;
				boolean spawnedAny = spawnFriendlyWolves(serverWorld, serverPlayer, WOLF_COUNT);
				if (spawnedAny) {
					cooldownTicks.put(id, COOLDOWN_TICKS);
					return TypedActionResult.success(stack);
				}
				return TypedActionResult.pass(stack);
			} catch (Exception e) {
				LOGGER.error("Error handling bone crouch use", e);
				return TypedActionResult.pass(player.getStackInHand(hand));
			}
		});
	}

	private boolean spawnFriendlyWolves(ServerWorld world, ServerPlayerEntity owner, int count) {
		try {
			Vec3d base = owner.getPos();
			int spawned = 0;

			for (int i = 0; i < count; i++) {
				Vec3d offset = computeOffset(i);
				Vec3d desired = base.add(offset);
				BlockPos spawnPos = findSpawnPosNear(world, BlockPos.ofFloored(desired), owner.getBlockPos());
				if (spawnPos == null) {
					continue;
				}
				WolfEntity wolf = EntityType.WOLF.create(world);
				if (wolf == null) {
					continue;
				}

				wolf.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, world.random.nextFloat() * 360.0f, 0.0f);
				wolf.initialize(world, world.getLocalDifficulty(spawnPos), SpawnReason.TRIGGERED, null);
				wolf.setTamed(true);
				wolf.setOwner(owner);
				wolf.setSitting(false);
				wolf.setHealth(wolf.getMaxHealth());

				if (world.spawnEntity(wolf)) {
					spawned++;
				}
			}

			if (spawned > 0) {
				owner.sendMessage(Text.literal("Spawned " + spawned + " friendly wolf" + (spawned == 1 ? "" : "es") + "."), true);
				return true;
			}
			owner.sendMessage(Text.literal("No safe space to spawn wolves."), true);
			return false;
		} catch (Exception e) {
			LOGGER.error("Failed spawning friendly wolves", e);
			return false;
		}
	}

	private Vec3d computeOffset(int index) {
		// Three positions roughly around the player
		switch (index % 3) {
			case 0:
				return new Vec3d(SPAWN_RADIUS, 0.0, 0.0);
			case 1:
				return new Vec3d(-SPAWN_RADIUS * 0.5, 0.0, SPAWN_RADIUS * 0.866); // ~60deg
			default:
				return new Vec3d(-SPAWN_RADIUS * 0.5, 0.0, -SPAWN_RADIUS * 0.866);
		}
	}

	private BlockPos findSpawnPosNear(ServerWorld world, BlockPos desired, BlockPos playerPos) {
		try {
			// Search a small cube for a safe spot (feet + head clear, solid ground)
			final int horizontal = 2;
			final int vertical = 2;
			BlockPos.Mutable m = new BlockPos.Mutable();

			for (int dy = -vertical; dy <= vertical; dy++) {
				for (int dx = -horizontal; dx <= horizontal; dx++) {
					for (int dz = -horizontal; dz <= horizontal; dz++) {
						m.set(desired.getX() + dx, desired.getY() + dy, desired.getZ() + dz);
						if (m.getSquaredDistance(playerPos) < (MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE)) {
							continue;
						}
						if (isSafeSpawn(world, m)) {
							return m.toImmutable();
						}
					}
				}
			}
			return null;
		} catch (Exception e) {
			LOGGER.error("Error finding spawn position", e);
			return null;
		}
	}

	private boolean isSafeSpawn(ServerWorld world, BlockPos pos) {
		try {
			// Use collision shapes to avoid deprecated "isAir" checks where possible.
			if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) return false;
			BlockPos head = pos.up();
			if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) return false;
			BlockPos below = pos.down();
			return world.getBlockState(below).isSolidBlock(world, below);
		} catch (Exception e) {
			LOGGER.error("Error checking safe spawn", e);
			return false;
		}
	}
}

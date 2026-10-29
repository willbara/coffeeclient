package dev.coffeeclient.util.fakeplayer;

import com.mojang.authlib.GameProfile;
import dev.coffeeclient.mixin.AbstractClientPlayerAccessor;
import dev.coffeeclient.mixin.ClientPacketListenerAccessor;
import dev.coffeeclient.util.RandomWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A client-side player entity used by the FakePlayer module for testing. It can wander around its spawn
 * point, and can pass as a real player: then it has a tab-list entry and Coffee's modules stop
 * skipping it.
 */
public class FakePlayerEntity extends RemotePlayer {
	/** Client-side ids are negative so they never clash with server-assigned (positive) ids. */
	private static final AtomicInteger NEXT_ID = new AtomicInteger(-1_000_000);
	/** Entity shared-flag index for fall flying (elytra). */
	private static final int FALL_FLYING_FLAG = 7;
	private final @Nullable PlayerInfo skinInfo;
	private @Nullable RandomWalk walk;
	private double walkRadius;
	private double fallSpeed;
	private boolean real;
	private boolean lookAtPlayer;
	private float walkSpeed;
	private float walkPhase;
	private @Nullable PlayerInfo tabEntry;

	public FakePlayerEntity(Player player, String name, @Nullable PlayerInfo skinInfo, boolean copyInventory) {
		super(Minecraft.getInstance().level, new GameProfile(UUID.randomUUID(), name));
		this.skinInfo = skinInfo;
		// ClientLevel.addEntity calls getId(), which throws until an id is assigned.
		this.setId(NEXT_ID.getAndDecrement());
		// RemotePlayer turns physics off (the server moves it), so move() would skip collision and never update onGround.
		this.noPhysics = false;
		// Client-side players never receive the server's model-part flags, so show every outer
		// layer (hat, jacket, sleeves, pants, cape) by hand or the second skin layer won't render.
		int modelParts = 0;
		for (PlayerModelPart part : PlayerModelPart.values()) modelParts |= part.getMask();
		this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) modelParts);
		copyPosition(player);
		this.yRotO = this.getYRot();
		this.xRotO = this.getXRot();
		this.yHeadRot = player.yHeadRot;
		this.yHeadRotO = this.yHeadRot;
		this.yBodyRot = player.yBodyRot;
		this.yBodyRotO = this.yBodyRot;
		this.getAttributes().assignAllValues(player.getAttributes());
		this.setPose(player.getPose());
		// Sneaking is a shared flag too, and the bounding box only follows the pose after a refresh.
		this.setShiftKeyDown(player.isShiftKeyDown());
		this.refreshDimensions();
		// Airborne players keep their jump: same ground state and vertical speed, so walking bots continue the arc.
		this.setOnGround(player.onGround());
		this.setDeltaMovement(player.getDeltaMovement());
		this.fallSpeed = player.getDeltaMovement().y;
		if (copyInventory) this.getInventory().replaceWith(player.getInventory());
		// Mid-action state: swing, item use and the walk cycle carry over, so a frozen bot holds that frame.
		this.swinging = player.swinging;
		this.swingingArm = player.swingingArm;
		this.swingTime = player.swingTime;
		// Swing lerps from the previous value to the current one, so equal values hold the frame.
		this.attackAnim = player.attackAnim;
		this.oAttackAnim = this.attackAnim;
		if (player.isUsingItem()) {
			this.startUsingItem(player.getUsedItemHand());
			this.useItemRemaining = player.getUseItemRemainingTicks();
		}
		this.walkSpeed = player.walkAnimation.speed(1f);
		this.walkPhase = player.walkAnimation.position(1f);
		// Wings stay at their zero defaults until the elytra state ticks, and a frozen bot never ticks.
		// Set the flight flag if the player is flying, then run the state to its steady angle (each tick closes 70% of the gap).
		if (player.isFallFlying()) this.setSharedFlag(FALL_FLYING_FLAG, true);
		for (int i = 0; i < 40; i++) this.elytraAnimationState.tick();
		// Later setters above can move the bot; make the previous pose match, or the render lerps from a stale spot.
		this.setOldPosAndRot();
		syncWalkCycle();
	}

	/** Replace this bot's armor, hands and offhand with a random enchanted high-end kit. */
	public void giveRandomGear() {
		RandomGear.equip(this);
	}

	public void spawn() {
		this.unsetRemoved();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) mc.level.addEntity(this);
	}

	/** False for a bot set to pass as a real player. Use instead of {@code instanceof} when skipping fakes. */
	public static boolean isFake(Entity entity) {
		return entity instanceof FakePlayerEntity fake && !fake.real;
	}

	/** Wander within {@code radius} of where the bot stands now, or stop (radius 0). */
	public void setWalking(double radius) {
		walkRadius = radius;
		if (radius <= 0) {
			if (walk != null) {
				// Hold the stride the bot is in right now.
				walkSpeed = walkAnimation.speed(1f);
				walkPhase = walkAnimation.position(1f);
			}
			walk = null;
		} else if (walk == null) {
			walk = new RandomWalk();
			walk.reset(position());
		}
		syncWalkCycle();
	}

	/** Frozen bots hold their captured stride; walking bots animate normally. */
	private void syncWalkCycle() {
		FrozenWalkCycle cycle = (FrozenWalkCycle) walkAnimation;
		if (walk == null) cycle.freeze(walkSpeed, walkPhase);
		else cycle.thaw();
	}

	public boolean isFrozen() {
		return walk == null;
	}

	/** Pass as a real player: a tab-list entry with this bot's name and skin, and no special-casing by Coffee. */
	public void setReal(boolean real) {
		this.real = real;
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.getConnection() instanceof ClientPacketListenerAccessor connection)) return;
		if (real && tabEntry == null) {
			PlayerInfo skin = skinInfo != null ? skinInfo : mc.getConnection().getPlayerInfo(mc.player.getUUID());
			var profile = skin == null ? getGameProfile()
				: new GameProfile(getUUID(), getGameProfile().name(), skin.getProfile().properties());
			tabEntry = new PlayerInfo(profile, false);
			connection.coffeeclient$playerInfoMap().put(getUUID(), tabEntry);
			connection.coffeeclient$listedPlayers().add(tabEntry);
		} else if (!real && tabEntry != null) {
			connection.coffeeclient$playerInfoMap().remove(getUUID());
			connection.coffeeclient$listedPlayers().remove(tabEntry);
			tabEntry = null;
		}
	}

	/** Walks toward the current wander target with simple gravity, jumping one-block steps. */
	@Override
	public void aiStep() {
		super.aiStep();
		if (walk == null) return;
		Vec3 pos = position();
		Vec3 target = walk.target(pos, walkRadius, 20, 80);
		double vx = 0, vz = 0;
		if (target != null) {
			Vec3 dir = new Vec3(target.x - pos.x, 0, target.z - pos.z).normalize();
			if (onGround() && RandomWalk.ledgeAhead(level(), pos, dir, 2)) {
				walk.blocked();
			} else {
				vx = dir.x * 0.2;
				vz = dir.z * 0.2;
				float yaw = (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90f;
				setYRot(yaw);
				yHeadRot = yaw;
				yBodyRot = yaw;
			}
		}
		// A grounded bot still presses down: move() only refreshes onGround on vertical movement for a remote
		// entity, so with no downward push it would stay "grounded" off a ledge and float.
		fallSpeed = onGround() ? (target != null && horizontalCollision ? 0.42 : -0.08) : (fallSpeed - 0.08) * 0.98;
		move(MoverType.SELF, new Vec3(vx, fallSpeed, vz));
	}

	/** Walk off freezes the bot on its captured frame; the head still turns toward the local player. */
	@Override
	public void tick() {
		if (walk == null) {
			updateHeadLook();
			return;
		}
		super.tick();
		updateHeadLook();
	}

	/** Turns only the head toward the local player. The body keeps its own heading. */
	public void setLookAtPlayer(boolean look) {
		lookAtPlayer = look;
	}

	private void updateHeadLook() {
		Player target = Minecraft.getInstance().player;
		if (!lookAtPlayer || target == null) return;
		Vec3 to = target.getEyePosition().subtract(getEyePosition());
		double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
		float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(to.y, horizontal) * Mth.RAD_TO_DEG);
		// Set the previous angles too, and turn the short way, or the head lerps in visible steps.
		yHeadRotO = yHeadRot;
		yHeadRot += Mth.wrapDegrees(yaw - yHeadRot);
		xRotO = getXRot();
		setXRot(pitch);
	}

	public void despawn() {
		setReal(false);
		if (this.isRemoved()) return;
		if (level() instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
			clientLevel.removeEntity(this.getId(), Entity.RemovalReason.DISCARDED);
		}
		this.setRemoved(Entity.RemovalReason.DISCARDED);
	}

	@Override
	protected @Nullable PlayerInfo getPlayerInfo() {
		if (skinInfo != null) return skinInfo;
		PlayerInfo info = super.getPlayerInfo();
		Minecraft mc = Minecraft.getInstance();
		if (info == null && mc.player != null && mc.getConnection() != null) {
			info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
			if (info != null) ((AbstractClientPlayerAccessor) this).coffeeclient$setPlayerInfo(info);
		}
		return info;
	}
}

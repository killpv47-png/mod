package com.killpv47.stealthaddon.modules;

import com.killpv47.stealthaddon.StealthAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * ElytraHeadSlammer - AnarchyMC combat module.
 *
 * Behaviour:
 *   1. Finds the nearest player within `range` blocks.
 *   2. Puts on elytra (if in inventory / chestplate slot) and starts flying.
 *   3. Ascends above the target's head using elytra fly boosts (firework or motion).
 *   4. When directly above and close enough, does a series of head attacks
 *      (attack packet + swing) rapidly on the target's head.
 *   5. Repeats for the next target.
 *
 * Designed for anarchy servers where crit/head strikes via elytra dive are
 * a legitimate PvP tactic. Uses motion + packet spam only, does not try to
 * be un-detectable.
 */
public class ElytraHeadSlammer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAttack  = settings.createGroup("Attack");
    private final SettingGroup sgFlight  = settings.createGroup("Flight");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to search for a target player.")
        .defaultValue(48.0)
        .min(4.0).max(128.0)
        .sliderRange(4.0, 128.0)
        .build()
    );

    private final Setting<Double> attackRange = sgAttack.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("Distance from the target's head at which we start slamming.")
        .defaultValue(4.0)
        .min(1.0).max(6.0)
        .sliderRange(1.0, 6.0)
        .build()
    );

    private final Setting<Integer> hitsPerBurst = sgAttack.add(new IntSetting.Builder()
        .name("hits-per-burst")
        .description("How many attack packets to send in a row when in range.")
        .defaultValue(8)
        .min(1).max(32)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> hitDelay = sgAttack.add(new IntSetting.Builder()
        .name("hit-delay-ticks")
        .description("Ticks between individual attack packets in a burst (0 = every tick).")
        .defaultValue(0)
        .min(0).max(10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Double> aboveHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("above-height")
        .description("How high above the target's head we try to be before diving.")
        .defaultValue(4.0)
        .min(1.0).max(15.0)
        .sliderRange(1.0, 15.0)
        .build()
    );

    private final Setting<Double> boostStrength = sgFlight.add(new DoubleSetting.Builder()
        .name("boost-strength")
        .description("Downward velocity multiplier when slamming.")
        .defaultValue(1.6)
        .min(0.5).max(4.0)
        .sliderRange(0.5, 4.0)
        .build()
    );

    private final Setting<Boolean> autoElytra = sgFlight.add(new BoolSetting.Builder()
        .name("auto-elytra")
        .description("Automatically start elytra flight if not already flying.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgAttack.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards the target's head silently (server-only).")
        .defaultValue(true)
        .build()
    );

    // ---- runtime state --------------------------------------------------
    private PlayerEntity target;
    private int burstCooldown = 0;
    private enum Phase { SEARCH, ASCEND, SLAM }
    private Phase phase = Phase.SEARCH;

    public ElytraHeadSlammer() {
        super(StealthAddon.CATEGORY, "elytra-head-slammer",
            "Elytra fly above nearest player and slam their head with rapid attacks.");
    }

    @Override
    public void onActivate() {
        target = null;
        phase = Phase.SEARCH;
        burstCooldown = 0;
    }

    @Override
    public void onDeactivate() {
        target = null;
        phase = Phase.SEARCH;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        ClientPlayerEntity me = mc.player;

        // Refresh target
        if (target == null || !target.isAlive() || me.distanceTo(target) > range.get()) {
            target = findNearestPlayer(me);
            if (target == null) {
                phase = Phase.SEARCH;
                return;
            }
            phase = Phase.ASCEND;
        }

        // Ensure we have elytra flight if requested
        if (autoElytra.get()) tryStartElytra(me);

        Vec3d head = target.getPos().add(0, target.getStandingEyeHeight(), 0);
        Vec3d desiredAbove = head.add(0, aboveHeight.get(), 0);

        // Rotate towards head (server-side, doesn't move the camera)
        if (rotate.get()) sendLookPacket(me, head);

        switch (phase) {
            case ASCEND: {
                // If we're not yet above the target, boost up + towards.
                double dyToAbove = desiredAbove.y - me.getY();
                Vec3d horiz = new Vec3d(desiredAbove.x - me.getX(), 0, desiredAbove.z - me.getZ());
                double horizDist = horiz.length();

                if (me.isGliding()) {
                    Vec3d v = me.getVelocity();
                    // Push upwards while we're too low
                    double vy = v.y;
                    if (dyToAbove > 1.0) vy = Math.max(vy, 0.6);
                    // Steer horizontally
                    double hx = v.x, hz = v.z;
                    if (horizDist > 0.5) {
                        Vec3d dir = horiz.normalize().multiply(0.7);
                        hx = dir.x; hz = dir.z;
                    }
                    me.setVelocity(hx, vy, hz);
                }

                if (dyToAbove <= 1.5 && horizDist <= 2.5) {
                    phase = Phase.SLAM;
                }
                break;
            }
            case SLAM: {
                // Nose-dive: aim velocity straight down at target head.
                if (me.isGliding()) {
                    Vec3d toHead = head.subtract(me.getPos());
                    Vec3d dir = toHead.length() < 0.001
                        ? new Vec3d(0, -1, 0)
                        : toHead.normalize();
                    double s = boostStrength.get();
                    me.setVelocity(dir.x * s, Math.min(-0.4, dir.y * s), dir.z * s);
                }

                // If close to head, fire a burst.
                double d = me.getPos().distanceTo(head);
                if (d <= attackRange.get()) {
                    fireBurst();
                } else if (me.getY() < head.y - 2) {
                    // We've overshot the target — climb again.
                    phase = Phase.ASCEND;
                }
                break;
            }
            case SEARCH:
            default:
                break;
        }
    }

    private void fireBurst() {
        if (target == null) return;
        if (burstCooldown > 0) { burstCooldown--; return; }

        int hits = hitsPerBurst.get();
        for (int i = 0; i < hits; i++) {
            mc.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking())
            );
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
        burstCooldown = hitDelay.get();
    }

    private PlayerEntity findNearestPlayer(ClientPlayerEntity me) {
        List<? extends PlayerEntity> all = mc.world.getPlayers();
        PlayerEntity best = null;
        double bestD = range.get() * range.get();
        for (PlayerEntity p : all) {
            if (p == me) continue;
            if (!p.isAlive()) continue;
            if (p.isSpectator()) continue;
            double d = p.squaredDistanceTo(me);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private void tryStartElytra(ClientPlayerEntity me) {
        if (me.isGliding()) return;

        // Ensure elytra is in the chestplate slot; if not, try to equip.
        var chest = me.getEquippedStack(EquipmentSlot.CHEST);
        if (chest == null || chest.getItem() != Items.ELYTRA) {
            FindItemResult r = InvUtils.find(Items.ELYTRA);
            if (r.found() && r.slot() >= 0) {
                InvUtils.move().from(r.slot()).toArmor(2);
            }
        }

        // Send "start fall flying" — only valid mid-air.
        // We try it every tick until the server accepts it.
        try {
            mc.getNetworkHandler().sendPacket(
                new ClientCommandC2SPacket(me, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
            );
        } catch (Throwable ignored) {}
    }

    private void sendLookPacket(ClientPlayerEntity me, Vec3d target) {
        double dx = target.x - me.getX();
        double dy = target.y - (me.getY() + me.getStandingEyeHeight());
        double dz = target.z - me.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horiz) * (180.0 / Math.PI));

        try {
            mc.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
                    yaw, pitch, me.isOnGround(), me.horizontalCollision
                )
            );
        } catch (Throwable ignored) {}
    }
}

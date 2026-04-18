package net.nullcoil.cugo.brain.behaviors.pathfinding.movecontrol;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.util.Dev;

public class TightMoveControl extends MoveControl {

    // Stop completely within this XZ distance squared (~0.1 blocks)
    private static final double ARRIVAL_THRESHOLD_SQ = 0.01;

    // Begin slowing down within this XZ distance squared (~0.8 blocks)
    private static final double SLOWDOWN_RADIUS_SQ = 0.64;

    // Minimum speed multiplier so the golem doesn't stall out before arriving
    private static final float MIN_SPEED_FACTOR = 0.2f;

    public TightMoveControl(CopperGolem golem) {
        super(golem);
    }

    public void forceStop() {
        this.operation = Operation.WAIT;
        this.wantedX = this.mob.getX();
        this.wantedY = this.mob.getY();
        this.wantedZ = this.mob.getZ();
        this.mob.setZza(0.0F);
        this.mob.setSpeed(0.0F);
    }

    @Override
    public void tick() {
        if (this.operation == Operation.STRAFE) {
            super.tick();
            return;
        }

        if (this.operation == Operation.WAIT) {
            this.mob.setZza(0.0F);
            this.mob.setSpeed(0.0F);
            return;
        }

        if (this.operation == Operation.JUMPING) {
            float speed = (float) this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            this.mob.setSpeed((float) (speed * this.speedModifier));
            if (this.mob.onGround()) {
                this.operation = Operation.MOVE_TO;
            }
            return;
        }

        // ── MOVE_TO ──────────────────────────────────────────────────────────

        double dx = this.wantedX - this.mob.getX();
        double dz = this.wantedZ - this.mob.getZ();
        double dy = this.wantedY - this.mob.getY();
        double xzDistSq = dx * dx + dz * dz;

        // Arrived
        if (xzDistSq < ARRIVAL_THRESHOLD_SQ) {
            forceStop();
            return;
        }

        // Rotate toward target
        float targetYaw = (float)(Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.mob.setYRot(this.rotlerp(this.mob.getYRot(), targetYaw, 30.0F));
        this.mob.setYBodyRot(this.mob.getYRot());

        // Scale speed down as we approach — linear falloff within SLOWDOWN_RADIUS_SQ
        float baseSpeed = (float) this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        float speedFactor;
        if (xzDistSq < SLOWDOWN_RADIUS_SQ) {
            // t goes from 1.0 (at threshold edge) to 0.0 (at arrival)
            float t = (float)(xzDistSq / SLOWDOWN_RADIUS_SQ);
            speedFactor = Mth.clamp(t, MIN_SPEED_FACTOR, 1.0f);
        } else {
            speedFactor = 1.0f;
        }

        float finalSpeed = baseSpeed * (float) this.speedModifier * speedFactor;

        // Only set speed — do NOT also call setZza, let the locomotion
        // system derive forward movement from the yaw delta naturally
        this.mob.setSpeed(finalSpeed);

        // Jump if blocked by a step
        if (dy > this.mob.maxUpStep() && xzDistSq < Math.max(1.0F, this.mob.getBbWidth())) {
            this.mob.getJumpControl().jump();
            this.operation = Operation.JUMPING;
        }
    }
}
package net.nullcoil.cugo.brain.behaviors.pathfinding.movecontrol;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.nullcoil.cugo.util.Dev;

public class TightMoveControl extends MoveControl {

    // Vanilla uses 2.5000003E-7F (~0.0005 blocks).
    // We use 0.0025 (~0.05 blocks) — tight enough to land precisely on a block
    // center without overshooting.
    private static final double ARRIVAL_THRESHOLD_SQ = 0.04;

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
        Dev.log("[TMC] Stopped at: "
                + this.mob.getX() + ", "
                + this.mob.getY() + ", "
                + this.mob.getZ()
                + " | wanted: " + this.wantedX + ", " + this.wantedY + ", " + this.wantedZ);
    }

    @Override
    public void tick() {
        // Delegate non-movement operations to vanilla.
        if (this.operation == Operation.STRAFE) {
            super.tick();
            return;
        }

        if (this.operation == Operation.JUMPING) {
            // Keep speed applied while airborne, then fall back to WAIT on landing.
            float speed = (float) this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            this.mob.setSpeed(speed);
            if (this.mob.onGround()) {
                this.operation = Operation.MOVE_TO; // resume approach after landing
            }
            return;
        }

        if (this.operation == Operation.WAIT) {
            this.mob.setZza(0.0F);
            this.mob.setSpeed(0.0F);
            return;
        }

        // ── MOVE_TO ──────────────────────────────────────────────────────────
        // Unlike vanilla, we do NOT reset to WAIT here — we stay in MOVE_TO
        // until we actually arrive. This means setWantedPosition only needs to
        // be called once and we drive ourselves to the target.

        double dx = this.wantedX - this.mob.getX();
        double dy = this.wantedY - this.mob.getY();
        double dz = this.wantedZ - this.mob.getZ();
        double xzDistSq = dx * dx + dz * dz;

        // Arrived — stop.
        if (xzDistSq < ARRIVAL_THRESHOLD_SQ) {
            forceStop();
            return;
        }

        // Rotate toward target.
        float targetYaw = (float)(Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.mob.setYRot(this.rotlerp(this.mob.getYRot(), targetYaw, 90.0F));
        this.mob.setYBodyRot(this.mob.getYRot());

        // Flat movement speed — no multipliers.
        float speed = (float) this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        this.mob.setSpeed(speed);
        this.mob.setZza(speed);

        // Jump if there's a step up ahead, same as vanilla.
        if (dy > this.mob.maxUpStep() && xzDistSq < Math.max(1.0F, this.mob.getBbWidth())) {
            this.mob.getJumpControl().jump();
            this.operation = Operation.JUMPING;
        }
    }
}
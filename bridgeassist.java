// Eagle.java - Bridge Assist (Slinky-style 1:1 Logic)
// 4-corner AABB edge detection with velocity+input prediction
// Timer-based unsneak with smooth noise randomization

// === State ===
double unsneakTimer = 0.0;
boolean eagleActive = false;
boolean moduleSneaking = false;
int groundTicks = 0;
double globalTimer = 0;

// Smooth noise states: {seedTime, oldTarget, newTarget}
double noiseEdgeSeed = 0; float noiseEdgeOld = 0.5f; float noiseEdgeNew = 0.5f;
double noiseDelaySeed = 0; float noiseDelayOld = 0.5f; float noiseDelayNew = 0.5f;
double noiseExtraSeed = 0; float noiseExtraOld = 0.5f; float noiseExtraNew = 0.5f;

void onLoad() {
    modules.registerSlider("Edge Offset", "", 0.3, 0.0, 0.5, 0.01);
    modules.registerSlider("Unsneak Delay", " ticks", 1.0, 0.0, 10.0, 0.5);
    modules.registerButton("Randomize", true);
    modules.registerButton("Sneak On Jump", true);
    modules.registerButton("Require Sneak", true);
    modules.registerButton("Require Holding Blocks", true);
    modules.registerButton("Require Looking Down", false);
}

void onEnable() {
    unsneakTimer = 0.0;
    eagleActive = false;
    moduleSneaking = false;
    groundTicks = 0;
    globalTimer = 0;
}

void onDisable() {
    if (eagleActive) {
        int code = keybinds.getKeycode("sneak");
        keybinds.setPressed("sneak", keybinds.isKeyDown(code));
    }
    eagleActive = false;
    moduleSneaking = false;
}

// =========================================================================
// Smooth Noise — Slinky SmoothRandomDelay
// Perlin-like interpolation between two random targets over a period.
// Ensures |old - new| >= 0.2 for visible variation.
// =========================================================================
float smoothNoise(float min, float max, double period,
                  int channel) {
    // channel: 0=edge, 1=delay, 2=extra
    double seed; float old; float nw;
    if (channel == 0) { seed = noiseEdgeSeed; old = noiseEdgeOld; nw = noiseEdgeNew; }
    else if (channel == 1) { seed = noiseDelaySeed; old = noiseDelayOld; nw = noiseDelayNew; }
    else { seed = noiseExtraSeed; old = noiseExtraOld; nw = noiseExtraNew; }

    if (globalTimer > seed + period) {
        seed = globalTimer;
        old = nw;
        int safety = 0;
        do { nw = (float) Math.random(); safety++; }
        while (Math.abs(old - nw) < 0.2f && safety < 20);
        // Write back
        if (channel == 0) { noiseEdgeSeed = seed; noiseEdgeOld = old; noiseEdgeNew = nw; }
        else if (channel == 1) { noiseDelaySeed = seed; noiseDelayOld = old; noiseDelayNew = nw; }
        else { noiseExtraSeed = seed; noiseExtraOld = old; noiseExtraNew = nw; }
    }
    float t = (float)((globalTimer - seed) / period);
    if (t > 1.0f) t = 1.0f;
    float value = old + t * (nw - old);
    return value * (max - min) + min;
}

boolean smoothNoiseChance(double period, int channel) {
    float value = smoothNoise(0.0f, 1.0f, period, channel);
    return Math.random() < (double) value;
}

// =========================================================================
// Block solidity check
// =========================================================================
boolean isSolid(int x, int y, int z) {
    Block b = world.getBlockAt(x, y, z);
    return b != null && !b.name.equals("air");
}

// =========================================================================
// 4-Corner Edge Detection — Slinky WillFallOff (1:1)
// Predicts next-tick position using velocity + WASD input,
// then checks 4 corners of the player's hitbox for solid ground.
// =========================================================================
boolean willFallOff(Entity player, boolean predictUnsneak) {
    Vec3 pos = player.getPosition();
    double feetX = pos.x;
    double feetY = pos.y - 0.01; // just below feet (Slinky: AABB.minY - 0.01)
    double feetZ = pos.z;

    // Current velocity
    Vec3 motion = client.getMotion();
    double velX = motion.x;
    double velZ = motion.z;

    // WASD input from raw key state (Slinky: IsKeyPressed on keybindings)
    // Do NOT use client.getForward()/getStrafe() — those are sneak-modified (×0.3)
    // which double-scales with sneak speed, making prediction 3.3x too short.
    float forward = 0.0f;
    if (keybinds.isKeyDown(keybinds.getKeycode("forward"))) forward += 1.0f;
    if (keybinds.isKeyDown(keybinds.getKeycode("back")))    forward -= 1.0f;
    float strafe = 0.0f;
    if (keybinds.isKeyDown(keybinds.getKeycode("left")))  strafe += 1.0f;
    if (keybinds.isKeyDown(keybinds.getKeycode("right"))) strafe -= 1.0f;
    forward *= 0.98f;
    strafe  *= 0.98f;
    float inputMagSq = forward * forward + strafe * strafe;

    if (inputMagSq >= 0.0001f) {
        float inputMag = (float) Math.sqrt(inputMagSq);
        // Approximate landMovementFactor (Slinky: field 0x468 = getAIMoveSpeed)
        float speed;
        double hSpeed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (hSpeed > 0.12 || predictUnsneak) {
            speed = 0.13f; // assume sprinting for safety if checking unsneak
        } else if (keybinds.isPressed("sneak") || moduleSneaking) {
            speed = 0.03f; // sneaking
        } else {
            speed = 0.1f;  // walking
        }
        speed *= 0.9999998f; // Slinky epsilon (dump line 25905)
        if (inputMag >= 1.0f) speed /= inputMag;

        double yawRad = Math.toRadians(player.getYaw());
        float sinYaw = (float) Math.sin(yawRad);
        float cosYaw = (float) Math.cos(yawRad);

        // MC standard: motionX += strafe*cos - forward*sin
        velX += (double)(strafe * speed * cosYaw - forward * speed * sinYaw);
        velZ += (double)(forward * speed * cosYaw + strafe * speed * sinYaw);
    }

    double predictedX = feetX + velX;
    double predictedZ = feetZ + velZ;

    // Edge offset with noise (Slinky: subtract noise from offset, clamp >= 0)
    double edgeOffset = modules.getSlider(scriptName, "Edge Offset");
    if (modules.getButton(scriptName, "Randomize")) {
        float noise = smoothNoise(0.0f, 0.15f, 5.0, 0); // period 5 ticks
        edgeOffset -= (double) noise;
        if (edgeOffset < 0.0) edgeOffset = 0.0;
    }

    // 4-corner check (Slinky logic: any corner solid → SAFE)
    int bx1 = (int) Math.floor(predictedX - edgeOffset);
    int bz1 = (int) Math.floor(predictedZ - edgeOffset);
    int by  = (int) Math.floor(feetY);

    // Corner 1
    if (isSolid(bx1, by, bz1)) return false; // SAFE

    // Corner 2 (opposite Z)
    int bz2 = (int) Math.floor(predictedZ + edgeOffset);
    if (bz1 != bz2 && isSolid(bx1, by, bz2)) return false; // SAFE

    // Corner 3 (opposite X)
    int bx2 = (int) Math.floor(predictedX + edgeOffset);
    if (bx1 != bx2) {
        if (isSolid(bx2, by, bz1)) return false; // SAFE
        // Corner 4 (diagonal)
        if (bz1 != bz2 && isSolid(bx2, by, bz2)) return false; // SAFE
    }

    return true; // NO solid block → WILL FALL
}

// =========================================================================
// Deactivate — restore sneak key to physical state
// =========================================================================
void deactivate(boolean sneakPhysical) {
    if (eagleActive) {
        keybinds.setPressed("sneak", sneakPhysical);
        moduleSneaking = false;
    }
    eagleActive = false;
}

// =========================================================================
// Main Update — Slinky BridgeAssist_OnUpdate (1:1)
// =========================================================================
void onPreUpdate() {
    globalTimer += 1.0;

    Entity player = client.getPlayer();
    if (player == null) return;

    // Track grounded state (Slinky: max 20 ticks, decrement when airborne)
    if (player.onGround()) {
        groundTicks = 20;
    } else {
        if (groundTicks > 0) groundTicks--;
    }

    int sneakCode = keybinds.getKeycode("sneak");
    boolean sneakPhysical = keybinds.isKeyDown(sneakCode);

    // ===== CONDITIONAL GATES (Slinky 1:1) =====

    // Gate 1: Require Sneak — sneak key must be physically held
    if (modules.getButton(scriptName, "Require Sneak") && !sneakPhysical) {
        deactivate(sneakPhysical);
        return;
    }

    // Gate 2: Require Looking Down — pitch >= 65°
    if (modules.getButton(scriptName, "Require Looking Down") && player.getPitch() < 65.0f) {
        deactivate(sneakPhysical);
        return;
    }

    // Gate 3: Require Holding Blocks
    if (modules.getButton(scriptName, "Require Holding Blocks")) {
        if (player.getHeldItem() == null || !player.getHeldItem().isBlock) {
            deactivate(sneakPhysical);
            return;
        }
    }

    // ===== ALL GATES PASSED — MODULE ACTIVE =====
    eagleActive = true;

    boolean isSneaking = keybinds.isPressed("sneak"); // real game state (Slinky: IsKeyPressed)
    Vec3 motion = client.getMotion();

    // =========================================================
    // BRANCH A: NOT currently sneaking → decide if we should START
    // =========================================================
    if (!isSneaking) {
        boolean shouldCheck = false;

        // A1: On ground → always check
        if (player.onGround()) {
            shouldCheck = true;
        }
        // A2: Sneak On Jump + going up (velY >= 0) → check
        else if (modules.getButton(scriptName, "Sneak On Jump") && motion.y >= 0.0) {
            shouldCheck = true;
        }

        if (shouldCheck && willFallOff(player, false)) {
            keybinds.setPressed("sneak", true);
            moduleSneaking = true;
            
            // Set initial timer so we don't instantly unsneak at corners
            float delay = (float) modules.getSlider(scriptName, "Unsneak Delay");
            if (modules.getButton(scriptName, "Randomize")) {
                float r = smoothNoise(0.0f, 1.0f, 30.0, 1);
                delay += r;
                if (smoothNoiseChance(60.0, 2)) {
                    delay += (float) Math.random();
                }
            }
            unsneakTimer = (double) delay;
        }
        return;
    }

    // =========================================================
    // BRANCH B: Currently sneaking → decide if we should STOP
    // =========================================================
    boolean requireSneak = modules.getButton(scriptName, "Require Sneak");

    if (!requireSneak) {
        // Sub-branch B1: require_sneak OFF
        if (!player.onGround()) {
            if (!modules.getButton(scriptName, "Sneak On Jump")) {
                return; // Airborne + sneak_on_jump OFF → keep sneaking
            }
            if (motion.y < 0.0) {
                return; // Falling → keep sneaking
            }
            // Going up → proceed to edge check
        }
        // On ground OR going up → proceed to edge check
    } else {
        // Sub-branch B2: require_sneak ON
        // Only proceed to edge check when falling and was recently grounded.
        // On ground: motionY ≈ -0.0784 (gravity), passes the check → proceeds.
        // Jumping up: motionY > 0 → keep sneaking.
        if (motion.y >= 0.0) {
            return; // Going up → keep sneaking
        }
        if (groundTicks < 1) {
            return; // Not recently grounded → keep sneaking
        }
        // Falling or on ground (motionY < 0) AND was recently grounded → edge check
    }

    // === STANDING STILL GUARD ===
    // If the player has no WASD input, do NOT unsneak.
    // The player is only safe because they're sneaking;
    // releasing sneak would let them walk off the edge.
    boolean anyMovement =
        keybinds.isKeyDown(keybinds.getKeycode("forward")) ||
        keybinds.isKeyDown(keybinds.getKeycode("back")) ||
        keybinds.isKeyDown(keybinds.getKeycode("left")) ||
        keybinds.isKeyDown(keybinds.getKeycode("right"));
    if (!anyMovement) {
        // User wants to stay sneaking when standing still at the edge.
        // Do nothing, just return. Timer remains frozen.
        return;
    }

    // === EDGE CHECK for unsneak ===
    // We pass predictUnsneak=true so it simulates walk/sprint speed prediction.
    if (!willFallOff(player, true)) {
        // Safe — decrement unsneak timer
        unsneakTimer -= 1.0;
        if (unsneakTimer <= 0.0) {
            // Timer expired → RELEASE SNEAK
            keybinds.setPressed("sneak", false);
            moduleSneaking = false;

            // Reset timer with delay + noise (Slinky 1:1)
            float delay = (float) modules.getSlider(scriptName, "Unsneak Delay");

            if (modules.getButton(scriptName, "Randomize")) {
                // Smooth random delay (period 30 ticks, fixed range [0, 1])
                float r = smoothNoise(0.0f, 1.0f, 30.0, 1);
                delay += r;

                // 1/60 chance for extra random delay (period 60 ticks)
                if (smoothNoiseChance(60.0, 2)) {
                    delay += (float) Math.random();
                }
            }
            unsneakTimer = (double) delay + unsneakTimer;
        }
    }
    // If willFallOff → still on edge → keep sneaking (do nothing)
}

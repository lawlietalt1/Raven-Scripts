boolean blocking = false;
boolean canBlock = false;
long blockEnd = 0;
int lastHurt = 0;
boolean interacting = false;
boolean hasEnemyNearby = false;

// Ms-precise hit tracking
long hitTime = 0;

// Lag mode — packet queue (timestamp → list of packets)
// ALL outbound packets are queued during lag, not just C08.
// This mirrors LagRange/FakeLag architecture from ravenbs-main.
boolean lagActive = false;
long lagEndTime = 0;
Vec3 lagStartPos = null; // server's last known position (drawn in onRenderWorld)
// We use ArrayList of Object[]{Long releaseTime, CPacket packet} as a simple queue
// since Raven scripts don't have ConcurrentSkipListMap.
// Access is always on the game thread (onPacketSent/onRenderTick) so no need for
// a background timer thread — we drain on each onRenderTick frame instead.
ArrayList lagQueue = new ArrayList(); // each entry: Object[]{ Long releaseTime, CPacket packet }
boolean lagTimerRunning = false;

void onLoad() {
    modules.registerSlider("Maximum Hurt Time", " ms", 330, 50, 1000, 1);
    modules.registerSlider("Maximum Hold Duration", " ms", 50, 50, 500, 1);
    modules.registerSlider("Range", " blocks", 3.5, 1.0, 6.0, 0.1);
    modules.registerSlider("Lag Chance", "%", 100, 0, 100, 1);
    modules.registerSlider("Lag Max Duration", " ms", 210, 50, 1000, 25);
    modules.registerButton("Block Again Immediately", true);
    modules.registerButton("Prevent Delaying Attacks", true);
    modules.registerButton("Require Damage", true);
    modules.registerButton("Right Click Require", true);
    modules.registerButton("Ignore Teammates", true);
    modules.registerButton("Ignore Bots", true);
    modules.registerButton("Debug", false);
}

void onEnable() {
    blocking = false;
    canBlock = false;
    hitTime = 0;
    flushQueue(); // ensure clean state
    lagActive = false;
    lagEndTime = 0;
    lagStartPos = null;
}

void onDisable() {
    // Always flush queued packets before disabling to avoid desync
    flushQueue();
    lagActive = false;
    lagEndTime = 0;
    lagStartPos = null;
    keybinds.setPressed("use", false);
    blocking = false;
    canBlock = false;
    interacting = false;
}

// ─────────────────────────────────────────────────────────────────────
// Intercept ALL outbound packets during lag window.
// This is the correct approach — queueing only C08 causes bad packet
// sequences (server gets position/attack without the C08 stop-use in order).
// ─────────────────────────────────────────────────────────────────────
boolean onPacketSent(CPacket packet) {
    if (!lagActive) return true;

    // Never queue login or handshake packets (same pattern as LagRange)
    String name = packet.name;
    if (name.equals("C00PacketLoginStart") || name.equals("C00Handshake")) {
        return true;
    }

    // Never delay shop/inventory interactions — flush lag first so position is current,
    // then let the packet through. Prevents out-of-order (interact at frozen position).
    if (name.equals("C0EPacketClickWindow") || name.equals("C0DPacketCloseWindow")) {
        flushQueue();
        lagActive = false;
        return true;
    }

    if (name.equals("C02PacketUseEntity")) {
        C02 c02 = (C02) packet;
        if (c02.action != null) {
            if (c02.action.startsWith("ATTACK")) {
                // Attack — flush lag so CPS isn't delayed (Prevent Delaying Attacks)
                boolean preventDelay = modules.getButton(scriptName, "Prevent Delaying Attacks");
                if (preventDelay) {
                    flushQueue();
                    lagActive = false;
                    return true; // let attack packet through
                }
            } else {
                // INTERACT / INTERACT_AT — shop NPC, villager, etc.
                // Flush lag first so server has current position, then let through.
                flushQueue();
                lagActive = false;
                return true;
            }
        }
    }

    // Queue packet — all packets in this lag cycle share the same release time
    long releaseTime = lagEndTime;
    synchronized (lagQueue) {
        lagQueue.add(new Object[]{ releaseTime, packet });
    }
    return false; // suppress — will be sent by drainQueue()
}

// ─────────────────────────────────────────────────────────────────────
// Drain the queue: release all packets whose release time has passed.
// Called every frame from onRenderTick.
// ─────────────────────────────────────────────────────────────────────
void drainQueue() {
    if (lagQueue.isEmpty()) return;
    long now = client.time();
    int drained = 0;
    synchronized (lagQueue) {
        int i = 0;
        while (i < lagQueue.size()) {
            Object[] entry = (Object[]) lagQueue.get(i);
            long releaseTime = (long) (Long) entry[0];
            if (now >= releaseTime) {
                client.sendPacketNoEvent((CPacket) entry[1]);
                lagQueue.remove(i);
                drained++;
            } else {
                i++;
            }
        }
    }
    if (drained > 0 && modules.getButton(scriptName, "Debug")) {
        client.print("[LAG] drain: " + drained + " packets released (" + lagQueue.size() + " remaining)");
    }
}

// Release all queued packets immediately (flush on demand)
void flushQueue() {
    lagStartPos = null;
    if (lagQueue.isEmpty()) return;
    synchronized (lagQueue) {
        int count = lagQueue.size();
        for (int i = 0; i < lagQueue.size(); i++) {
            Object[] entry = (Object[]) lagQueue.get(i);
            client.sendPacketNoEvent((CPacket) entry[1]);
        }
        lagQueue.clear();
        if (modules.getButton(scriptName, "Debug")) {
            client.print("[LAG] flush: " + count + " packets released");
        }
    }
}

boolean passesClickRequire() {
    if (modules.getButton(scriptName, "Right Click Require") && !keybinds.isMouseDown(1)) return false;
    return true;
}

// Detection only — hurtTime is tick-based, so onPreMotion is the correct place
void onPreMotion(PlayerState state) {
    Entity player = client.getPlayer();
    if (player == null) {
        canBlock = false;
        return;
    }

    int hurt = player.getHurtTime();

    if (!player.isHoldingSword()) {
        canBlock = false;
        lastHurt = hurt;
        return;
    }

    // ALWAYS detect new hits regardless of other conditions
    boolean newHit = hurt > 0 && (lastHurt == 0 || hurt > lastHurt);
    if (newHit) {
        hitTime = client.time() - ((10 - hurt) * 50L);
    }

    // Only cancel native right-click when NOT actively blocking or lagging
    if (!blocking && !lagActive) {
        keybinds.setPressed("use", false);
    }

    if (!passesClickRequire()) {
        canBlock = false;
        lastHurt = hurt;
        return;
    }
    if (interacting) {
        canBlock = false;
        lastHurt = hurt;
        return;
    }

    hasEnemyNearby = findNearestEnemy(player, modules.getSlider(scriptName, "Range")) != null;
    if (!hasEnemyNearby) {
        if (!interacting && !blocking && !lagActive) {
            keybinds.setPressed("use", false);
        }
        canBlock = false;
        lastHurt = hurt;
        return;
    }

    canBlock = true;
    lastHurt = hurt;
}

// Execution — runs every frame
void onRenderTick(float partialTicks) {
    long now = client.time();

    // Always drain queued packets (respects timestamps)
    drainQueue();

    // ── Lag mode ticker ──────────────────────────────────────────────
    if (lagActive) {
        boolean preventDelay = modules.getButton(scriptName, "Prevent Delaying Attacks");
        boolean needsAttack = preventDelay && !canBlock;

        if (now >= lagEndTime || needsAttack) {
            // Lag window expired or player needs to attack — end lag
            lagActive = false;
            flushQueue(); // release any remaining held packets

            boolean blockAgain = modules.getButton(scriptName, "Block Again Immediately");
            if (blockAgain && canBlock) {
                int maxHold = (int) modules.getSlider(scriptName, "Maximum Hold Duration");
                keybinds.setPressed("use", true);
                blocking = true;
                blockEnd = now + maxHold;
            }
        }
        return; // skip normal block/unblock cycle while lagging
    }
    // ─────────────────────────────────────────────────────────────────

    long elapsed = now - hitTime;
    double remainingImmunity = 500.0 - elapsed;
    double maxHurtTimeMs = modules.getSlider(scriptName, "Maximum Hurt Time");

    boolean requireDamage = modules.getButton(scriptName, "Require Damage");
    boolean shouldBlock;
    if (requireDamage) {
        shouldBlock = canBlock && hitTime > 0 && remainingImmunity <= maxHurtTimeMs && remainingImmunity > 0;
    } else {
        shouldBlock = canBlock && remainingImmunity <= maxHurtTimeMs;
    }

    if (shouldBlock) {
        if (blocking && now >= blockEnd) {
            startUnblock(now);
        } else if (!blocking && now >= blockEnd) {
            int maxHold = (int) modules.getSlider(scriptName, "Maximum Hold Duration");
            keybinds.setPressed("use", true);
            blocking = true;
            blockEnd = now + maxHold;
        }
    } else {
        if (blocking) {
            startUnblock(now);
        }
    }
}

// Called whenever the sword needs to be unblocked.
// Decides whether to activate lag mode or release cleanly.
void startUnblock(long now) {
    keybinds.setPressed("use", false);
    blocking = false;
    blockEnd = now + 50; // 1-tick cooldown before re-block

    double lagChance = modules.getSlider(scriptName, "Lag Chance");
    if (lagChance <= 0) return;

    double roll = util.randomDouble(0, 100);
    if (roll > lagChance) return;

    // Capture the server's last known position before lag starts
    Entity player = client.getPlayer();
    if (player != null) {
        lagStartPos = player.getPosition();
    }

    // Activate lag — onPacketSent will now queue ALL outbound packets
    long lagDuration = (long) modules.getSlider(scriptName, "Lag Max Duration");
    lagActive = true;
    lagEndTime = now + lagDuration;
}

// Draw a simple cyan ring at the server's last known player position (feet level)
void onRenderWorld(float partialTicks) {
    if (!lagActive || lagStartPos == null) return;

    Vec3 cam = render.getPosition();
    double rx = lagStartPos.x - cam.x;
    double ry = lagStartPos.y - cam.y;
    double rz = lagStartPos.z - cam.z;

    int segments = 32;
    double radius = 0.35;

    gl.push();
    gl.translate(rx, ry, rz);
    gl.depth(false);
    gl.texture2d(false);
    gl.blend(true);
    gl.lineWidth(2.0f);
    gl.begin(1); // GL_LINES
    gl.color(0.0f, 1.0f, 1.0f, 0.8f); // cyan

    for (int i = 0; i < segments; i++) {
        double a1 = 2.0 * Math.PI * i / segments;
        double a2 = 2.0 * Math.PI * (i + 1) / segments;
        gl.vertex3((float)(Math.cos(a1) * radius), 0.05f, (float)(Math.sin(a1) * radius));
        gl.vertex3((float)(Math.cos(a2) * radius), 0.05f, (float)(Math.sin(a2) * radius));
    }

    gl.end();
    gl.blend(false);
    gl.texture2d(true);
    gl.depth(true);
    gl.pop();
}

boolean isValid(Entity target, Entity player) {
    if (target == null) return false;
    String type = target.type;
    if (type == null) return false;
    if (!type.equals("EntityPlayer") && !type.equals("EntityOtherPlayerMP")) {
        if (modules.getButton(scriptName, "Ignore Bots")) return false;
    }
    if (client.isFriend(target.getName())) return false;
    if (modules.getButton(scriptName, "Ignore Teammates") && isTeammate(target, player)) return false;
    if (modules.getButton(scriptName, "Ignore Bots") && isBot(target)) return false;
    return true;
}

boolean isTeammate(Entity target, Entity player) {
    String pn = player.getDisplayName();
    String tn = target.getDisplayName();
    if (pn == null || tn == null || pn.length() < 2 || tn.length() < 2) return false;
    if (pn.charAt(0) == '\u00A7' && tn.charAt(0) == '\u00A7')
        return pn.substring(0, 2).equals(tn.substring(0, 2));
    return false;
}

boolean isBot(Entity target) {
    String name = target.getName();
    if (name == null || name.length() < 3 || name.length() > 16 || name.contains(" ")) return true;
    List tab = world.getNetworkPlayers();
    if (tab != null) {
        for (int i = 0; i < tab.size(); i++) {
            if (tab.get(i) != null && tab.get(i).toString().contains(name)) return false;
        }
        return true;
    }
    return false;
}

// Intercept right click to allow villager/chest interaction while holding sword
boolean onMouse(int button, boolean pressed) {
    if (button != 1) return true;
    if (!pressed) {
        interacting = false;
        return true;
    }
    Entity player = client.getPlayer();
    if (player == null) return true;
    if (!player.isHoldingSword()) return true;

    Object[] entityHit = client.raycastEntity(6);
    if (entityHit != null) {
        Entity target = (Entity) entityHit[0];
        if (target != null && !isValid(target, player)) {
            interacting = true;
            return true;
        }
    }

    Object[] blockHit = client.raycastBlock(6);
    if (blockHit != null) {
        Vec3 pos = (Vec3) blockHit[0];
        Block block = world.getBlockAt((int) pos.x, (int) pos.y, (int) pos.z);
        if (block != null) {
            String n = block.name;
            if (n.contains("chest") || n.contains("furnace") || n.contains("crafting")
                || n.contains("anvil") || n.contains("enchant") || n.contains("brewing")
                || n.contains("hopper") || n.contains("dropper") || n.contains("dispenser")
                || n.contains("beacon") || n.contains("door") || n.contains("gate")
                || n.contains("lever") || n.contains("button") || n.contains("trap")) {
                interacting = true;
                return true;
            }
        }
    }

    if (!hasEnemyNearby) {
        return false;
    }
    return true;
}

Entity findNearestEnemy(Entity player, double maxRange) {
    List players = world.getPlayerEntities();
    Entity nearest = null;
    double nearestDist = maxRange;
    for (int i = 0; i < players.size(); i++) {
        Entity e = (Entity) players.get(i);
        if (e.equals(player)) continue;
        if (!isValid(e, player)) continue;
        double dist = player.getPosition().distanceTo(e.getPosition());
        if (dist < nearestDist) {
            nearestDist = dist;
            nearest = e;
        }
    }
    return nearest;
}

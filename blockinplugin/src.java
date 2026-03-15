boolean autoBlockinWasEnabled = false;
int lastAutoSlot = -1;
int reEnableCountdown = 0; // counts down ticks before re-enabling AutoBlockin

String[] PICKAXE_NAMES = new String[]{
    "wooden_pickaxe",
    "stone_pickaxe",
    "iron_pickaxe",
    "golden_pickaxe",
    "diamond_pickaxe",
    "netherite_pickaxe"
};

String[] AXE_NAMES = new String[]{
    "wooden_axe",
    "stone_axe",
    "iron_axe",
    "golden_axe",
    "diamond_axe",
    "netherite_axe"
};

String[] SHEARS_NAMES = new String[]{
    "shears"
};

// Register the slider once when the script loads.
// Default = 3 ticks, range = 1–10, step = 1.
void onLoad() {
    modules.registerSlider("Re-enable Delay", "ticks", 3, 1, 10, 1);
}

String getToolForBlock(String blockName) {
    if (blockName == null) return null;

    String n = blockName.toLowerCase();

    if (n.contains("wool"))      return "SHEARS";
    if (n.contains("log"))       return "AXE";
    if (n.contains("plank"))     return "AXE";
    if (n.contains("glass"))     return "AXE";
    if (blockName.equals("obsidian"))  return "PICKAXE";
    if (blockName.equals("clay"))      return "PICKAXE";
    if (n.contains("stone"))     return "PICKAXE";
    if (n.contains("ore"))       return "PICKAXE";
    if (n.contains("brick"))     return "PICKAXE";
    if (n.contains("sandstone")) return "PICKAXE";
    if (n.contains("clay")) return "PICKAXE";

    return null;
}

int findToolInHotbar(String toolType) {
    String[] targetList;

    if (toolType.equals("PICKAXE"))      targetList = PICKAXE_NAMES;
    else if (toolType.equals("AXE"))     targetList = AXE_NAMES;
    else if (toolType.equals("SHEARS"))  targetList = SHEARS_NAMES;
    else return -1;

    for (int slot = 0; slot <= 8; slot++) {
        ItemStack item = inventory.getStackInSlot(slot);
        if (item == null) continue;

        for (String name : targetList) {
            if (name.equals(item.name)) return slot;
        }
    }
    return -1;
}

void runAutoTool() {
    Object[] hit = client.raycastBlock(5);
    if (hit == null) return;

    Block block = world.getBlockAt((Vec3) hit[0]);
    if (block == null) return;

    String toolType = getToolForBlock(block.name);
    if (toolType == null) return;

    int slot = findToolInHotbar(toolType);
    if (slot == -1) return;

    if (inventory.getSlot() != slot) {
        inventory.setSlot(slot);
        lastAutoSlot = slot;
    }
}

void onPreUpdate() {
    boolean leftHeld = keybinds.isMouseDown(0);

    if (!leftHeld) {
        // Mouse released — tick the countdown down each update.
        // Only re-enable AutoBlockin once it reaches zero.
        if (reEnableCountdown > 0) {
            reEnableCountdown--;
        }
        if (reEnableCountdown == 0 && !modules.isEnabled("AutoBlockin")) {
            modules.enable("AutoBlockin");
        }
        lastAutoSlot = -1;

    } else {
        // Mouse held — keep resetting the countdown to the slider value
        // so the delay always starts fresh from the moment of release.
        reEnableCountdown = (int) modules.getSlider(scriptName, "Re-enable Delay");

        if (modules.isEnabled("AutoBlockin")) {
            modules.disable("AutoBlockin");
        }
        runAutoTool();
    }
}

void onEnable() {
    autoBlockinWasEnabled = modules.isEnabled("AutoBlockin");
    lastAutoSlot = -1;
    reEnableCountdown = 0;
    client.print("§a[SmartTool] §fEnabled");
}

void onDisable() {
    // Restore AutoBlockin to whatever state it was in before this script touched it.
    if (autoBlockinWasEnabled) {
        if (!modules.isEnabled("AutoBlockin")) modules.enable("AutoBlockin");
    } else {
        if (modules.isEnabled("AutoBlockin")) modules.disable("AutoBlockin");
    }
    client.print("§7[SmartTool] Disabled.");
}

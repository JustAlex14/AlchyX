package com.lealex.alchyx.menu;

/**
 * A machine menu with an upgrade tab: its slots are [upgradeStart, upgradeEnd), added right after the player
 * inventory (so the inventory is [upgradeStart - 36, upgradeStart)).
 */
public interface UpgradeMenu {
    int upgradeStart();

    int upgradeEnd();

    default int upgradeSlotCount() {
        return upgradeEnd() - upgradeStart();
    }
}

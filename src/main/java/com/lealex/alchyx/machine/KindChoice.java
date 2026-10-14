package com.lealex.alchyx.machine;

import java.util.List;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * A machine whose screen is a set of kinds, each turned on or off by the player (what a mine digs, what a filter
 * lets through, what a farm grows). {@code menu/ChoiceMenu} and {@code client/ChoiceScreen} are written for it, and
 * read it on both sides: a block entity implements it and keeps the kinds and the choices in step for clients (they
 * are read from the client's copy, nothing else is sent).
 */
public interface KindChoice {
    /** The kinds on offer now, in the screen's order. */
    List<Item> offered();

    /** True if this kind is on. */
    boolean chosen(Item kind);

    /** The screen's click on the kind of this number: off, or on again (server side). */
    void toggle(int index);

    /** Something wrong with this kind, written under its name, or null. */
    default @Nullable String warning(Item kind) {
        return null;
    }

    /** What this kind puts in the basket, when that is not itself (a mine's ore block gives raw metal). */
    default Item given(Item kind) {
        return kind;
    }

    /** What is known of this kind's chances ("Comes 12% of the time"), told under the mouse. Empty: nothing to tell. */
    default List<String> odds(Item kind) {
        return List.of();
    }

    /**
     * A number the player sets on the screen with four buttons (-8, -1, +1, +8), told in these words ("Digs at
     * height 12"), or null for no dial.
     */
    default @Nullable String dial() {
        return null;
    }

    /** A press on one of the dial's buttons: the number moves by this much (server side). */
    default void turnDial(int by) {}

    /**
     * Any other button the screen sends (a number at or past the kinds' count, below ChoiceMenu.DIAL): a mod's own
     * extras, such as a side panel of upgrades. Server side. True if it was this machine's.
     */
    default boolean button(net.minecraft.world.entity.player.Player player, int id, net.minecraft.world.item.ItemStack carried) {
        return false;
    }

    /** What the button of a kind that is on says ("Grown", "Kept"). */
    String chosenLabel();

    /** Short lines written under the list (the screen wraps them, and shows five lines at most). */
    List<String> notes();
}

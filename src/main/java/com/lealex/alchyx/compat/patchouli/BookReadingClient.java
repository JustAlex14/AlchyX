package com.lealex.alchyx.compat.patchouli;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ScreenEvent;
import vazkii.patchouli.client.book.gui.GuiBookEntry;

import java.util.HashSet;
import java.util.Set;

/**
 * Client half of {@link BookReading}: reports each Patchouli entry the player opens (once per connection).
 * Registered from AlchyXClient only when Patchouli is loaded, since this class touches Patchouli's screens.
 */
public final class BookReadingClient {
    private static final Set<Identifier> SENT = new HashSet<>();
    private static ClientPacketListener sentOn;

    private BookReadingClient() {}

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GuiBookEntry gui) || gui.getEntry() == null) return;
        Identifier entry = gui.getEntry().getId();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (entry == null || connection == null || !connection.hasChannel(BookReading.Payload.TYPE)) return;
        if (connection != sentOn) {
            SENT.clear();
            sentOn = connection;
        }
        if (SENT.add(entry)) connection.send(new ServerboundCustomPayloadPacket(new BookReading.Payload(entry)));
    }
}

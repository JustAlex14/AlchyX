package com.lealex.alchyx.client.scene;

import com.lealex.alchyx.AlchyX;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jspecify.annotations.Nullable;

/**
 * Plays a {@link Trailer}: the camera leaves the player for a marker flown along the shots every frame (real time,
 * paused with the game), the player is hidden and their keys taken, every HUD layer is left out (a {@link CameraShots}
 * shot), and the trailer's own layer draws the black bars and the titles. Client only, nothing is sent to the server.
 * Commands: {@code /trailer play <id>} (origin = the block you stand in), {@code /trailer at <x> <y> <z> <id>},
 * {@code /trailer stop}, {@code /trailer list}.
 */
public final class TrailerPlayer {
    private TrailerPlayer() {}

    public static final Identifier SHOT = Identifier.fromNamespaceAndPath(AlchyX.MODID, "trailer");
    public static final Identifier LAYER = Identifier.fromNamespaceAndPath(AlchyX.MODID, "trailer");
    private static final int TEXT_FADE = 12, BARS_IN = 20;

    private static @Nullable Trailer trailer;
    private static @Nullable Marker camera;
    private static @Nullable ClientInput savedInput;
    private static Vec3 origin = Vec3.ZERO;
    private static double time;
    private static int cuesRun;
    /** The trailer's commands sorted by time (a file may list them in any order; same-time ones keep theirs). */
    private static List<Trailer.Cue> cueOrder = List.of();
    private static long lastMillis;
    /** The world's game time when the trailer started: the clock never runs ahead of the server's ticks. */
    private static long startGameTime;
    /** Real ticks the camera spent waiting for the world (told to the player at the end). */
    private static double waited;
    /** The trailer's own music while it plays (the game's music is kept quiet meanwhile). */
    private static net.minecraft.client.resources.sounds.@Nullable SimpleSoundInstance music;
    /** Ticks until the music is started again where the trailer is (a change of world stops every sound). */
    private static int musicResume;

    /** Plays the trailer's music from `seconds` in: the stream skips what was already heard. */
    private static void startMusic(Minecraft minecraft, net.minecraft.resources.Identifier sound, double seconds) {
        minecraft.getMusicManager().stopPlaying();
        music = new net.minecraft.client.resources.sounds.SimpleSoundInstance(sound, net.minecraft.sounds.SoundSource.MUSIC,
                1F, 1F, net.minecraft.util.RandomSource.create(), false, 0,
                net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0, true) {
            @Override
            public java.util.concurrent.CompletableFuture<net.minecraft.client.sounds.AudioStream> getStream(
                    net.minecraft.client.sounds.SoundBufferLibrary buffers, net.minecraft.client.resources.sounds.Sound played,
                    boolean looping) {
                java.util.concurrent.CompletableFuture<net.minecraft.client.sounds.AudioStream> stream =
                        super.getStream(buffers, played, looping);
                return seconds <= 0 ? stream : stream.thenApply(audio -> skip(audio, seconds));
            }
        };
        minecraft.getSoundManager().play(music);
    }

    private static net.minecraft.client.sounds.AudioStream skip(net.minecraft.client.sounds.AudioStream audio, double seconds) {
        javax.sound.sampled.AudioFormat format = audio.getFormat();
        long left = (long) (seconds * format.getFrameRate()) * format.getFrameSize();
        try {
            while (left > 0) {
                java.nio.ByteBuffer chunk = audio.read((int) Math.min(left, 65536));
                if (chunk == null || !chunk.hasRemaining()) break;   // past the end: it simply plays nothing
                left -= chunk.remaining();
            }
        } catch (java.io.IOException ignored) {
            // a stream that cannot be read ahead plays from where it got to
        }
        return audio;
    }
    private static float yaw, pitch, roll;
    private static float fov = Float.NaN;
    private static @Nullable LocalPlayer inputOwner;
    private static int reanchor, lost;
    private static net.minecraft.resources.@Nullable ResourceKey<net.minecraft.world.level.Level> home;

    public static boolean playing() {
        return trailer != null;
    }

    public static boolean play(Identifier id, Vec3 at) {
        Trailer found = Trailers.get(id);
        Minecraft minecraft = Minecraft.getInstance();
        if (found == null || minecraft.level == null || minecraft.player == null) return false;
        stop();
        Marker marker = EntityType.MARKER.create(minecraft.level, EntitySpawnReason.LOAD);
        if (marker == null) return false;
        trailer = found;
        camera = marker;
        origin = at;
        time = 0;
        cuesRun = 0;
        cueOrder = found.commands().stream().sorted(java.util.Comparator.comparingInt(Trailer.Cue::at)).toList();
        lastMillis = Util.getMillis();
        startGameTime = worldTicks(minecraft);
        waited = 0;
        place(0);
        LocalPlayer player = minecraft.player;
        savedInput = player.input;
        inputOwner = player;
        player.input = new StillInput();
        reanchor = 0;
        lost = 0;
        home = minecraft.level.dimension();
        musicResume = 0;
        found.music().ifPresent(sound -> startMusic(minecraft, sound, 0));
        CameraShots.take(SHOT, CameraType.FIRST_PERSON, null);
        CameraShots.watch(SHOT, marker);
        return true;
    }

    public static void stop() {
        if (trailer == null) return;
        trailer = null;
        camera = null;
        fov = Float.NaN;
        roll = 0F;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.input instanceof StillInput) {
            // the player made after a change of world never had its keys taken by us: give it fresh ones
            player.input = player == inputOwner && savedInput != null ? savedInput : new net.minecraft.client.player.KeyboardInput(Minecraft.getInstance().options);
        }
        savedInput = null;
        inputOwner = null;
        CameraShots.release(SHOT);
        if (music != null) {
            Minecraft.getInstance().getSoundManager().stop(music);
            music = null;
        }
    }

    /** Moves the marker to where the trailer is at this time (ticks from its start). */
    private static void place(double at) {
        Trailer playing = trailer;
        Marker marker = camera;
        if (playing == null || marker == null) return;
        int start = 0;
        Trailer.Shot shot = playing.shots().getLast();
        for (Trailer.Shot s : playing.shots()) {
            if (at < start + s.ticks()) {
                shot = s;
                break;
            }
            start += s.ticks();
        }
        if (at >= playing.length()) start = playing.length() - shot.ticks();
        float raw = (float) Mth.clamp((at - start) / shot.ticks(), 0, 1);
        float k = shot.eased(raw);
        Vec3 pos = origin.add(shot.position(k));
        Vec3 target = shot.target(k);
        Vec3 look;
        if (target != null) {
            look = origin.add(target).subtract(pos);
        } else {
            look = origin.add(shot.position(Math.min(1F, k + 0.01F))).subtract(pos);
            if (look.lengthSqr() < 1.0E-6) look = origin.add(shot.position(k)).subtract(origin.add(shot.position(Math.max(0F, k - 0.01F))));
        }
        if (look.lengthSqr() > 1.0E-6) {
            yaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90F;
            pitch = (float) -(Mth.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * Mth.RAD_TO_DEG);
        }
        roll = Trailer.Shot.span(shot.roll(), k, 0F);
        fov = Trailer.Shot.span(shot.fov(), k, Float.NaN);
        marker.snapTo(pos.x, pos.y, pos.z, yaw, pitch);
        marker.setOldPosAndRot();
    }

    /** Where the trailer is: shot index and how far into it, for the fades. */
    private static float fadeAt(double at) {
        Trailer playing = trailer;
        if (playing == null) return 0F;
        int start = 0;
        for (Trailer.Shot s : playing.shots()) {
            if (at < start + s.ticks()) {
                double in = at - start, out = start + s.ticks() - at;
                float dark = 0F;
                if (s.fadeIn() > 0 && in < s.fadeIn()) dark = Math.max(dark, 1F - (float) (in / s.fadeIn()));
                if (s.fadeOut() > 0 && out < s.fadeOut()) dark = Math.max(dark, 1F - (float) (out / s.fadeOut()));
                return dark;
            }
            start += s.ticks();
        }
        return 1F;
    }

    /**
     * How fast the clock may run (1 = real time). Builds, waves and machines run on the server's ticks: a server that
     * falls behind 20 ticks a second (a busy world) would leave the camera ahead of them, filming a machine before it
     * stands or works. So the clock eases off once it is more than a few ticks ahead of the world's game time (the
     * client's copy, corrected by the server every second) and almost stops while far ahead. Behind is left alone.
     */
    private static double pace(Minecraft minecraft) {
        if (minecraft.level == null) return 1;
        double lead = time - (worldTicks(minecraft) - startGameTime);
        if (lead <= 4) return 1;
        return Math.max(0.1, 1 - (lead - 4) / 16);
    }

    /**
     * Ticks the world has really run: in single player the integrated server's own count (exact, even when it falls
     * far behind), else the client's game time (the server corrects it once a second).
     */
    private static long worldTicks(Minecraft minecraft) {
        net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) return server.getTickCount();
        return minecraft.level == null ? 0 : minecraft.level.getGameTime();
    }

    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (trailer == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        long now = Util.getMillis();
        // paused with the game, and while another cinematic (a boss's entrance a command started) holds the camera
        if (!minecraft.isPaused() && CameraShots.topIs(SHOT)) {
            double real = Math.min(250, now - lastMillis) / 50.0, step = real * pace(minecraft);
            time += step;
            waited += real - step;
        }
        lastMillis = now;
        place(time);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Trailer playing = trailer;
        if (playing == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || camera == null) {
            if (++lost > 200) stop(); // between worlds for a moment is fine; gone for good is not
            return;
        }
        lost = 0;
        if (camera.level() != minecraft.level) {
            // a command took the player to another world (a void arena): the camera follows, and the shots that come
            // next are measured from where the player now stands (re-read for a few ticks while their place settles)
            Marker moved = EntityType.MARKER.create(minecraft.level, EntitySpawnReason.LOAD);
            if (moved == null) {
                stop();
                return;
            }
            camera = moved;
            reanchor = 15;
            if (music != null) musicResume = 10; // the change of world stopped it: start it again once the world is in
        }
        if (!(minecraft.player.input instanceof StillInput)) minecraft.player.input = new StillInput();
        if (music != null) minecraft.getMusicManager().stopPlaying(); // no game music over the trailer's own
        if (musicResume > 0 && --musicResume == 0 && music != null && !minecraft.getSoundManager().isActive(music)) {
            startMusic(minecraft, music.getIdentifier(), time / 20.0);
        }
        if (!CameraShots.active(SHOT)) CameraShots.take(SHOT, CameraType.FIRST_PERSON, null);
        if (minecraft.getCameraEntity() != camera && CameraShots.topIs(SHOT)) CameraShots.watch(SHOT, camera);
        if (reanchor > 0) {
            reanchor--;
            origin = Vec3.atLowerCornerOf(minecraft.player.blockPosition());
            // back where it began: the player stands on the map's standing spot again, not on its origin
            if (minecraft.level.dimension() == home) origin = origin.subtract(playing.stand());
        }
        List<Trailer.Cue> cues = cueOrder;
        while (cuesRun < cues.size() && cues.get(cuesRun).at() <= time) {
            String command = cues.get(cuesRun++).command().trim();
            if (command.startsWith("/")) command = command.substring(1);
            if (!command.isEmpty()) minecraft.player.connection.sendCommand(command);
        }
        if (time >= playing.length() && CameraShots.topIs(SHOT)) {
            if (waited >= 20) minecraft.gui.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Trailer: the camera waited " + Math.round(waited / 20) + " s for the world (the server ran behind)"));
            stop();
            return;
        }
        float dark = fadeAt(time);
        if (dark > 0F) ScreenFade.show(dark, 0x000000);
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (trailer == null || !CameraShots.topIs(SHOT)) return;
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(roll);
    }

    public static void onFov(ViewportEvent.ComputeFov event) {
        if (trailer != null && !Float.isNaN(fov) && CameraShots.topIs(SHOT)) event.setFOV(fov);
    }

    /** The player stays out of their own trailer. */
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?, ?> event) {
        if (trailer == null || !(event.getRenderState() instanceof AvatarRenderState state)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && state.id == player.getId()) event.setCanceled(true);
    }

    /** Never the inside of a block over the screen (the player may stand in one; the camera is elsewhere). */
    public static void onBlockOverlay(net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent event) {
        if (trailer != null) event.setCanceled(true);
    }

    /** No hands or held item in the shot (a trailer may hand the player a tool for what it shows, like a tuner). */
    public static void onRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (trailer != null) event.setCanceled(true);
    }

    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, TrailerPlayer::draw);
        CameraShots.keepDuringScenes(LAYER);
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Trailer playing = trailer;
        if (playing == null) return;
        int w = graphics.guiWidth(), h = graphics.guiHeight();
        if (playing.letterbox()) {
            double end = playing.length() - time;
            float k = (float) Mth.clamp(Math.min(time, end) / BARS_IN, 0, 1);
            k = k * k * (3 - 2 * k);
            int bar = Math.round(h * 0.11F * k);
            if (bar > 0) {
                graphics.fill(0, 0, w, bar, 0xFF000000);
                graphics.fill(0, h - bar, w, h, 0xFF000000);
            }
        }
        Font font = Minecraft.getInstance().font;
        for (Trailer.Text text : playing.texts()) {
            double in = time - text.at(), out = text.at() + text.ticks() - time;
            if (in < 0 || out < 0) continue;
            float alpha = (float) Mth.clamp(Math.min(in, out) / TEXT_FADE, 0, 1);
            if (alpha < 0.03F) continue;
            int argb = Math.max(5, Math.round(alpha * 255)) << 24 | text.rgb();
            Component line = Component.translatableWithFallback(text.text(), text.text());
            float scale;
            float y;
            switch (text.style()) {
                case "subtitle" -> {
                    scale = 1.5F;
                    y = h * 0.55F;
                }
                case "caption" -> {
                    scale = 1F;
                    y = h * 0.84F;
                }
                default -> {
                    scale = 3F;
                    y = h * 0.40F;
                }
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate(w / 2F, y);
            graphics.pose().scale(scale, scale);
            graphics.centeredText(font, line, 0, -font.lineHeight / 2, argb);
            graphics.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------ command

    private static final SuggestionProvider<CommandSourceStack> IDS = (context, builder) ->
            SharedSuggestionProvider.suggest(Trailers.all().keySet().stream().map(Identifier::toString), builder);

    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("trailer")
                .then(Commands.literal("play").then(Commands.argument("id", StringArgumentType.greedyString()).suggests(IDS)
                        .executes(c -> {
                            LocalPlayer player = Minecraft.getInstance().player;
                            if (player == null) return 0;
                            Vec3 at = Vec3.atLowerCornerOf(player.blockPosition());
                            Identifier id = Identifier.tryParse(StringArgumentType.getString(c, "id").trim());
                            Trailer wanted = id == null ? null : Trailers.get(id);
                            return start(c, wanted == null ? at : at.subtract(wanted.stand())); // from the map's standing spot
                        })))
                .then(Commands.literal("at").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("id", StringArgumentType.greedyString()).suggests(IDS)
                                        .executes(c -> start(c, new Vec3(IntegerArgumentType.getInteger(c, "x"),
                                                IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")))))))))
                .then(Commands.literal("stop").executes(c -> {
                    stop();
                    return 1;
                }))
                .then(Commands.literal("list").executes(c -> {
                    List<String> ids = Trailers.all().keySet().stream().map(Identifier::toString).sorted().toList();
                    c.getSource().sendSuccess(() -> Component.translatable("commands.alchyx.trailer.list", ids.size(), String.join(", ", ids)), false);
                    return ids.size();
                })));
    }

    private static int start(CommandContext<CommandSourceStack> context, Vec3 at) {
        String raw = StringArgumentType.getString(context, "id").trim();
        Identifier id = Identifier.tryParse(raw);
        if (id == null || !play(id, at)) {
            context.getSource().sendFailure(Component.translatable("commands.alchyx.trailer.unknown", raw));
            return 0;
        }
        Trailer playing = trailer;
        int seconds = playing == null ? 0 : playing.length() / 20;
        context.getSource().sendSuccess(() -> Component.translatable("commands.alchyx.trailer.playing", raw, seconds), false);
        return 1;
    }

    private static final class StillInput extends ClientInput {
        @Override
        public void tick() {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
        }
    }
}

package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.animation.Keyframe;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.client.miniature.BoxMesh;
import com.lealex.alchyx.miniature.BlockBox;
import com.lealex.alchyx.multiblock.FreeBuild;
import com.lealex.alchyx.multiblock.MachineStudio;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.lealex.alchyx.multiblock.ShellParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Machine Studio (see multiblock/MachineStudio): a formed free-build machine shown in 3D, like a page of a
 * guide book, but one you change.
 * <ul>
 *   <li>drag to turn the view, the wheel to zoom, the layer buttons to look inside;</li>
 *   <li>click a block to choose it (shift + click: add it, ctrl + click: every block of its kind), then a motion
 *       on the right;</li>
 *   <li>Play shows the motions as they will look; Save sends them to the machine (nothing changes before).</li>
 * </ul>
 */
public class StudioScreen extends Screen {
    private static final int PANEL_WIDE = 132, MARGIN = 8, TOP = 22, BAR = 30, ROW = 22;
    private static final int BACK = 0xE0101018, VIEW_BACK = 0xFF1A1A26, EDGE = 0xFF3C3550, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0,
            FAINT = 0xFF9A93AA, WARN = 0xFFFFB866, GOOD = 0xFF8BE39B;
    private static final float START_YAW = (float) Math.toRadians(-35), START_PITCH = (float) Math.toRadians(-28);
    private static final BlockState CHOSEN_MARK = Blocks.MAGENTA_STAINED_GLASS.defaultBlockState();
    private static final BlockState HOVER_MARK = Blocks.WHITE_STAINED_GLASS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final BlockPos corePos;
    private final MultiblockPattern shape;
    private final FreeBuild rules;
    private final BlockPos min, size;                        // the build's box: lowest offset from the core, and its size
    private final Map<BlockPos, BlockState> looks = new HashMap<>();     // what shows at each cell (box coordinates)
    private final Set<BlockPos> movable = new LinkedHashSet<>();        // cells that can be given a motion
    private final Map<BlockPos, Motion> edits = new LinkedHashMap<>();  // the motions as changed here (box coordinates)
    private final Map<BlockPos, Motion> saved = new LinkedHashMap<>();  // as the machine has them
    private final Set<BlockPos> chosen = new LinkedHashSet<>();
    private final Map<BlockState, BoxMesh> pieceMeshes = new HashMap<>();

    private float yaw = START_YAW, pitch = START_PITCH, zoom = 1;
    private int top;                       // the highest layer shown (box y), size.y - 1 = all of them
    private boolean playing = true;
    private float clock;                   // ticks the preview has played
    private boolean dragging;
    private double dragged;                // how far the mouse moved while held (a click if it hardly moved)
    private @Nullable BoxMesh still;
    private boolean stillDirty = true;
    private @Nullable BlockPos hovered;

    private @Nullable Button playButton, saveButton, layerDown, layerUp;
    private final List<Button> motionButtons = new ArrayList<>();
    private final List<@Nullable Motion> motionChoices = new ArrayList<>();

    public StudioScreen(MultiblockCoreBlockEntity core) {
        super(Component.literal("Machine Studio"));
        this.corePos = core.getBlockPos().immutable();
        this.shape = core.recordedShape();
        this.rules = core.freeBuild();
        this.min = shape.minOffset();
        BlockPos max = shape.maxOffset();
        this.size = new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        this.top = size.getY() - 1;
        ClientLevel level = Minecraft.getInstance().level;
        for (MultiblockPattern.LookCell cell : shape.formedLook()) {
            BlockPos at = cell.offset().subtract(min);
            looks.put(at, cell.state());
            if (cell.offset().equals(BlockPos.ZERO) || level == null) continue;
            if (ShellParts.isPart(level.getBlockState(corePos.offset(cell.offset())))) movable.add(at);
        }
        core.motions().forEach((offset, motion) -> saved.put(offset.subtract(min), motion));
        edits.putAll(saved);
    }

    /** Opens the studio for the core at this position, if it still shows a formed free-build machine. */
    public static void open(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof MultiblockCoreBlockEntity core && MachineStudio.opens(core)) {
            minecraft.setScreen(new StudioScreen(core));
        }
    }

    // ---- Layout ----

    private int viewX0() { return MARGIN; }
    private int viewY0() { return TOP; }
    private int viewX1() { return width - PANEL_WIDE - MARGIN * 2; }
    private int viewY1() { return height - BAR; }
    private int panelX() { return width - PANEL_WIDE - MARGIN; }

    @Override
    protected void init() {
        motionButtons.clear();
        motionChoices.clear();
        int y = height - BAR + 5, x = MARGIN;
        playButton = addRenderableWidget(Button.builder(Component.literal(playing ? "Pause" : "Play"), b -> {
            playing = !playing;
            stillDirty = true;
            b.setMessage(Component.literal(playing ? "Pause" : "Play"));
        }).bounds(x, y, 46, 20).build());
        x += 50;
        layerDown = addRenderableWidget(Button.builder(Component.literal("-"), b -> layers(-1)).bounds(x, y, 20, 20).build());
        layerDown.setTooltip(Tooltip.create(Component.literal("Hide the top layer: look inside")));
        x += 24 + 58;
        layerUp = addRenderableWidget(Button.builder(Component.literal("+"), b -> layers(1)).bounds(x, y, 20, 20).build());
        layerUp.setTooltip(Tooltip.create(Component.literal("Show one more layer")));
        x += 24;
        addRenderableWidget(Button.builder(Component.literal("Reset view"), b -> {
            yaw = START_YAW;
            pitch = START_PITCH;
            zoom = 1;
        }).bounds(x, y, 64, 20).build());

        saveButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(width - MARGIN - 104, y, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width - MARGIN - 50, y, 50, 20).build());

        // A button per motion this machine allows, and one to keep blocks still
        int px = panelX() + 4, py = TOP + 46;
        List<@Nullable Motion> choices = new ArrayList<>();
        choices.add(null);
        choices.addAll(rules.motions());
        for (Motion motion : choices) {
            Button button = addRenderableWidget(Button.builder(Component.literal(label(motion)), b -> give(motion))
                    .bounds(px, py, PANEL_WIDE - 8, 18).build());
            button.setTooltip(Tooltip.create(Component.literal(explain(motion))));
            motionButtons.add(button);
            motionChoices.add(motion);
            py += ROW - 2;
        }
        refreshButtons();
    }

    private static String label(@Nullable Motion motion) {
        return motion == null ? "Still" : capital(motion.getSerializedName());
    }

    private static String explain(@Nullable Motion motion) {
        if (motion == null) return "The block doesn't move";
        return switch (motion) {
            case SPIN -> "Turns around itself";
            case BOB -> "Floats up and down";
            case STRIKE -> "Strikes out the way it faces, like a piston";
            case SWING -> "Swings a quarter turn, like a door";
            case SHAKE -> "Trembles in place";
        };
    }

    private void refreshButtons() {
        if (layerDown != null) layerDown.active = top > 0;
        if (layerUp != null) layerUp.active = top < size.getY() - 1;
        if (saveButton != null) saveButton.active = !edits.equals(saved);
        Motion shared = null;
        boolean same = true, first = true;
        for (BlockPos cell : chosen) {
            Motion motion = edits.get(cell);
            if (first) shared = motion;
            else if (motion != shared) same = false;
            first = false;
        }
        for (int i = 0; i < motionButtons.size(); i++) {
            Button button = motionButtons.get(i);
            Motion motion = motionChoices.get(i);
            button.active = !chosen.isEmpty();
            boolean current = !chosen.isEmpty() && same && motion == shared;
            button.setMessage(Component.literal(current ? "> " + label(motion) + " <" : label(motion)));
        }
    }

    // ---- Changes ----

    private void give(@Nullable Motion motion) {
        for (BlockPos cell : chosen) {
            if (motion == null) edits.remove(cell);
            else edits.put(cell, motion);
        }
        stillDirty = true;
        refreshButtons();
    }

    private void layers(int change) {
        top = Mth.clamp(top + change, 0, size.getY() - 1);
        stillDirty = true;
        refreshButtons();
    }

    private void save() {
        Map<BlockPos, Motion> motions = new LinkedHashMap<>();
        edits.forEach((cell, motion) -> motions.put(cell.offset(min), motion));
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        connection.send(new ServerboundCustomPayloadPacket(new MachineStudio.Save(corePos, motions)));
        saved.clear();
        saved.putAll(edits);
        refreshButtons();
    }

    // ---- Each tick: the machine must still be there ----

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(corePos) instanceof MultiblockCoreBlockEntity core)
                || !MachineStudio.opens(core) || core.recordedShape() != shape
                || minecraft.player == null || minecraft.player.distanceToSqr(corePos.getCenter()) > 16 * 16) {
            onClose();
            return;
        }
        if (playing) clock++;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- The mouse ----

    private boolean inView(double x, double y) {
        return x >= viewX0() && x < viewX1() && y >= viewY0() && y < viewY1();
    }

    private float scale() {
        float fit = Math.min(viewX1() - viewX0(), viewY1() - viewY0()) / (float) Math.sqrt(
                size.getX() * size.getX() + size.getY() * size.getY() + size.getZ() * size.getZ());
        return fit * zoom;
    }

    private Vec3 center() {
        return new Vec3(size.getX() / 2.0, (top + 1) / 2.0, size.getZ() / 2.0);
    }

    private @Nullable BlockPos under(double x, double y) {
        if (!inView(x, y)) return null;
        List<BlockPos> shown = new ArrayList<>();
        for (BlockPos cell : looks.keySet()) if (cell.getY() <= top) shown.add(cell);
        return StudioView.pick(x - (viewX0() + viewX1()) / 2.0, y - (viewY0() + viewY1()) / 2.0, scale(), yaw, pitch, center(), shown);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && inView(event.x(), event.y())) {
            dragging = true;
            dragged = 0;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            dragged += Math.abs(dx) + Math.abs(dy);
            yaw += (float) (dx * 0.012);
            pitch = Mth.clamp(pitch - (float) (dy * 0.012), (float) -Math.PI / 2, (float) Math.PI / 2);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && event.button() == 0) {
            dragging = false;
            if (dragged < 3) choose(under(event.x(), event.y()), event.hasShiftDown(), event.hasControlDown());
            return true;
        }
        return super.mouseReleased(event);
    }

    private void choose(@Nullable BlockPos cell, boolean add, boolean allOfKind) {
        if (!add) chosen.clear();
        if (cell != null && movable.contains(cell)) {
            if (allOfKind) {
                Object block = looks.get(cell).getBlock();
                for (BlockPos other : movable) if (looks.get(other).getBlock() == block) chosen.add(other);
            } else if (add && chosen.contains(cell)) {
                chosen.remove(cell);
            } else {
                chosen.add(cell);
            }
        }
        refreshButtons();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (inView(x, y)) {
            zoom = Mth.clamp(zoom * (float) Math.pow(1.15, scrollY), 0.4F, 4.0F);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 32) { // space: play / pause
            if (playButton != null) playButton.onPress(event);
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- Drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        hovered = dragging ? hovered : under(mouseX, mouseY);
        int x0 = viewX0(), y0 = viewY0(), x1 = viewX1(), y1 = viewY1();
        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, EDGE);
        graphics.fill(x0, y0, x1, y1, VIEW_BACK);
        graphics.submitPictureInPictureRenderState(new StudioRenderState(stillMesh(), pieces(a), center(), yaw, pitch,
                x0, y0, x1, y1, scale(), graphics.peekScissorStack()));

        // Header
        String name = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getBlockState(corePos).getBlock().getName().getString() : "";
        graphics.text(font, "Machine Studio", MARGIN, 7, ACCENT, false);
        graphics.text(font, name, MARGIN + font.width("Machine Studio") + 8, 7, FAINT, false);
        if (!edits.equals(saved)) {
            String unsaved = "Unsaved changes";
            graphics.text(font, unsaved, viewX1() - font.width(unsaved), 7, WARN, false);
        }
        // Under the view: the layers shown, and what the mouse is on
        graphics.text(font, top == size.getY() - 1 ? "All layers" : "Layers 1-" + (top + 1), MARGIN + 74, height - BAR + 11, TEXT, false);
        if (hovered != null) {
            BlockState look = looks.get(hovered);
            String says = look.getBlock().getName().getString();
            Motion motion = edits.get(hovered);
            if (motion != null) says += ": " + motion.getSerializedName();
            else if (!movable.contains(hovered)) says += hovered.offset(min).equals(BlockPos.ZERO) ? " (the core)" : " (stays as it is)";
            graphics.text(font, says, x0 + 4, y1 - 12, TEXT, true);
        }

        panel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void panel(GuiGraphicsExtractor graphics) {
        int px = panelX(), py = TOP;
        graphics.fill(px - 1, py - 1, px + PANEL_WIDE + 1, viewY1() + 1, EDGE);
        graphics.fill(px, py, px + PANEL_WIDE, viewY1(), VIEW_BACK);
        int tx = px + 5;
        if (chosen.isEmpty()) {
            graphics.text(font, "Click a block", tx, py + 6, ACCENT, false);
            graphics.text(font, movable.size() + " blocks can move", tx, py + 18, FAINT, false);
        } else if (chosen.size() == 1) {
            BlockPos cell = chosen.iterator().next();
            graphics.text(font, clip(looks.get(cell).getBlock().getName().getString()), tx, py + 6, ACCENT, false);
            BlockPos offset = cell.offset(min);
            graphics.text(font, "At " + offset.getX() + " " + offset.getY() + " " + offset.getZ() + " from the core", tx, py + 18, FAINT, false);
        } else {
            graphics.text(font, chosen.size() + " blocks chosen", tx, py + 6, ACCENT, false);
            graphics.text(font, "A motion goes to all", tx, py + 18, FAINT, false);
        }
        graphics.text(font, "Motion", tx, py + 34, TEXT, false);

        int y = TOP + 46 + motionButtons.size() * (ROW - 2) + 6;
        String when = switch (rules.motionWhen()) {
            case IDLE -> "Plays while it rests";
            case WORKING -> "Plays while it works";
            default -> "Plays all the time";
        };
        graphics.text(font, when, tx, y, playing ? GOOD : FAINT, false);
        y += 16;
        for (String help : List.of("Drag: turn", "Wheel: zoom", "Click: choose a block", "Shift+click: add one", "Ctrl+click: all alike",
                "Space: play / pause")) {
            if (y > viewY1() - 10) break;
            graphics.text(font, help, tx, y, FAINT, false);
            y += 10;
        }
    }

    private String clip(String text) {
        int room = PANEL_WIDE - 10;
        if (font.width(text) <= room) return text;
        while (!text.isEmpty() && font.width(text + "...") > room) text = text.substring(0, text.length() - 1);
        return text + "...";
    }

    // ---- What the view shows ----

    /** The blocks that stand still now, as one mesh (made again when the layers, the motions or play change). */
    private BoxMesh stillMesh() {
        if (still == null || stillDirty || still.isStale()) {
            ClientLevel level = Minecraft.getInstance().level;
            still = BoxMesh.build(level, corePos, BlockBox.of(size.getX(), size.getY(), size.getZ(), false, (x, y, z) -> {
                if (y > top) return AIR;
                BlockPos cell = new BlockPos(x, y, z);
                if (playing && edits.containsKey(cell)) return AIR; // drawn on its own, moving
                BlockState look = looks.get(cell);
                return look == null ? AIR : look;
            }), 400_000);
            stillDirty = false;
        }
        return still;
    }

    /** One block on its own: a box two high with the block on top (so its bottom face is drawn too). */
    private BoxMesh pieceMesh(BlockState state) {
        BoxMesh mesh = pieceMeshes.get(state);
        if (mesh == null || mesh.isStale()) {
            mesh = BoxMesh.build(Minecraft.getInstance().level, corePos, BlockBox.of(1, 2, 1, false, (x, y, z) -> y == 1 ? state : AIR), 10_000);
            pieceMeshes.put(state, mesh);
        }
        return mesh;
    }

    /** The moving blocks where they are now, and the marks over the chosen and pointed-at blocks. */
    private List<StudioRenderState.Piece> pieces(float partialTicks) {
        List<StudioRenderState.Piece> pieces = new ArrayList<>();
        float time = clock + (playing ? partialTicks : 0);
        if (playing) {
            for (Map.Entry<BlockPos, Motion> entry : edits.entrySet()) {
                BlockPos cell = entry.getKey();
                BlockState look = looks.get(cell);
                if (look == null || cell.getY() > top) continue;
                List<Keyframe> frames = entry.getValue().keyframes();
                float loop = Math.max(1, Keyframe.duration(frames));
                Keyframe pose = Keyframe.at(frames, time % loop);
                Direction d = direction(entry.getValue(), cell, look);
                Vec3 at = Vec3.atLowerCornerOf(cell).add(d.getStepX() * pose.move(), d.getStepY() * pose.move(), d.getStepZ() * pose.move())
                        .add(pose.offset());
                pieces.add(new StudioRenderState.Piece(pieceMesh(look), at, pose.rotation(), pose.scale()));
            }
        }
        for (BlockPos cell : chosen) {
            if (cell.getY() <= top) pieces.add(mark(cell, CHOSEN_MARK, 1.08F));
        }
        if (hovered != null && !chosen.contains(hovered) && movable.contains(hovered)) pieces.add(mark(hovered, HOVER_MARK, 1.05F));
        return pieces;
    }

    private StudioRenderState.Piece mark(BlockPos cell, BlockState glass, float size) {
        return new StudioRenderState.Piece(pieceMesh(glass), Vec3.atLowerCornerOf(cell), Vec3.ZERO, size);
    }

    /** The way a motion moves along at this cell: up, or for "facing" the block's facing (else toward the core). */
    private Direction direction(Motion motion, BlockPos cell, BlockState look) {
        if (!"facing".equals(motion.direction())) return Direction.UP;
        Property<?> property = look.getBlock().getStateDefinition().getProperty("facing");
        if (property != null && look.getValue(property) instanceof Direction facing) return facing;
        BlockPos offset = cell.offset(min);
        if (offset.getX() == 0 && offset.getZ() == 0) return offset.getY() <= 0 ? Direction.UP : Direction.DOWN;
        return Direction.getApproximateNearest(-offset.getX(), 0, -offset.getZ());
    }

    private static String capital(String words) {
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1).toLowerCase(Locale.ROOT);
    }
}

package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.BlockLook;
import com.lealex.alchyx.animation.Keyframe;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.animation.StudioMark;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.client.miniature.BoxMesh;
import com.lealex.alchyx.client.miniature.Puppets;
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
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
 * guide book, but one you change. Two tabs:
 * <ul>
 *   <li><b>Blocks</b>: click a block to choose it (shift + click: add it, ctrl + click: every block of its kind),
 *       then a motion on the right: a move (spin, bob, strike...) or a change of look (light, fill, open) for the
 *       blocks that can;</li>
 *   <li><b>Anchors</b>: click the face of a block to pick the spot in front of it, then put particles or a figure
 *       (an entity) there.</li>
 * </ul>
 * Drag to turn the view, the wheel to zoom, the layer buttons to look inside. Play shows it all as it will look;
 * Save sends it to the machine (nothing changes before).
 */
public class StudioScreen extends Screen {
    private static final int PANEL_WIDE = 132, MARGIN = 8, TOP = 22, BAR = 30, ROW = 20, LIST_TOP = TOP + 64;
    private static final int BACK = 0xE0101018, VIEW_BACK = 0xFF1A1A26, EDGE = 0xFF3C3550, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0,
            FAINT = 0xFF9A93AA, WARN = 0xFFFFB866, GOOD = 0xFF8BE39B;
    private static final float START_YAW = (float) Math.toRadians(-35), START_PITCH = (float) Math.toRadians(-28);
    private static final BlockState CHOSEN_MARK = Blocks.MAGENTA_STAINED_GLASS.defaultBlockState();
    private static final BlockState HOVER_MARK = Blocks.WHITE_STAINED_GLASS.defaultBlockState();
    private static final BlockState PARTICLE_MARK = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
    private static final BlockState SPOT_MARK = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState ITEM_MARK = Blocks.ORANGE_STAINED_GLASS.defaultBlockState();
    private static final BlockState PATH_MARK = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
    private static final int OPERATION = 100; // the preview's pretend work: one operation this many ticks long

    private enum Tab { BLOCKS, ANCHORS }

    private final BlockPos corePos;
    private final MultiblockPattern shape;
    private final FreeBuild rules;
    private final BlockPos min, size;                        // the build's box: lowest offset from the core, and its size
    private final Map<BlockPos, BlockState> looks = new HashMap<>();     // what shows at each cell (box coordinates)
    private final Set<BlockPos> movable = new LinkedHashSet<>();        // cells that can be given a motion
    private final Map<BlockPos, Motion> edits = new LinkedHashMap<>();  // the motions as changed here (box coordinates)
    private final Map<BlockPos, Motion> saved = new LinkedHashMap<>();  // as the machine has them
    private final Map<BlockPos, StudioMark> marks = new LinkedHashMap<>();     // the anchors as changed here (box coordinates)
    private final Map<BlockPos, StudioMark> savedMarks = new LinkedHashMap<>();
    private final Set<BlockPos> chosen = new LinkedHashSet<>();
    private final Map<BlockState, BoxMesh> pieceMeshes = new HashMap<>();
    private final Map<Identifier, @Nullable Entity> standIns = new HashMap<>();

    private Tab tab = Tab.BLOCKS;
    private @Nullable BlockPos spot;       // the anchor spot picked (box coordinates)
    private float yaw = START_YAW, pitch = START_PITCH, zoom = 1;
    private int top;                       // the highest layer shown (box y), size.y - 1 = all of them
    private boolean playing = true;
    private float clock;                   // ticks the preview has played
    private boolean dragging;
    private double dragged;                // how far the mouse moved while held (a click if it hardly moved)
    private @Nullable BoxMesh still;
    private boolean stillDirty = true;
    private int stillStep = -1;            // the preview step the still mesh shows (changes of look)
    private StudioView.@Nullable Hit hovered;
    private boolean pickingTo;             // the next spot picked is where the chosen item travels to
    private boolean scrubbing;             // the mouse holds the work bar

    private @Nullable Button saveButton, layerDown, layerUp, blocksTab, anchorsTab;
    private final List<Button> motionButtons = new ArrayList<>();
    private final List<@Nullable Motion> motionChoices = new ArrayList<>();
    private final List<Button> anchorButtons = new ArrayList<>();
    private @Nullable Button particlesButton, figureButton, itemButton, removeButton, previousButton, nextButton, turnButton;
    private @Nullable Button appearButton, vanishButton, leaveButton, arriveButton, alwaysButton, addItemButton;
    private net.minecraft.client.gui.components.@Nullable EditBox itemBox;
    private static final int ITEMS_TOP_ROWS = 8; // the item list's row, counted in ROWs under LIST_TOP (+ a little)

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
        for (StudioMark mark : core.marks()) {
            BlockPos at = mark.offset().subtract(min);
            savedMarks.put(at, mark.shifted(BlockPos.ZERO.subtract(min)));
        }
        marks.putAll(savedMarks);
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
        anchorButtons.clear();
        int y = height - BAR + 5, x = MARGIN;
        addRenderableWidget(Button.builder(Component.literal(playing ? "Pause" : "Play"), b -> {
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

        // The tabs
        int px = panelX() + 4, wide = PANEL_WIDE - 8, half = (wide - 4) / 2;
        blocksTab = addRenderableWidget(Button.builder(Component.literal("Blocks"), b -> setTab(Tab.BLOCKS)).bounds(px, TOP + 4, half, 18).build());
        anchorsTab = addRenderableWidget(Button.builder(Component.literal("Anchors"), b -> setTab(Tab.ANCHORS))
                .bounds(px + half + 4, TOP + 4, half, 18).build());
        anchorsTab.setTooltip(Tooltip.create(Component.literal("Spots where particles rise or a figure stands")));

        // Blocks: a button per motion this machine allows, and one to keep blocks still
        int py = LIST_TOP;
        List<@Nullable Motion> choices = new ArrayList<>();
        choices.add(null);
        choices.addAll(rules.motions());
        int columns = choices.size() > 6 ? 2 : 1, column = 0;
        int buttonWide = columns == 2 ? half : wide;
        for (Motion motion : choices) {
            Button button = addRenderableWidget(Button.builder(Component.literal(label(motion)), b -> give(motion))
                    .bounds(px + column * (half + 4), py, buttonWide, 18).build());
            button.setTooltip(Tooltip.create(Component.literal(explain(motion))));
            motionButtons.add(button);
            motionChoices.add(motion);
            if (++column == columns) {
                column = 0;
                py += ROW;
            }
        }

        // Anchors: what plays at the spot
        py = LIST_TOP;
        int third = (wide - 4) / 3;
        particlesButton = addRenderableWidget(Button.builder(Component.literal("Particles"), b -> putMark(StudioMark.Kind.PARTICLES))
                .bounds(px, py, third, 18).build());
        figureButton = addRenderableWidget(Button.builder(Component.literal("Figure"), b -> putMark(StudioMark.Kind.FIGURE))
                .bounds(px + third + 2, py, third, 18).build());
        itemButton = addRenderableWidget(Button.builder(Component.literal("Item"), b -> putMark(StudioMark.Kind.ITEM))
                .bounds(px + 2 * (third + 2), py, wide - 2 * (third + 2), 18).build());
        itemButton.setTooltip(Tooltip.create(Component.literal("The item the machine works on floats here")));
        py += ROW;
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleMark(-1)).bounds(px, py, 18, 18).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleMark(1)).bounds(px + wide - 18, py, 18, 18).build());
        py += ROW;
        turnButton = addRenderableWidget(Button.builder(Component.literal("Turn"), b -> turnMark()).bounds(px, py, half, 18).build());
        removeButton = addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            if (spot != null) marks.remove(spot);
            refreshButtons();
        }).bounds(px + half + 4, py, half, 18).build());
        // When in the work: set from where the work bar stands
        py += ROW + 14;
        appearButton = addRenderableWidget(Button.builder(Component.literal("Appear here"), b -> retime(0)).bounds(px, py, half, 18).build());
        appearButton.setTooltip(Tooltip.create(Component.literal("It shows from this point of the work (the work bar)")));
        vanishButton = addRenderableWidget(Button.builder(Component.literal("Vanish here"), b -> retime(1)).bounds(px + half + 4, py, half, 18).build());
        vanishButton.setTooltip(Tooltip.create(Component.literal("It's gone from this point of the work")));
        py += ROW;
        leaveButton = addRenderableWidget(Button.builder(Component.literal("Leave here"), b -> retime(2)).bounds(px, py, half, 18).build());
        leaveButton.setTooltip(Tooltip.create(Component.literal("The item starts traveling at this point of the work")));
        arriveButton = addRenderableWidget(Button.builder(Component.literal("Arrive here"), b -> retime(3)).bounds(px + half + 4, py, half, 18).build());
        arriveButton.setTooltip(Tooltip.create(Component.literal("The item reaches its end at this point of the work")));
        py += ROW;
        alwaysButton = addRenderableWidget(Button.builder(Component.literal("Reset times"), b -> retime(-1)).bounds(px, py, wide, 18).build());
        alwaysButton.setTooltip(Tooltip.create(Component.literal("Shows all the time, travels through the whole work")));
        // The items an item anchor goes through: typed in, best match added with Enter or +
        py += ROW + 4;
        itemBox = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, px + 18, py, wide - 40, 18, Component.literal("Item")));
        itemBox.setHint(Component.literal("stone, gravel..."));
        itemBox.setMaxLength(64);
        addItemButton = addRenderableWidget(Button.builder(Component.literal("+"), b -> addTypedItem()).bounds(px + wide - 18, py, 18, 18).build());
        addItemButton.setTooltip(Tooltip.create(Component.literal("Add this item: the anchor shows its items one after another through the work")));
        anchorButtons.addAll(List.of(particlesButton, figureButton, itemButton, previousButton, nextButton, turnButton, removeButton,
                appearButton, vanishButton, leaveButton, arriveButton, alwaysButton, addItemButton));
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
            case STRIKE -> "Strikes out the way it faces. A piston pushes out its head";
            case SWING -> "Swings a quarter turn, like a door";
            case SHAKE -> "Trembles in place";
            case LIGHT -> "Lights up: furnaces, smokers, campfires, candles, lamps";
            case FILL -> "Fills up as the work goes on: cauldrons, composters, respawn anchors";
            case OPEN -> "Opens: barrels, trapdoors, doors, fence gates";
        };
    }

    private void setTab(Tab to) {
        tab = to;
        refreshButtons();
    }

    private boolean changed() {
        return !edits.equals(saved) || !marks.equals(savedMarks);
    }

    private void refreshButtons() {
        if (layerDown != null) layerDown.active = top > 0;
        if (layerUp != null) layerUp.active = top < size.getY() - 1;
        if (saveButton != null) saveButton.active = changed();
        if (blocksTab != null) blocksTab.active = tab != Tab.BLOCKS;
        if (anchorsTab != null) anchorsTab.active = tab != Tab.ANCHORS;
        boolean blocks = tab == Tab.BLOCKS;

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
            button.visible = blocks;
            boolean fits = !chosen.isEmpty();
            for (BlockPos cell : chosen) if (motion != null && !motion.fits(looks.get(cell))) fits = false;
            button.active = fits;
            boolean current = !chosen.isEmpty() && same && motion == shared;
            button.setMessage(Component.literal(current ? "> " + label(motion) + " <" : label(motion)));
        }

        StudioMark mark = spot == null ? null : marks.get(spot);
        for (Button button : anchorButtons) button.visible = !blocks;
        if (particlesButton != null) particlesButton.active = spot != null && !rules.particles().isEmpty()
                && (mark == null || mark.kind() != StudioMark.Kind.PARTICLES) && (mark != null || marks.size() < StudioMark.MAX);
        if (figureButton != null) figureButton.active = spot != null && !rules.figures().isEmpty()
                && (mark == null || mark.kind() != StudioMark.Kind.FIGURE) && (mark != null || marks.size() < StudioMark.MAX);
        if (itemButton != null) itemButton.active = spot != null
                && (mark == null || mark.kind() != StudioMark.Kind.ITEM) && (mark != null || marks.size() < StudioMark.MAX);
        if (previousButton != null) previousButton.active = mark != null;
        if (nextButton != null) nextButton.active = mark != null;
        if (removeButton != null) removeButton.active = mark != null;
        boolean travels = mark != null && mark.kind() == StudioMark.Kind.ITEM && mark.to().isPresent();
        for (Button button : new Button[] {appearButton, vanishButton, alwaysButton}) if (button != null) button.active = mark != null;
        for (Button button : new Button[] {leaveButton, arriveButton}) if (button != null) button.active = travels;
        boolean itemMark = mark != null && mark.kind() == StudioMark.Kind.ITEM;
        if (itemBox != null) itemBox.visible = !blocks && itemMark;
        if (addItemButton != null) {
            addItemButton.visible = !blocks && itemMark;
            addItemButton.active = itemMark && mark.items().size() < StudioMark.MAX_ITEMS;
        }
        if (turnButton != null) {
            boolean item = mark != null && mark.kind() == StudioMark.Kind.ITEM;
            turnButton.active = mark != null && mark.kind() != StudioMark.Kind.PARTICLES;
            turnButton.setMessage(Component.literal(!item ? "Turn" : pickingTo ? "Pick spot..." : mark.to().isPresent() ? "Stay" : "Travel"));
            turnButton.setTooltip(Tooltip.create(Component.literal(!item ? "Turn the figure a quarter"
                    : mark.to().isPresent() ? "Stop traveling: float here" : "Then click the spot it travels to as the work goes on")));
        }
    }

    // ---- Changes ----

    private void give(@Nullable Motion motion) {
        for (BlockPos cell : chosen) {
            if (motion == null) edits.remove(cell);
            else if (motion.fits(looks.get(cell))) edits.put(cell, motion);
        }
        stillDirty = true;
        refreshButtons();
    }

    private List<String> choicesFor(StudioMark.Kind kind) {
        List<String> usable = new ArrayList<>();
        switch (kind) {
            case FIGURE -> rules.figures().forEach(id -> usable.add(id.toString()));
            case ITEM -> usable.addAll(StudioMark.DISPLAYS);
            case PARTICLES -> {
                for (Identifier id : rules.particles()) if (StudioMark.particle(id) != null) usable.add(id.toString());
            }
        }
        return usable;
    }

    private void putMark(StudioMark.Kind kind) {
        if (spot == null) return;
        List<String> choices = choicesFor(kind);
        if (choices.isEmpty()) return;
        pickingTo = false;
        marks.put(spot, new StudioMark(spot, kind, choices.getFirst(), 0));
        refreshButtons();
    }

    private void cycleMark(int step) {
        StudioMark mark = spot == null ? null : marks.get(spot);
        if (mark == null) return;
        List<String> choices = choicesFor(mark.kind());
        if (choices.isEmpty()) return;
        int index = Math.floorMod(choices.indexOf(mark.what()) + step, choices.size());
        marks.put(spot, mark.withWhat(choices.get(index)));
        refreshButtons();
    }

    private void turnMark() {
        StudioMark mark = spot == null ? null : marks.get(spot);
        if (mark == null) return;
        if (mark.kind() == StudioMark.Kind.ITEM) {
            if (pickingTo || mark.to().isPresent()) {
                pickingTo = false;
                marks.put(spot, mark.withTo(java.util.Optional.empty()));
            } else {
                pickingTo = true;
            }
        } else {
            marks.put(spot, mark.withYaw((mark.yaw() + 90) % 360));
        }
        refreshButtons();
    }

    /** Sets one of the chosen anchor's times to where the work bar stands: 0 appear, 1 vanish, 2 leave, 3 arrive; -1 resets them. */
    private void retime(int which) {
        StudioMark mark = spot == null ? null : marks.get(spot);
        if (mark == null) return;
        int now = Math.round(progress(0) * 100);
        StudioMark.Times t = mark.times();
        StudioMark.Times changed = switch (which) {
            case 0 -> new StudioMark.Times(now, Math.max(now, t.showUntil()), t.leave(), t.arrive());
            case 1 -> new StudioMark.Times(Math.min(now, t.showFrom()), now, t.leave(), t.arrive());
            case 2 -> new StudioMark.Times(t.showFrom(), t.showUntil(), now, Math.max(now, t.arrive()));
            case 3 -> new StudioMark.Times(t.showFrom(), t.showUntil(), Math.min(now, t.leave()), now);
            default -> StudioMark.Times.ALWAYS;
        };
        marks.put(spot, mark.withTimes(changed));
        refreshButtons();
    }

    /** The item that best matches what is typed: the same id or name, else the shortest that starts with it, else contains it. */
    private static net.minecraft.world.item.@Nullable Item match(String typed) {
        String text = typed.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (text.isEmpty()) return null;
        Identifier exact = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text);
        if (exact != null && BuiltInRegistries.ITEM.containsKey(exact)) return BuiltInRegistries.ITEM.getValue(exact);
        net.minecraft.world.item.Item starts = null, contains = null;
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            String path = id.getPath();
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == net.minecraft.world.item.Items.AIR) continue;
            if (path.startsWith(text) && (starts == null || path.length() < BuiltInRegistries.ITEM.getKey(starts).getPath().length())) starts = item;
            else if (path.contains(text) && (contains == null || path.length() < BuiltInRegistries.ITEM.getKey(contains).getPath().length())) contains = item;
        }
        return starts != null ? starts : contains;
    }

    private void addTypedItem() {
        StudioMark mark = spot == null ? null : marks.get(spot);
        if (mark == null || mark.kind() != StudioMark.Kind.ITEM || itemBox == null) return;
        net.minecraft.world.item.Item item = match(itemBox.getValue());
        if (item == null || mark.items().size() >= StudioMark.MAX_ITEMS) return;
        List<Identifier> items = new ArrayList<>(mark.items());
        items.add(BuiltInRegistries.ITEM.getKey(item));
        marks.put(spot, mark.withItems(items));
        itemBox.setValue("");
        refreshButtons();
    }

    /** Where the item list's icons start, and how many go in a row. */
    private int itemsY() { return LIST_TOP + ITEMS_TOP_ROWS * ROW + 2; } // (under the item box)
    private static final int ICONS_PER_ROW = 7;

    private void layers(int change) {
        top = Mth.clamp(top + change, 0, size.getY() - 1);
        stillDirty = true;
        refreshButtons();
    }

    private void save() {
        Map<BlockPos, Motion> motions = new LinkedHashMap<>();
        edits.forEach((cell, motion) -> motions.put(cell.offset(min), motion));
        List<StudioMark> sent = new ArrayList<>();
        for (StudioMark mark : marks.values()) sent.add(mark.shifted(min));
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        connection.send(new ServerboundCustomPayloadPacket(new MachineStudio.Save(corePos, motions, sent)));
        saved.clear();
        saved.putAll(edits);
        savedMarks.clear();
        savedMarks.putAll(marks);
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
                (size.getX() + 2) * (size.getX() + 2) + size.getY() * size.getY() + (size.getZ() + 2) * (size.getZ() + 2));
        return fit * zoom;
    }

    private Vec3 center() {
        return new Vec3(size.getX() / 2.0, (top + 1) / 2.0, size.getZ() / 2.0);
    }

    /** What the mouse points at: a block shown, or (on the Anchors tab) an anchor's own spot. */
    private StudioView.@Nullable Hit under(double x, double y) {
        if (!inView(x, y)) return null;
        List<BlockPos> shown = new ArrayList<>();
        for (BlockPos cell : looks.keySet()) if (cell.getY() <= top) shown.add(cell);
        if (tab == Tab.ANCHORS) {
            for (BlockPos cell : marks.keySet()) if (cell.getY() <= top && !looks.containsKey(cell)) shown.add(cell);
        }
        return StudioView.pick(x - (viewX0() + viewX1()) / 2.0, y - (viewY0() + viewY1()) / 2.0, scale(), yaw, pitch, center(), shown);
    }

    /** The item list's icon under the mouse (its index), or -1. */
    private int iconUnder(double x, double y) {
        StudioMark mark = tab == Tab.ANCHORS && spot != null ? marks.get(spot) : null;
        if (mark == null || mark.kind() != StudioMark.Kind.ITEM) return -1;
        int left = panelX() + 5, top = itemsY();
        if (x < left || y < top) return -1;
        int column = (int) ((x - left) / 18), row = (int) ((y - top) / 18);
        if (column >= ICONS_PER_ROW) return -1;
        int index = row * ICONS_PER_ROW + column;
        return index < mark.items().size() ? index : -1;
    }

    // The work bar along the bottom of the view: the pretend operation's progress, held to scrub through it
    private boolean onBar(double x, double y) {
        return x >= viewX0() + 4 && x < viewX1() - 4 && y >= viewY1() - 9 && y < viewY1();
    }

    private void scrub(double x) {
        float at = Mth.clamp((float) ((x - viewX0() - 4) / (viewX1() - viewX0() - 8)), 0, 0.999F);
        clock = (float) Math.floor(clock / OPERATION) * OPERATION + at * OPERATION;
    }

    /** How far the pretend operation has got (0 to 1): items travel and cauldrons fill with it. */
    private float progress(float partialTicks) {
        return ((clock + (playing ? partialTicks : 0)) % OPERATION) / OPERATION;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int icon = iconUnder(event.x(), event.y());
        if (icon >= 0 && event.button() == 0) {
            StudioMark mark = marks.get(spot);
            List<Identifier> items = new ArrayList<>(mark.items());
            items.remove(icon);
            marks.put(spot, mark.withItems(items));
            refreshButtons();
            return true;
        }
        if (event.button() == 0 && onBar(event.x(), event.y())) {
            scrubbing = true;
            if (playing) {
                playing = false;
                rebuildWidgets();
            }
            scrub(event.x());
            return true;
        }
        if (event.button() == 0 && inView(event.x(), event.y())) {
            setFocused(null); // (the item box lets go of the keys)
            dragging = true;
            dragged = 0;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (scrubbing) {
            scrub(event.x());
            return true;
        }
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
        if (scrubbing && event.button() == 0) {
            scrubbing = false;
            return true;
        }
        if (dragging && event.button() == 0) {
            dragging = false;
            if (dragged < 3) {
                StudioView.Hit hit = under(event.x(), event.y());
                if (tab == Tab.BLOCKS) choose(hit == null ? null : hit.cell(), event.hasShiftDown(), event.hasControlDown());
                else pickSpot(hit);
            }
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

    /** An anchor's own spot picks it; a block's face picks the spot in front of that face (in or right around the build). */
    private void pickSpot(StudioView.@Nullable Hit hit) {
        if (pickingTo) {
            pickingTo = false;
            StudioMark mark = spot == null ? null : marks.get(spot);
            BlockPos to = hit == null ? null : spotAt(hit);
            if (mark != null && to != null && !to.equals(spot)) marks.put(spot, mark.withTo(java.util.Optional.of(to)));
            refreshButtons();
            return;
        }
        if (hit == null) {
            spot = null;
        } else {
            spot = spotAt(hit);
        }
        refreshButtons();
    }

    /** The spot a click picks: an anchor's own, or the one in front of a block's face (in or right around the build). */
    private @Nullable BlockPos spotAt(StudioView.Hit hit) {
        if (marks.containsKey(hit.cell()) && !looks.containsKey(hit.cell())) return hit.cell();
        BlockPos front = hit.cell().relative(hit.face());
        if (front.getY() > top) return withinReach(front) ? front : null; // a hidden layer: the spot may be inside a block
        boolean near = front.getX() >= -1 && front.getY() >= -1 && front.getZ() >= -1
                && front.getX() <= size.getX() && front.getY() <= size.getY() && front.getZ() <= size.getZ();
        return near && !looks.containsKey(front) ? front : null;
    }

    private boolean withinReach(BlockPos cell) {
        return cell.getX() >= -1 && cell.getY() >= -1 && cell.getZ() >= -1
                && cell.getX() <= size.getX() && cell.getY() <= size.getY() && cell.getZ() <= size.getZ();
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
        if (itemBox != null && itemBox.isFocused() && itemBox.visible) {
            if (event.key() == 257 || event.key() == 335) { // enter: add the item typed
                addTypedItem();
                return true;
            }
            if (!event.isEscape()) return itemBox.keyPressed(event) || true;
        }
        if (event.key() == 32) { // space: play / pause
            playing = !playing;
            stillDirty = true;
            rebuildWidgets();
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
        graphics.submitPictureInPictureRenderState(new StudioRenderState(stillMesh(), pieces(a), figures(a), items(a), center(), yaw, pitch,
                x0, y0, x1, y1, scale(), graphics.peekScissorStack()));

        // Header
        String name = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getBlockState(corePos).getBlock().getName().getString() : "";
        graphics.text(font, "Machine Studio", MARGIN, 7, ACCENT, false);
        graphics.text(font, name, MARGIN + font.width("Machine Studio") + 8, 7, FAINT, false);
        if (changed()) {
            String unsaved = "Unsaved changes";
            graphics.text(font, unsaved, viewX1() - font.width(unsaved), 7, WARN, false);
        }
        // Under the view: the layers shown, and what the mouse is on
        graphics.text(font, top == size.getY() - 1 ? "All layers" : "Layers 1-" + (top + 1), MARGIN + 74, height - BAR + 11, TEXT, false);
        if (hovered != null) {
            String says = describe(hovered.cell());
            if (says != null) graphics.text(font, says, x0 + 4, y1 - 22, TEXT, true);
        }
        int barLeft = x0 + 4, barRight = x1 - 4, filled = barLeft + Math.round((barRight - barLeft) * progress(a));
        graphics.fill(barLeft, y1 - 6, barRight, y1 - 3, EDGE);
        graphics.fill(barLeft, y1 - 6, filled, y1 - 3, ACCENT);
        graphics.fill(filled - 1, y1 - 8, filled + 1, y1 - 1, TEXT);
        StudioMark picked = tab == Tab.ANCHORS && spot != null ? marks.get(spot) : null;
        if (picked != null) { // the chosen anchor's times over the bar: when it shows (green), when it travels (blue)
            int span = barRight - barLeft;
            graphics.fill(barLeft + span * picked.times().showFrom() / 100, y1 - 9, barLeft + span * picked.times().showUntil() / 100, y1 - 7, GOOD);
            if (picked.to().isPresent()) {
                graphics.fill(barLeft + span * picked.times().leave() / 100, y1 - 3, barLeft + span * picked.times().arrive() / 100, y1 - 1, 0xFF66B3FF);
            }
        }
        String work = "Work " + Math.round(progress(a) * 100) + "%";
        graphics.text(font, work, barRight - font.width(work), y1 - 18, FAINT, false);
        if (pickingTo) graphics.text(font, "Click the spot the item travels to", x0 + 4, y0 + 4, WARN, true);

        panel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private @Nullable String describe(BlockPos cell) {
        StudioMark mark = marks.get(cell);
        BlockState look = looks.get(cell);
        if (look == null) return mark == null ? null : "Anchor: " + markName(mark);
        String says = look.getBlock().getName().getString();
        Motion motion = edits.get(cell);
        if (motion != null) says += ": " + motion.getSerializedName();
        else if (!movable.contains(cell)) says += cell.offset(min).equals(BlockPos.ZERO) ? " (the core)" : " (stays as it is)";
        return says;
    }

    private static String markName(StudioMark mark) {
        return mark.kind().getSerializedName() + ", " + shortName(mark);
    }

    private static String shortName(StudioMark mark) {
        Identifier id = mark.kind() == StudioMark.Kind.ITEM ? null : mark.id();
        String name = id == null ? mark.what() : id.getPath();
        if (mark.kind() == StudioMark.Kind.ITEM && !mark.items().isEmpty()) {
            name = mark.items().size() == 1 ? mark.items().getFirst().getPath() : mark.items().size() + " items in turn";
        }
        return name.replace('_', ' ') + (mark.to().isPresent() ? ", travels" : "");
    }

    private void panel(GuiGraphicsExtractor graphics) {
        int px = panelX(), py = TOP;
        graphics.fill(px - 1, py - 1, px + PANEL_WIDE + 1, viewY1() + 1, EDGE);
        graphics.fill(px, py, px + PANEL_WIDE, viewY1(), VIEW_BACK);
        int tx = px + 5, ty = TOP + 28;
        List<String> help;
        int y;
        if (tab == Tab.BLOCKS) {
            if (chosen.isEmpty()) {
                graphics.text(font, "Click a block", tx, ty, ACCENT, false);
                graphics.text(font, movable.size() + " blocks can move", tx, ty + 12, FAINT, false);
            } else if (chosen.size() == 1) {
                BlockPos cell = chosen.iterator().next();
                graphics.text(font, clip(looks.get(cell).getBlock().getName().getString()), tx, ty, ACCENT, false);
                BlockPos offset = cell.offset(min);
                graphics.text(font, "At " + offset.getX() + " " + offset.getY() + " " + offset.getZ() + " from the core", tx, ty + 12, FAINT, false);
            } else {
                graphics.text(font, chosen.size() + " blocks chosen", tx, ty, ACCENT, false);
                graphics.text(font, "A motion goes to all", tx, ty + 12, FAINT, false);
            }
            int rows = (motionButtons.size() + (motionButtons.size() > 6 ? 1 : 0)) / (motionButtons.size() > 6 ? 2 : 1);
            y = LIST_TOP + rows * ROW + 4;
            String when = switch (rules.motionWhen()) {
                case IDLE -> "Plays while it rests";
                case WORKING -> "Plays while it works";
                default -> "Plays all the time";
            };
            graphics.text(font, when, tx, y, playing ? GOOD : FAINT, false);
            y += 14;
            help = List.of("Drag: turn, wheel: zoom", "Click: choose a block", "Shift+click: add one", "Ctrl+click: all alike",
                    "Greyed: it can't do that", "Space: play / pause");
        } else {
            StudioMark mark = spot == null ? null : marks.get(spot);
            if (spot == null) {
                graphics.text(font, "Click a block's side", tx, ty, ACCENT, false);
                graphics.text(font, marks.size() + " anchors", tx, ty + 12, FAINT, false);
            } else {
                BlockPos offset = spot.offset(min);
                graphics.text(font, mark == null ? "Empty spot" : clip(capital(markName(mark))), tx, ty, ACCENT, false);
                graphics.text(font, "At " + offset.getX() + " " + offset.getY() + " " + offset.getZ() + " from the core", tx, ty + 12, FAINT, false);
            }
            if (mark != null) {
                String shown = clip(shortName(mark));
                int middle = px + PANEL_WIDE / 2;
                graphics.text(font, shown, middle - font.width(shown) / 2, LIST_TOP + ROW + 5, TEXT, false);
            }
            y = LIST_TOP + 3 * ROW + 3;
            String when = mark == null ? "When in the work:" : mark.times().shownAlways() ? "Shows all the time"
                    : "Shows " + mark.times().showFrom() + "% to " + mark.times().showUntil() + "%";
            graphics.text(font, when, tx, y, mark == null ? FAINT : TEXT, false);
            y = LIST_TOP + 6 * ROW + 14;
            if (mark != null && mark.kind() == StudioMark.Kind.ITEM) {
                if (itemBox != null) { // the best match for what is typed, by the box
                    net.minecraft.world.item.Item match = match(itemBox.getValue());
                    if (match != null) graphics.item(new net.minecraft.world.item.ItemStack(match), px + 4, itemBox.getY() + 1);
                }
                int top = itemsY();
                for (int i = 0; i < mark.items().size(); i++) {
                    int ix = tx + (i % ICONS_PER_ROW) * 18, iy = top + (i / ICONS_PER_ROW) * 18;
                    graphics.fill(ix - 1, iy - 1, ix + 17, iy + 17, EDGE);
                    BuiltInRegistries.ITEM.getOptional(mark.items().get(i))
                            .ifPresent(item -> graphics.item(new net.minecraft.world.item.ItemStack(item), ix, iy));
                    if (i < mark.items().size() - 1 && (i + 1) % ICONS_PER_ROW != 0) graphics.text(font, ">", ix + 17, iy + 4, FAINT, false);
                }
                y = top + Math.max(1, (mark.items().size() + ICONS_PER_ROW - 1) / ICONS_PER_ROW) * 18 + 4;
                if (mark.items().isEmpty()) graphics.text(font, "No items: shows its display", tx, top + 4, FAINT, false);
                if (mark.to().isPresent()) {
                    graphics.text(font, "Travels " + mark.times().leave() + "% to " + mark.times().arrive() + "%", tx, y, TEXT, false);
                    y += 12;
                }
                if (!mark.items().isEmpty()) {
                    graphics.text(font, "Click an item: remove it", tx, y, FAINT, false);
                    y += 12;
                }
            }
            help = List.of("Click a side of a block:", "the spot in front of it", "Then particles, a figure", "or an item",
                    "Click an anchor: change it", "Drag the work bar, then", "Appear / Vanish here", "Hide layers to reach inside");
        }
        for (String line : help) {
            if (y > viewY1() - 10) break;
            graphics.text(font, line, tx, y, FAINT, false);
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

    private boolean drawnApart(BlockPos cell) {
        Motion motion = edits.get(cell);
        return motion != null && motion.moves();
    }

    /** What a cell looks like now in the preview: its own look, or the step a change of look is at. */
    private BlockState shownLook(BlockPos cell, BlockState look) {
        Motion motion = edits.get(cell);
        if (motion == null || motion.moves()) return look;
        List<Map<String, String>> steps = motion.steps(look);
        if (steps.isEmpty()) return look;
        int step = Math.min(steps.size() - 1, (int) (progress(0) * steps.size())); // through the work, like on the machine
        return BlockLook.apply(look, steps.get(step));
    }

    /** The blocks that stand still now, as one mesh (made again when the layers, the motions, play or a step change). */
    private BoxMesh stillMesh() {
        int step = (int) (progress(0) * 24); // (fine enough for the steps of any change of look)
        if (still == null || stillDirty || still.isStale() || step != stillStep) {
            ClientLevel level = Minecraft.getInstance().level;
            still = BoxMesh.build(level, corePos, BlockBox.of(size.getX(), size.getY(), size.getZ(), false, (x, y, z) -> {
                if (y > top) return AIR;
                BlockPos cell = new BlockPos(x, y, z);
                BlockState look = looks.get(cell);
                if (look == null || drawnApart(cell)) return AIR; // a moving block is drawn on its own
                return shownLook(cell, look);
            }), 400_000);
            stillDirty = false;
            stillStep = step;
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

    /** The moving blocks where they are now, and the marks over the chosen blocks, the anchors and what is pointed at. */
    private List<StudioRenderState.Piece> pieces(float partialTicks) {
        List<StudioRenderState.Piece> pieces = new ArrayList<>();
        float time = clock + (playing ? partialTicks : 0);
        { // (paused or scrubbed, the moves stand where the clock left them)
            for (Map.Entry<BlockPos, Motion> entry : edits.entrySet()) {
                BlockPos cell = entry.getKey();
                BlockState look = looks.get(cell);
                Motion motion = entry.getValue();
                if (look == null || cell.getY() > top || !motion.moves()) continue;
                if (motion == Motion.STRIKE && Motion.isPiston(look)) {
                    piston(pieces, cell, look, time);
                    continue;
                }
                List<Keyframe> frames = motion.keyframes();
                float loop = Math.max(1, Keyframe.duration(frames));
                Keyframe pose = Keyframe.at(frames, time % loop);
                Direction d = direction(motion, cell, look);
                Vec3 at = Vec3.atLowerCornerOf(cell).add(d.getStepX() * pose.move(), d.getStepY() * pose.move(), d.getStepZ() * pose.move())
                        .add(pose.offset());
                pieces.add(new StudioRenderState.Piece(pieceMesh(look), at, pose.rotation(), pose.scale()));
            }
        }
        if (tab == Tab.BLOCKS) {
            for (BlockPos cell : chosen) {
                if (cell.getY() <= top) pieces.add(mark(cell, CHOSEN_MARK, 1.08F));
            }
            if (hovered != null && !chosen.contains(hovered.cell()) && movable.contains(hovered.cell())) {
                pieces.add(mark(hovered.cell(), HOVER_MARK, 1.05F));
            }
        }
        // Anchors: particles as a small glowing cube (bobbing while playing), a figure's spot as a thin plate
        float progress = progress(partialTicks);
        for (StudioMark mark : marks.values()) {
            if (mark.offset().getY() > top || !mark.times().shows(progress)) continue;
            if (mark.kind() == StudioMark.Kind.PARTICLES) {
                float bob = playing ? (float) Math.sin(time / 6.0) * 0.08F : 0;
                pieces.add(new StudioRenderState.Piece(pieceMesh(PARTICLE_MARK), Vec3.atLowerCornerOf(mark.offset()).add(0, bob, 0),
                        new Vec3(0, time * 4, 0), 0.3F));
            }
        }
        for (StudioMark mark : marks.values()) {
            if (mark.kind() != StudioMark.Kind.ITEM || mark.offset().getY() > top) continue;
            boolean shown = mark.times().shows(progress);
            if (!shown && !(tab == Tab.ANCHORS && mark.offset().equals(spot))) continue; // (its own spot still shows it, faintly)
            Vec3 from = Vec3.atLowerCornerOf(mark.offset());
            if (mark.to().isPresent()) {
                Vec3 to = Vec3.atLowerCornerOf(mark.to().get());
                int dots = (int) Math.ceil(from.distanceTo(to) * 2);
                for (int i = 1; i < dots; i++) {
                    pieces.add(new StudioRenderState.Piece(pieceMesh(PATH_MARK), from.lerp(to, i / (double) dots), Vec3.ZERO, 0.1F));
                }
                if (shown && mark.items().isEmpty()) {
                    pieces.add(new StudioRenderState.Piece(pieceMesh(ITEM_MARK), from.lerp(to, mark.times().travel(progress)),
                            new Vec3(0, time * 3, 0), 0.3F));
                }
            } else {
                float bob = playing ? (float) Math.sin(time / 10.0) * 0.05F : 0;
                if (shown && mark.items().isEmpty()) {
                    pieces.add(new StudioRenderState.Piece(pieceMesh(ITEM_MARK), from.add(0, bob, 0), new Vec3(0, time * 3, 0), 0.3F));
                }
            }
        }
        if (tab == Tab.ANCHORS) {
            if (spot != null) pieces.add(new StudioRenderState.Piece(pieceMesh(CHOSEN_MARK), Vec3.atLowerCornerOf(spot), Vec3.ZERO, 0.9F));
            BlockPos front = hoveredSpot();
            if (front != null && !front.equals(spot)) {
                pieces.add(new StudioRenderState.Piece(pieceMesh(SPOT_MARK), Vec3.atLowerCornerOf(front), Vec3.ZERO, 0.5F));
            }
        }
        return pieces;
    }

    /** The items of item anchors that name them, as they show now (an anchor showing a display gets a stand-in cube). */
    private List<StudioRenderState.ItemPiece> items(float partialTicks) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<StudioRenderState.ItemPiece> items = new ArrayList<>();
        float progress = progress(partialTicks), time = clock + (playing ? partialTicks : 0);
        for (StudioMark mark : marks.values()) {
            if (mark.kind() != StudioMark.Kind.ITEM || mark.items().isEmpty() || mark.offset().getY() > top || !mark.times().shows(progress)) continue;
            // Which item: the one this point of its showing time has reached, like on the machine
            float from = mark.times().showFrom() / 100.0F, until = mark.times().showUntil() / 100.0F;
            float local = until - from < 1e-4F ? 1 : (progress - from) / (until - from);
            int index = Mth.clamp((int) (local * mark.items().size()), 0, mark.items().size() - 1);
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.getOptional(mark.items().get(index)).orElse(null);
            if (item == null) continue;
            net.minecraft.client.renderer.item.ItemStackRenderState state = new net.minecraft.client.renderer.item.ItemStackRenderState();
            Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, new net.minecraft.world.item.ItemStack(item),
                    net.minecraft.world.item.ItemDisplayContext.GROUND, level, null, index);
            Vec3 at = Vec3.atCenterOf(mark.offset());
            if (mark.to().isPresent()) at = at.lerp(Vec3.atCenterOf(mark.to().get()), mark.times().travel(progress));
            else at = at.add(0, playing ? Math.sin(time / 10.0) * 0.05 : 0, 0);
            items.add(new StudioRenderState.ItemPiece(state, at.add(0, -0.25, 0), mark.to().isPresent() ? 0 : time * 4, 1.0F));
        }
        return items;
    }

    /** The spot a click would pick now (Anchors tab), to show it. */
    private @Nullable BlockPos hoveredSpot() {
        return hovered == null ? null : spotAt(hovered);
    }

    /** A piston's strike: the base shows extended while its head shoots out along its facing and back. */
    private void piston(List<StudioRenderState.Piece> pieces, BlockPos cell, BlockState look, float time) {
        List<Keyframe> frames = Motion.pistonKeyframes();
        float elapsed = time % Motion.pistonEvery();
        float duration = Keyframe.duration(frames);
        if (elapsed > duration) {
            pieces.add(new StudioRenderState.Piece(pieceMesh(look), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
            return;
        }
        Direction facing = direction(Motion.STRIKE, cell, look);
        pieces.add(new StudioRenderState.Piece(pieceMesh(BlockLook.apply(look, Map.of("extended", "true"))), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
        Keyframe pose = Keyframe.at(frames, elapsed);
        Map<String, String> state = new HashMap<>(Keyframe.stateAt(frames, elapsed));
        state.put("type", look.is(Blocks.STICKY_PISTON) ? "sticky" : "normal");
        state.put("facing", facing.getSerializedName());
        BlockState head = BlockLook.apply(Blocks.PISTON_HEAD.defaultBlockState(), state);
        Vec3 at = Vec3.atLowerCornerOf(cell).add(facing.getStepX() * pose.move(), facing.getStepY() * pose.move(), facing.getStepZ() * pose.move());
        pieces.add(new StudioRenderState.Piece(pieceMesh(head), at, Vec3.ZERO, 1));
    }

    private StudioRenderState.Piece mark(BlockPos cell, BlockState glass, float size) {
        return new StudioRenderState.Piece(pieceMesh(glass), Vec3.atLowerCornerOf(cell), Vec3.ZERO, size);
    }

    /** The figures standing at anchors, as entities made only to be drawn (never added to the world). */
    private List<Puppets.Prepared> figures(float partialTicks) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().player == null) return List.of();
        List<Puppets.Prepared> figures = new ArrayList<>();
        Vec3 lit = Minecraft.getInstance().player.position();
        for (StudioMark mark : marks.values()) {
            if (mark.kind() != StudioMark.Kind.FIGURE || mark.offset().getY() > top || !mark.times().shows(progress(partialTicks))) continue;
            Identifier figureId = mark.id();
            if (figureId == null) continue;
            Entity entity = standIns.computeIfAbsent(figureId, id -> create(level, id));
            if (entity == null) continue;
            try {
                entity.setPos(lit.x, lit.y, lit.z);
                entity.xo = entity.xOld = lit.x;
                entity.yo = entity.yOld = lit.y;
                entity.zo = entity.zOld = lit.z;
                entity.setYRot(mark.yaw());
                entity.yRotO = mark.yaw();
                entity.tickCount = (int) clock;
                if (entity instanceof LivingEntity living) {
                    living.yBodyRot = living.yBodyRotO = mark.yaw();
                    living.yHeadRot = living.yHeadRotO = mark.yaw();
                }
                EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(entity, partialTicks);
                state.shadowPieces.clear();
                state.shadowRadius = 0;
                state.nameTag = null;
                figures.add(new Puppets.Prepared(state, mark.offset().getX() + 0.5F, mark.offset().getY(), mark.offset().getZ() + 0.5F));
            } catch (Throwable e) { // a mob that can't be shown must never take the game down
                standIns.put(figureId, null);
                AlchyX.LOGGER.warn("AlchyX: can't show {} in the Machine Studio: {}", figureId, e.toString());
            }
        }
        return figures;
    }

    private static @Nullable Entity create(ClientLevel level, Identifier id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) return null;
        try {
            return type.create(level, EntitySpawnReason.LOAD);
        } catch (Throwable e) {
            return null;
        }
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

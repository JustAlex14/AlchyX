package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.AlchyX;
import com.lealex.alchyx.animation.BlockLook;
import com.lealex.alchyx.animation.BlockPose;
import com.lealex.alchyx.animation.Effect;
import com.lealex.alchyx.animation.Keyframe;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.animation.MotionSetup;
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
    private static final int PANEL_WIDE = 150, MARGIN = 8, TOP = 22, BAR = 30, ROW = 20, LIST_TOP = TOP + 64;
    private static final int BACK = 0xE0101018, VIEW_BACK = 0xFF1A1A26, EDGE = 0xFF3C3550, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0,
            FAINT = 0xFF9A93AA, WARN = 0xFFFFB866, GOOD = 0xFF8BE39B;
    private static final float START_YAW = (float) Math.toRadians(-35), START_PITCH = (float) Math.toRadians(-28);
    private static final BlockState CHOSEN_MARK = Blocks.MAGENTA_STAINED_GLASS.defaultBlockState();
    private static final BlockState HOVER_MARK = Blocks.WHITE_STAINED_GLASS.defaultBlockState();
    private static final BlockState PARTICLE_MARK = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
    private static final BlockState SPOT_MARK = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState SOUND_MARK = Blocks.NOTE_BLOCK.defaultBlockState();
    private static final BlockState ITEM_MARK = Blocks.ORANGE_STAINED_GLASS.defaultBlockState();
    private static final BlockState PATH_MARK = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
    private static final int OPERATION = 100; // the preview's pretend work: one operation this many ticks long

    private enum Tab { BUILD, BLOCKS, SHAPE, ANCHORS }

    private final BlockPos corePos;
    private final @Nullable Object shape;                    // the machine's shape when the studio opened (it closes if that changes)
    private final FreeBuild rules;
    private final boolean fixed;                             // a machine with a fixed shape (its own animation files play too)
    private boolean hideFile, savedHideFile;                 // its own animation turned off, so only the studio's plays
    /** A block move of the machine's own animation file, on one of its cells (box coordinates): shown in the preview. */
    private record FileMove(BlockPos cell, com.lealex.alchyx.client.animation.AnimationEngine.PreviewMove move) {}
    private final List<FileMove> fileMoves = new ArrayList<>();
    private final Set<BlockPos> fileMoved = new java.util.HashSet<>(); // cells a file's move swaps or hides: drawn apart
    private BlockPos min, size;                              // the build's box: lowest offset from the core, and its size (grows as blocks are put in)
    private final @Nullable FreeBuild building;              // the rules for building in the studio (Build tab), or null: it can't be rebuilt
    private final Map<BlockPos, BlockState> builtFrom = new HashMap<>(); // the blocks as the machine stands, by offset from the core
    private BlockState brush = Blocks.STONE.defaultBlockState(); // what the Build tab puts in
    private boolean rebuilding;                              // a build was saved: the studio opens again on the new shape
    private @Nullable String buildProblem;                   // why the last block couldn't go in
    private final Map<BlockPos, BlockState> looks = new HashMap<>();     // what shows at each cell (box coordinates)
    private final Set<BlockPos> movable = new LinkedHashSet<>();        // cells that can be given a motion
    private final Map<BlockPos, Motion> edits = new LinkedHashMap<>();  // the motions as changed here (box coordinates)
    private final Map<BlockPos, Motion> saved = new LinkedHashMap<>();  // as the machine has them
    private final Map<BlockPos, MotionSetup> setups = new LinkedHashMap<>();      // when each motion plays, a change's state (box coordinates)
    private final Map<BlockPos, MotionSetup> savedSetups = new LinkedHashMap<>();
    private final Map<BlockPos, net.minecraft.nbt.CompoundTag> dataEdits = new LinkedHashMap<>(); // block data changed here (box coordinates)
    private final Map<BlockPos, StudioMark> marks = new LinkedHashMap<>();     // the anchors as changed here (box coordinates)
    private final Map<BlockPos, StudioMark> savedMarks = new LinkedHashMap<>();
    private final Map<BlockPos, BlockPose> poses = new LinkedHashMap<>();      // how the Shape tab reshaped blocks (box coordinates)
    private final Map<BlockPos, BlockPose> savedPoses = new LinkedHashMap<>();
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

    private @Nullable Button saveButton, layerDown, layerUp, buildTab, blocksTab, shapeTab, anchorsTab, brushButton, undoBuildButton;
    private final List<Button> shapeButtons = new ArrayList<>(); // Shape tab
    private @Nullable Button hideButton;
    private double nudge = 0.125;          // how far the Shape tab's arrows move a block (blocks)
    private final List<Button> motionButtons = new ArrayList<>();
    private final List<@Nullable Motion> motionChoices = new ArrayList<>();
    private final List<Button> anchorButtons = new ArrayList<>();
    private @Nullable Button particlesButton, figureButton, itemButton, soundButton, removeButton, previousButton, nextButton, turnButton;
    private @Nullable Button appearButton, vanishButton, leaveButton, arriveButton, alwaysButton, pickItemsButton;
    private final List<Button> blockButtons = new ArrayList<>(); // Blocks tab: when the chosen blocks' motion plays, a change's state
    private @Nullable Button startButton, stopButton, wholeButton, stateButton, dataButton, effectButton;
    private int motionRows;                // rows of motion buttons (Blocks tab)
    private static final int ICONS_PER_ROW = 7;

    public StudioScreen(MultiblockCoreBlockEntity core) {
        super(Component.literal("Machine Studio"));
        this.corePos = core.getBlockPos().immutable();
        this.shape = core.recordedShape() != null ? core.recordedShape() : core.pattern();
        this.rules = java.util.Objects.requireNonNull(core.studioRules());
        this.fixed = core.recordedShape() == null;
        this.hideFile = this.savedHideFile = core.hidesFileAnimation();
        BlockPos[] box = core.studioBox();
        this.min = box[0];
        BlockPos max = box[1];
        this.size = new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        this.top = size.getY() - 1;
        this.building = core.isFormed() ? core.freeBuild() : null;
        if (building != null) this.tab = Tab.BUILD; // a machine players may build: the studio opens on its build
        ClientLevel level = Minecraft.getInstance().level;
        for (MultiblockPattern.LookCell cell : core.studioCells()) {
            BlockPos at = cell.offset().subtract(min);
            // As it stands in the world (the core's own facing, every state the record didn't keep), else as recorded
            BlockState real = level == null ? null : com.lealex.alchyx.block.ChamberShellBlock.lookAt(level, corePos.offset(cell.offset()));
            looks.put(at, real == null || real.isAir() || ShellParts.isPart(real) ? cell.state() : real);
            if (cell.offset().equals(BlockPos.ZERO) || level == null) continue;
            if (ShellParts.isPart(level.getBlockState(corePos.offset(cell.offset())))) movable.add(at);
        }
        com.lealex.alchyx.animation.ResolvedAnimation file = com.lealex.alchyx.animation.ClientAnimations.get(core.patternId());
        if (file != null && level != null) {
            for (var move : com.lealex.alchyx.client.animation.AnimationEngine.previewMoves(level, core, file)) {
                BlockPos cell = move.pos().subtract(corePos).subtract(min);
                if (!looks.containsKey(cell)) continue;
                fileMoves.add(new FileMove(cell, move));
                if (move.effect().hide() || !move.effect().swap().isEmpty()) fileMoved.add(cell);
            }
        }
        core.motions().forEach((offset, motion) -> saved.put(offset.subtract(min), motion));
        edits.putAll(saved);
        core.setups().forEach((offset, setup) -> savedSetups.put(offset.subtract(min), setup));
        setups.putAll(savedSetups);
        for (StudioMark mark : core.marks()) {
            BlockPos at = mark.offset().subtract(min);
            savedMarks.put(at, mark.shifted(BlockPos.ZERO.subtract(min)));
        }
        marks.putAll(savedMarks);
        core.poses().forEach((offset, pose) -> savedPoses.put(offset.subtract(min), pose));
        poses.putAll(savedPoses);
        looks.forEach((cell, look) -> builtFrom.put(cell.offset(min), look));
    }

    /** The studio for that core, on the Build tab (opened again after a build was saved). */
    private StudioScreen(MultiblockCoreBlockEntity core, Tab tab, float yaw, float pitch, float zoom) {
        this(core);
        this.tab = tab;
        this.yaw = yaw;
        this.pitch = pitch;
        this.zoom = zoom;
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
        blockButtons.clear();
        shapeButtons.clear();
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
        if (fixed) { // a machine with a fixed shape: its own animation on or off, and the work as a datapack file
            x += 68;
            Button own = addRenderableWidget(Button.builder(Component.literal(hideFile ? "Own anim: off" : "Own anim: on"), b -> {
                hideFile = !hideFile;
                stillDirty = true;
                b.setMessage(Component.literal(hideFile ? "Own anim: off" : "Own anim: on"));
                refreshButtons();
            }).bounds(x, y, 80, 20).build());
            own.setTooltip(Tooltip.create(Component.literal("The machine's own animation (its mod's files): on, playing with what you make here, or off, so only yours plays (Save to keep it)")));
            x += 84;
            Button export = addRenderableWidget(Button.builder(Component.literal("Export"), b -> openFiles(true)).bounds(x, y, 50, 20).build());
            export.setTooltip(Tooltip.create(Component.literal("Write what you made here as an animation file for a datapack: every machine of this kind plays it")));
            x += 54;
            Button bring = addRenderableWidget(Button.builder(Component.literal("Import"), b -> openFiles(false)).bounds(x, y, 50, 20).build());
            bring.setTooltip(Tooltip.create(Component.literal("Bring back a file Export made (alchyx_exports), to change it here. Save to keep it")));
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width - MARGIN - 50, y, 50, 20).build());

        // The tabs
        int px = panelX() + 4, wide = PANEL_WIDE - 8, half = (wide - 4) / 2, tabWide = (wide - 6) / 4;
        buildTab = addRenderableWidget(Button.builder(Component.literal("Build"), b -> setTab(Tab.BUILD)).bounds(px, TOP + 4, tabWide, 18).build());
        buildTab.setTooltip(Tooltip.create(Component.literal(building != null
                ? "Put blocks in and take them out, like in the world: they all become the machine's parts"
                : "This machine has a fixed shape: complete it once, then it can be built your own way")));
        blocksTab = addRenderableWidget(Button.builder(Component.literal("Blocks"), b -> setTab(Tab.BLOCKS))
                .bounds(px + tabWide + 2, TOP + 4, tabWide, 18).build());
        blocksTab.setTooltip(Tooltip.create(Component.literal("How blocks move as the machine works")));
        shapeTab = addRenderableWidget(Button.builder(Component.literal("Shape"), b -> setTab(Tab.SHAPE))
                .bounds(px + 2 * (tabWide + 2), TOP + 4, tabWide, 18).build());
        shapeTab.setTooltip(Tooltip.create(Component.literal("Hide, move, turn and size blocks (for the look: the blocks stay where they are), or shrink the whole machine")));
        anchorsTab = addRenderableWidget(Button.builder(Component.literal("Anchors"), b -> setTab(Tab.ANCHORS))
                .bounds(px + 3 * (tabWide + 2), TOP + 4, wide - 3 * (tabWide + 2), 18).build());

        // Build: the block put in, and starting over
        brushButton = addRenderableWidget(Button.builder(Component.literal("Block..."), b -> pickBrush()).bounds(px, LIST_TOP, wide, 18).build());
        brushButton.setTooltip(Tooltip.create(Component.literal("The block a click puts in: any block, modded ones too. Middle click a block of the machine: that one")));
        undoBuildButton = addRenderableWidget(Button.builder(Component.literal("Undo the build"), b -> undoBuild())
                .bounds(px, LIST_TOP + ROW, wide, 18).build());
        undoBuildButton.setTooltip(Tooltip.create(Component.literal("Every block back as the machine stands (what isn't saved)")));
        anchorsTab.setTooltip(Tooltip.create(Component.literal("Spots where particles rise, a figure stands or an item floats")));

        // Blocks: a button per motion this machine allows, and one to keep blocks still
        int py = LIST_TOP;
        List<@Nullable Motion> choices = new ArrayList<>();
        choices.add(null);
        for (Motion motion : rules.motions()) if (motion != Motion.MOD) choices.add(motion);
        List<com.lealex.alchyx.animation.StudioMotions.Custom> mods = rules.motions().isEmpty() ? List.of()
                : com.lealex.alchyx.animation.StudioMotions.all(); // other mods' motions (a Create press...)
        int count = choices.size() + mods.size();
        int columns = count > 12 ? 3 : count > 6 ? 2 : 1, column = 0;
        int buttonWide = (wide - 2 * (columns - 1)) / columns;
        motionRows = (count + columns - 1) / columns;
        for (Motion motion : choices) {
            Button button = addRenderableWidget(Button.builder(Component.literal(label(motion)), b -> give(motion))
                    .bounds(px + column * (buttonWide + 2), py, buttonWide, 18).build());
            button.setTooltip(Tooltip.create(Component.literal(explain(motion) + (motion == null || motion == Motion.CHANGE ? ""
                    : ". Shift+click: play it too, with the block's own motion (a drill: Strike, then Shift+click Spin)"))));
            motionButtons.add(button);
            motionChoices.add(motion);
            if (++column == columns) {
                column = 0;
                py += ROW;
            }
        }
        modButtons.clear();
        modChoices.clear();
        for (com.lealex.alchyx.animation.StudioMotions.Custom custom : mods) {
            Identifier id = custom.id();
            Button button = addRenderableWidget(Button.builder(Component.literal(custom.name()), b -> giveMod(id))
                    .bounds(px + column * (buttonWide + 2), py, buttonWide, 18).build());
            button.setTooltip(Tooltip.create(Component.literal(custom.name() + " (from " + id.getNamespace()
                    + "). Shift+click: play it too, with the block's own motion")));
            modButtons.add(button);
            modChoices.add(custom);
            if (++column == columns) {
                column = 0;
                py += ROW;
            }
        }
        // When the chosen blocks' motion plays (from the work bar), and a change's state
        py = LIST_TOP + motionRows * ROW + 2;
        startButton = addRenderableWidget(Button.builder(Component.literal("Start here"), b -> retimeBlocks(0)).bounds(px, py, half, 18).build());
        startButton.setTooltip(Tooltip.create(Component.literal("It plays from this point of the work (the work bar). Drag the bar first")));
        stopButton = addRenderableWidget(Button.builder(Component.literal("Stop here"), b -> retimeBlocks(1)).bounds(px + half + 4, py, half, 18).build());
        stopButton.setTooltip(Tooltip.create(Component.literal("It stops at this point of the work")));
        py += ROW;
        int third = (wide - 4) / 3;
        wholeButton = addRenderableWidget(Button.builder(Component.literal("Always"), b -> retimeBlocks(-1)).bounds(px, py, third, 18).build());
        wholeButton.setTooltip(Tooltip.create(Component.literal("It plays all the time again")));
        stateButton = addRenderableWidget(Button.builder(Component.literal("State..."), b -> pickState()).bounds(px + third + 2, py, third, 18).build());
        dataButton = addRenderableWidget(Button.builder(Component.literal("Data..."), b -> editData())
                .bounds(px + 2 * (third + 2), py, wide - 2 * (third + 2), 18).build());
        dataButton.setTooltip(Tooltip.create(Component.literal("The block's data (a spawner's mob, a banner's patterns...): the machine keeps it and shows it")));
        stateButton.setTooltip(Tooltip.create(Component.literal("The block's state from the work bar's head: any property it has (a cauldron's level, what it holds, a trial spawner's state...). Each step is a keyframe")));
        blockButtons.addAll(List.of(startButton, stopButton, wholeButton, stateButton, dataButton));

        // Shape: hide, move, turn and size the chosen blocks (their look only), or the whole machine
        py = LIST_TOP;
        hideButton = shapeButton("Hide", "Hide the chosen blocks (they still stand there: only unseen)", px, py, half, () ->
                reshape(pose -> pose.withHidden(!chosen.stream().allMatch(cell -> poses.getOrDefault(cell, BlockPose.NONE).hidden()))));
        shapeButton("Reset", "The chosen blocks as they were: in place, straight, full size", px + half + 4, py, half, () -> reshape(pose -> BlockPose.NONE));
        py += ROW;
        int sixth = (wide - 10) / 6;
        String[] arrows = {"X-", "X+", "Y-", "Y+", "Z-", "Z+"};
        for (int i = 0; i < 6; i++) {
            int axis = i / 2, sign = i % 2 == 0 ? -1 : 1;
            shapeButton(arrows[i], "Move the chosen blocks along " + "XYZ".charAt(axis) + " (the step: below)", px + i * (sixth + 2), py,
                    i == 5 ? wide - 5 * (sixth + 2) : sixth, () -> reshape(pose -> pose.withOffset(pose.offset().add(
                            axis == 0 ? sign * nudge : 0, axis == 1 ? sign * nudge : 0, axis == 2 ? sign * nudge : 0))));
        }
        py += ROW;
        int third2 = (wide - 4) / 3;
        String[] turns = {"Turn X", "Turn Y", "Turn Z"};
        for (int i = 0; i < 3; i++) {
            int axis = i;
            shapeButton(turns[i], "Turn the chosen blocks 15 degrees around " + "XYZ".charAt(axis) + " (shift: the other way)", px + i * (third2 + 2), py,
                    i == 2 ? wide - 2 * (third2 + 2) : third2, () -> {
                        double by = Minecraft.getInstance().hasShiftDown() ? -15 : 15;
                        reshape(pose -> pose.withRotation(pose.rotation().add(axis == 0 ? by : 0, axis == 1 ? by : 0, axis == 2 ? by : 0)));
                    });
        }
        py += ROW;
        shapeButton("Size -", "Make the chosen blocks smaller", px, py, half, () -> reshape(pose -> pose.withScale(pose.scale() / 1.25F)));
        shapeButton("Size +", "Make the chosen blocks bigger", px + half + 4, py, half, () -> reshape(pose -> pose.withScale(pose.scale() * 1.25F)));
        py += ROW;
        stepButton = shapeButton(stepName(), "How far X/Y/Z move a block. Click: change", px, py, wide, () -> {
            nudge = nudge >= 1 ? 1 / 16.0 : nudge * 2;
            if (stepButton != null) stepButton.setMessage(Component.literal(stepName()));
        });
        py += ROW + 14;
        shrinkButton = shapeButton("Shrink all", "The whole machine smaller, toward its bottom middle: a mini machine (the core keeps its size)",
                px, py, half, () -> resizeAll(0.5F));
        growButton = shapeButton("Grow all", "The whole machine bigger, from its bottom middle", px + half + 4, py, half, () -> resizeAll(2.0F));
        py += ROW;
        resetAllButton = shapeButton("Reset all", "Every block as it was", px, py, wide, () -> {
            poses.clear();
            stillDirty = true;
            refreshButtons();
        });

        // Anchors: what plays at the spot
        py = LIST_TOP;
        int quarter = (wide - 8) / 5;
        particlesButton = addRenderableWidget(Button.builder(Component.literal("Fx"), b -> putMark(StudioMark.Kind.PARTICLES))
                .bounds(px, py, quarter, 18).build());
        particlesButton.setTooltip(Tooltip.create(Component.literal("Particles rise here: smoke, flames, glyphs...")));
        figureButton = addRenderableWidget(Button.builder(Component.literal("Mob"), b -> putMark(StudioMark.Kind.FIGURE))
                .bounds(px + quarter + 2, py, quarter, 18).build());
        figureButton.setTooltip(Tooltip.create(Component.literal("A figure stands here: a villager, a golem, a cat...")));
        itemButton = addRenderableWidget(Button.builder(Component.literal("Item"), b -> putMark(StudioMark.Kind.ITEM))
                .bounds(px + 2 * (quarter + 2), py, quarter, 18).build());
        itemButton.setTooltip(Tooltip.create(Component.literal("Items float here: the machine's input, output, any item")));
        soundButton = addRenderableWidget(Button.builder(Component.literal("Snd"), b -> putMark(StudioMark.Kind.SOUND))
                .bounds(px + 3 * (quarter + 2), py, quarter, 18).build());
        effectButton = addRenderableWidget(Button.builder(Component.literal("Mod"), b -> putMark(StudioMark.Kind.EFFECT))
                .bounds(px + 4 * (quarter + 2), py, wide - 4 * (quarter + 2), 18).build());
        effectButton.setTooltip(Tooltip.create(Component.literal("An effect a mod draws here: a rift portal, a sigil... (from the mods that add some)")));
        soundButton.setTooltip(Tooltip.create(Component.literal("A sound plays here as the work reaches its start (the timeline)")));
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
        py += ROW;
        pickItemsButton = addRenderableWidget(Button.builder(Component.literal("Items..."), b -> pickItems()).bounds(px, py, wide, 18).build());
        pickItemsButton.setTooltip(Tooltip.create(Component.literal("Its items, each with its own time and way of moving: any item, modded ones too, or a display")));
        anchorButtons.addAll(List.of(particlesButton, figureButton, itemButton, soundButton, effectButton, previousButton, nextButton, turnButton, removeButton,
                appearButton, vanishButton, leaveButton, arriveButton, alwaysButton, pickItemsButton));
        refreshButtons();
    }

    private static String label(@Nullable Motion motion) {
        if (motion == null) return "Still";
        return switch (motion) {
            case FILL_LAVA -> "Lava";
            case FILL_SNOW -> "Snow";
            case CHANGE -> "State";
            default -> capital(motion.getSerializedName());
        };
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
            case FILL -> "Fills up as the work goes on: cauldrons (water), composters, beehives, any block with a fill level, modded too";
            case FILL_LAVA -> "A cauldron fills with lava";
            case FILL_SNOW -> "A cauldron fills with powder snow";
            case GROW -> "Grows: crops, saplings, anything with an age";
            case CHANGE -> "Changes to the state you pick (State...): any block with a state, a trial spawner, a vault, modded blocks...";
            case OPEN -> "Opens: barrels, trapdoors, doors, fence gates";
            case MOD -> "Plays only motions other mods added";
        };
    }

    private @Nullable Button stepButton, shrinkButton, growButton, resetAllButton;

    private Button shapeButton(String label, String tip, int x, int y, int wide, Runnable action) {
        Button button = addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, wide, 18).build());
        button.setTooltip(Tooltip.create(Component.literal(tip)));
        shapeButtons.add(button);
        return button;
    }

    private String stepName() {
        return "Step: " + (nudge >= 1 ? "1 block" : "1/" + Math.round(1 / nudge) + " block");
    }

    /** Changes how the chosen blocks are shaped (the ones that can be: the machine's parts). */
    private void reshape(java.util.function.UnaryOperator<BlockPose> change) {
        for (BlockPos cell : chosen) {
            if (!movable.contains(cell)) continue;
            BlockPose pose = change.apply(poses.getOrDefault(cell, BlockPose.NONE)).tidy();
            if (pose.isNone()) poses.remove(cell);
            else poses.put(cell, pose);
        }
        stillDirty = true;
        refreshButtons();
    }

    /**
     * Sizes the whole machine by {@code by} toward (or from) its bottom middle: each part moves and sizes so the
     * build keeps its shape, smaller or bigger. The core (and blocks that aren't parts) keep theirs.
     */
    private void resizeAll(float by) {
        Vec3 pivot = new Vec3(size.getX() / 2.0, 0, size.getZ() / 2.0);
        for (BlockPos cell : movable) {
            BlockPose pose = poses.getOrDefault(cell, BlockPose.NONE);
            Vec3 middle = Vec3.atCenterOf(cell).add(pose.offset());
            Vec3 moved = pivot.add(middle.subtract(pivot).scale(by));
            BlockPose next = pose.withOffset(moved.subtract(Vec3.atCenterOf(cell))).withScale(pose.scale() * by).tidy();
            if (next.isNone()) poses.remove(cell);
            else poses.put(cell, next);
        }
        stillDirty = true;
        refreshButtons();
    }

    // ---- Build: blocks put in and taken out, like in the world (Save makes them the machine's parts) ----

    /** Whether the blocks shown differ from the machine as it stands. */
    private boolean buildChanged() {
        if (looks.size() != builtFrom.size()) return true;
        for (BlockPos cell : looks.keySet()) if (!builtFrom.containsKey(cell.offset(min))) return true;
        return false;
    }

    private void pickBrush() {
        minecraft.setScreen(new ItemPickerScreen(this, id -> {
            Identifier parsed = Identifier.tryParse(id);
            net.minecraft.world.item.Item item = parsed == null ? null : BuiltInRegistries.ITEM.getValue(parsed);
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) brush = blockItem.getBlock().defaultBlockState();
        }, true));
    }

    /** The block a click puts on a side facing {@code face}: turned that way, if it can turn. */
    private BlockState brushFor(Direction face) {
        BlockState state = brush;
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, face);
        } else if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                && face.getAxis().isHorizontal()) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, face);
        } else if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, face.getAxis());
        }
        return state;
    }

    /** Puts the brush's block on the side {@code face} of the block at {@code cell}, if it fits there. */
    private void putIn(BlockPos cell, Direction face) {
        if (building == null) return;
        BlockPos offset = cell.relative(face).offset(min);
        if (looks.containsKey(offset.subtract(min))) return;
        ClientLevel level = Minecraft.getInstance().level;
        BlockState there = level == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(corePos.offset(offset));
        if (!builtFrom.containsKey(offset) && !there.isAir() && !there.canBeReplaced()) {
            buildProblem = "Something stands there in the world (" + there.getBlock().getName().getString() + "): take it away first";
            return;
        }
        BlockPos high = min.offset(size).offset(-1, -1, -1);
        BlockPos low = BlockPos.min(min, offset), top = BlockPos.max(high, offset);
        int wideX = top.getX() - low.getX() + 1, wideZ = top.getZ() - low.getZ() + 1, tall = top.getY() - low.getY() + 1;
        if (Math.max(wideX, wideZ) > building.maxWidth() || tall > building.maxHeight()) {
            buildProblem = "That's past what this machine allows: " + building.maxWidth() + " wide, " + building.maxHeight() + " high";
            return;
        }
        if (!low.equals(min) || !top.equals(high)) regrow(low, top);
        BlockPos at = offset.subtract(min);
        looks.put(at, brushFor(face));
        movable.add(at);
        buildProblem = null;
        stillDirty = true;
        refreshButtons();
    }

    /** Takes the block at {@code cell} out (not the core). */
    private void takeOut(BlockPos cell) {
        if (building == null || !looks.containsKey(cell)) return;
        if (cell.offset(min).equals(BlockPos.ZERO)) {
            buildProblem = "The core stays: the machine is built around it";
            return;
        }
        looks.remove(cell);
        movable.remove(cell);
        edits.remove(cell);
        setups.remove(cell);
        poses.remove(cell);
        dataEdits.remove(cell);
        chosen.remove(cell);
        buildProblem = null;
        stillDirty = true;
        refreshButtons();
    }

    /** Every block back as the machine stands. */
    private void undoBuild() {
        for (BlockPos cell : new ArrayList<>(looks.keySet())) if (!builtFrom.containsKey(cell.offset(min))) takeOut(cell);
        ClientLevel level = Minecraft.getInstance().level;
        builtFrom.forEach((offset, look) -> {
            BlockPos cell = offset.subtract(min);
            if (looks.containsKey(cell)) return;
            looks.put(cell, look);
            if (level != null && ShellParts.isPart(level.getBlockState(corePos.offset(offset)))) movable.add(cell);
            BlockPos core = BlockPos.ZERO.subtract(min);
            if (cell.equals(core)) movable.remove(cell);
        });
        for (Map.Entry<BlockPos, Motion> entry : saved.entrySet()) if (!edits.containsKey(entry.getKey())) edits.put(entry.getKey(), entry.getValue());
        for (Map.Entry<BlockPos, MotionSetup> entry : savedSetups.entrySet()) setups.putIfAbsent(entry.getKey(), entry.getValue());
        for (Map.Entry<BlockPos, BlockPose> entry : savedPoses.entrySet()) poses.putIfAbsent(entry.getKey(), entry.getValue());
        buildProblem = null;
        stillDirty = true;
        refreshButtons();
    }

    /** The box grows to {@code low} .. {@code high} (offsets from the core): everything kept by box cell moves along. */
    private void regrow(BlockPos low, BlockPos high) {
        BlockPos by = min.subtract(low); // old cell + by = new cell
        boolean allShown = top >= size.getY() - 1;
        shift(looks, by);
        shift(edits, by);
        shift(saved, by);
        shift(setups, by);
        shift(savedSetups, by);
        shift(dataEdits, by);
        shift(poses, by);
        shift(savedPoses, by);
        shiftMarks(marks, by);
        shiftMarks(savedMarks, by);
        shift(movable, by);
        shift(chosen, by);
        shift(fileMoved, by);
        List<FileMove> moved = new ArrayList<>();
        for (FileMove file : fileMoves) moved.add(new FileMove(file.cell().offset(by), file.move()));
        fileMoves.clear();
        fileMoves.addAll(moved);
        if (spot != null) spot = spot.offset(by);
        hovered = null;
        min = low;
        size = new BlockPos(high.getX() - low.getX() + 1, high.getY() - low.getY() + 1, high.getZ() - low.getZ() + 1);
        top = allShown ? size.getY() - 1 : Mth.clamp(top + by.getY(), 0, size.getY() - 1);
        still = null;
        stillDirty = true;
    }

    private static <V> void shift(Map<BlockPos, V> map, BlockPos by) {
        Map<BlockPos, V> moved = new LinkedHashMap<>();
        map.forEach((cell, value) -> moved.put(cell.offset(by), value));
        map.clear();
        map.putAll(moved);
    }

    private static void shift(Set<BlockPos> set, BlockPos by) {
        List<BlockPos> moved = new ArrayList<>();
        for (BlockPos cell : set) moved.add(cell.offset(by));
        set.clear();
        set.addAll(moved);
    }

    private static void shiftMarks(Map<BlockPos, StudioMark> map, BlockPos by) {
        Map<BlockPos, StudioMark> moved = new LinkedHashMap<>();
        map.forEach((cell, mark) -> moved.put(cell.offset(by), mark.shifted(by)));
        map.clear();
        map.putAll(moved);
    }

    /** A message cut in lines that fit the panel. */
    private List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.isEmpty() ? word : line + " " + word;
            if (font.width(tried) > PANEL_WIDE - 10 && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private void setTab(Tab to) {
        tab = to;
        refreshButtons();
    }

    private boolean changed() {
        return !edits.equals(saved) || !setups.equals(savedSetups) || !marks.equals(savedMarks) || !dataEdits.isEmpty()
                || hideFile != savedHideFile || !poses.equals(savedPoses) || buildChanged();
    }

    private void refreshButtons() {
        if (layerDown != null) layerDown.active = top > 0;
        if (layerUp != null) layerUp.active = top < size.getY() - 1;
        if (saveButton != null) saveButton.active = changed();
        if (blocksTab != null) blocksTab.active = tab != Tab.BLOCKS;
        if (anchorsTab != null) anchorsTab.active = tab != Tab.ANCHORS;
        if (shapeTab != null) shapeTab.active = tab != Tab.SHAPE;
        if (buildTab != null) buildTab.active = tab != Tab.BUILD && building != null;
        if (brushButton != null) {
            brushButton.visible = tab == Tab.BUILD;
            brushButton.setMessage(Component.literal(clip("Block: " + brush.getBlock().getName().getString())));
        }
        if (undoBuildButton != null) {
            undoBuildButton.visible = tab == Tab.BUILD;
            undoBuildButton.active = buildChanged();
        }
        boolean blocks = tab == Tab.BLOCKS;
        boolean posable = !chosen.isEmpty() && movable.containsAll(chosen);
        for (Button button : shapeButtons) {
            button.visible = tab == Tab.SHAPE;
            button.active = posable;
        }
        if (hideButton != null) {
            boolean allHidden = posable && chosen.stream().allMatch(cell -> poses.getOrDefault(cell, BlockPose.NONE).hidden());
            hideButton.setMessage(Component.literal(allHidden ? "Show" : "Hide"));
        }
        if (shrinkButton != null) shrinkButton.active = !movable.isEmpty();
        if (growButton != null) growButton.active = !movable.isEmpty();
        if (resetAllButton != null) resetAllButton.active = !poses.isEmpty();
        if (stepButton != null) stepButton.active = true;

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
            boolean also = motion != null && !chosen.isEmpty()
                    && chosen.stream().allMatch(cell -> setups.getOrDefault(cell, MotionSetup.ALWAYS).also().contains(motion) && edits.containsKey(cell));
            button.setMessage(Component.literal(current ? "> " + label(motion) + " <" : also ? "+ " + label(motion) : label(motion)));
        }
        for (int i = 0; i < modButtons.size(); i++) {
            Button button = modButtons.get(i);
            com.lealex.alchyx.animation.StudioMotions.Custom custom = modChoices.get(i);
            button.visible = blocks;
            button.active = !chosen.isEmpty() && chosen.stream().allMatch(cell -> looks.get(cell) != null && custom.fits().test(looks.get(cell)));
            boolean alone = !chosen.isEmpty() && chosen.stream().allMatch(cell -> edits.get(cell) == Motion.MOD
                    && setups.getOrDefault(cell, MotionSetup.ALWAYS).mods().equals(List.of(custom.id())));
            boolean with = !alone && !chosen.isEmpty() && chosen.stream().allMatch(cell -> edits.containsKey(cell)
                    && setups.getOrDefault(cell, MotionSetup.ALWAYS).mods().contains(custom.id()));
            button.setMessage(Component.literal(alone ? "> " + custom.name() + " <" : with ? "+ " + custom.name() : custom.name()));
        }

        boolean moving = !chosen.isEmpty();
        for (BlockPos cell : chosen) if (!edits.containsKey(cell)) moving = false;
        for (Button button : blockButtons) {
            button.visible = blocks;
            button.active = moving;
        }
        if (dataButton != null) dataButton.active = !chosen.isEmpty() && chosen.stream().allMatch(cell -> {
            BlockState own = looks.get(cell), lead = looks.get(chosen.iterator().next());
            return own != null && lead != null && own.hasBlockEntity() && own.getBlock() == lead.getBlock();
        });
        if (stateButton != null) stateButton.active = chosen.size() > 0 && rules.motions().contains(Motion.CHANGE)
                && chosen.stream().allMatch(cell -> Motion.CHANGE.fits(looks.get(cell)));

        StudioMark mark = spot == null ? null : marks.get(spot);
        for (Button button : anchorButtons) button.visible = tab == Tab.ANCHORS;
        if (particlesButton != null) particlesButton.active = spot != null && !rules.particles().isEmpty()
                && (mark == null || mark.kind() != StudioMark.Kind.PARTICLES) && (mark != null || marks.size() < StudioMark.MAX);
        if (figureButton != null) figureButton.active = spot != null && !rules.figures().isEmpty()
                && (mark == null || mark.kind() != StudioMark.Kind.FIGURE) && (mark != null || marks.size() < StudioMark.MAX);
        if (itemButton != null) itemButton.active = spot != null
                && (mark == null || mark.kind() != StudioMark.Kind.ITEM) && (mark != null || marks.size() < StudioMark.MAX);
        if (soundButton != null) soundButton.active = spot != null
                && (mark == null || mark.kind() != StudioMark.Kind.SOUND) && (mark != null || marks.size() < StudioMark.MAX);
        if (effectButton != null) effectButton.active = spot != null && !choicesFor(StudioMark.Kind.EFFECT).isEmpty()
                && (mark == null || mark.kind() != StudioMark.Kind.EFFECT) && (mark != null || marks.size() < StudioMark.MAX);
        if (previousButton != null) previousButton.active = mark != null;
        if (nextButton != null) nextButton.active = mark != null;
        if (removeButton != null) removeButton.active = mark != null;
        if (pickItemsButton != null) {
            boolean sound = mark != null && mark.kind() == StudioMark.Kind.SOUND;
            boolean figure = mark != null && mark.kind() == StudioMark.Kind.FIGURE;
            pickItemsButton.active = spot != null && (mark == null ? marks.size() < StudioMark.MAX
                    : mark.kind() == StudioMark.Kind.ITEM || sound || figure);
            pickItemsButton.setMessage(Component.literal(sound ? "Pick sound..." : figure
                    ? "Action: " + (pickingTo ? "walk to (pick spot)" : actionName(mark)) : "Items..."));
            pickItemsButton.setTooltip(Tooltip.create(Component.literal(figure
                    ? "What the mob does: stands still, walks (in place, or to a spot you click next, through its show time), swings an arm, flaps its wings, hops or spins. Click: next"
                    : "Its items, each with its own time and way of moving: any item, modded ones too, or a display")));
        }
        boolean travels = mark != null && mark.kind() == StudioMark.Kind.ITEM && mark.to().isPresent();
        for (Button button : new Button[] {appearButton, vanishButton, alwaysButton}) if (button != null) button.active = mark != null;
        for (Button button : new Button[] {leaveButton, arriveButton}) if (button != null) button.active = travels;

        if (turnButton != null && mark != null && mark.kind() == StudioMark.Kind.SOUND) {
            turnButton.active = true;
            turnButton.setMessage(Component.literal(mark.yaw() == 0 ? "Once" : "Every " + seconds100(mark.yaw())));
            turnButton.setTooltip(Tooltip.create(Component.literal("Once as the work reaches its start, or again and again until its end. Click: change")));
        } else if (turnButton != null) {
            boolean item = mark != null && mark.kind() == StudioMark.Kind.ITEM;
            turnButton.active = mark != null && mark.kind() != StudioMark.Kind.PARTICLES;
            turnButton.setMessage(Component.literal(!item ? "Turn" : pickingTo ? "Pick spot..." : mark.to().isPresent() ? "Stay" : "Travel"));
            turnButton.setTooltip(Tooltip.create(Component.literal(!item ? "Turn the figure a quarter"
                    : mark.to().isPresent() ? "Stop traveling: float here" : "Then click the spot it travels to as the work goes on")));
        }
    }

    // ---- Changes ----

    private final List<Button> modButtons = new ArrayList<>();
    private final List<com.lealex.alchyx.animation.StudioMotions.Custom> modChoices = new ArrayList<>();

    /** Gives the chosen blocks a motion another mod added: alone, or (Shift) played too with their own. */
    private void giveMod(Identifier id) {
        boolean too = Minecraft.getInstance().hasShiftDown();
        for (BlockPos cell : chosen) {
            BlockState look = looks.get(cell);
            if (look == null || !com.lealex.alchyx.animation.StudioMotions.fits(id, look)) continue;
            Motion main = edits.get(cell);
            MotionSetup setup = setups.getOrDefault(cell, MotionSetup.ALWAYS);
            if (main == null || !too) {
                edits.put(cell, Motion.MOD);
                putSetup(cell, new MotionSetup(setup.from(), setup.until(), List.of(), List.of(), List.of(), List.of(id)));
                continue;
            }
            List<Identifier> mods = new ArrayList<>(setup.mods());
            if (!mods.remove(id)) mods.add(id);
            if (main == Motion.MOD && mods.isEmpty()) {
                edits.remove(cell);
                setups.remove(cell);
            } else {
                putSetup(cell, setup.withMods(mods));
            }
        }
        stillDirty = true;
        refreshButtons();
    }

    private void give(@Nullable Motion motion) {
        if (motion != null && motion != Motion.CHANGE && Minecraft.getInstance().hasShiftDown()) { // played too, with the block's own
            for (BlockPos cell : chosen) {
                Motion main = edits.get(cell);
                BlockState look = looks.get(cell);
                if (look == null || !motion.fits(look)) continue;
                if (main == null) {
                    edits.put(cell, motion);
                    continue;
                }
                if (main == motion) continue;
                MotionSetup setup = setups.getOrDefault(cell, MotionSetup.ALWAYS);
                List<Motion> also = new ArrayList<>(setup.also());
                if (!also.remove(motion)) also.add(motion);
                putSetup(cell, setup.withAlso(also).tidy(main, look));
            }
            stillDirty = true;
            refreshButtons();
            return;
        }
        if (motion == Motion.CHANGE) {
            pickState();
            return;
        }
        for (BlockPos cell : chosen) {
            if (motion == null) {
                edits.remove(cell);
                setups.remove(cell);
            } else if (motion.fits(looks.get(cell))) {
                edits.put(cell, motion);
                MotionSetup setup = setups.get(cell);
                if (setup != null) putSetup(cell, setup.withoutSteps()); // (a state is only for a change)
            }
        }
        stillDirty = true;
        refreshButtons();
    }

    private void putSetup(BlockPos cell, MotionSetup setup) {
        if (setup.equals(MotionSetup.ALWAYS)) setups.remove(cell);
        else setups.put(cell, setup);
    }

    /** Sets when the chosen blocks' motion plays from where the work bar stands: 0 start, 1 stop; -1 all along. */
    private void retimeBlocks(int which) {
        int now = Math.round(progress(0) * 100);
        for (BlockPos cell : chosen) {
            if (!edits.containsKey(cell)) continue;
            MotionSetup setup = setups.getOrDefault(cell, MotionSetup.ALWAYS);
            putSetup(cell, switch (which) {
                case 0 -> setup.withTimes(now, Math.max(now, setup.until()));
                case 1 -> setup.withTimes(Math.min(now, setup.from()), now);
                default -> setup.withTimes(0, 100);
            });
        }
        stillDirty = true;
        refreshButtons();
    }

    /** The data a cell's block keeps now: as changed here, or as its shell in the world has it. */
    private net.minecraft.nbt.CompoundTag dataOf(BlockPos cell) {
        net.minecraft.nbt.CompoundTag changed = dataEdits.get(cell);
        if (changed != null) return changed.copy();
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(corePos.offset(cell.offset(min)))
                instanceof com.lealex.alchyx.block.entity.ChamberShellBlockEntity shell) {
            net.minecraft.nbt.CompoundTag kept = shell.getData();
            if (kept != null) return kept;
        }
        return new net.minecraft.nbt.CompoundTag();
    }

    /** Opens the data menu for the chosen blocks (the first one's data); they all get what's written there. */
    private void editData() {
        if (chosen.isEmpty()) return;
        List<BlockPos> cells = new ArrayList<>(chosen);
        BlockState look = looks.get(cells.getFirst());
        if (look == null || !look.hasBlockEntity()) return;
        minecraft.setScreen(new DataEditScreen(this, look, dataOf(cells.getFirst()), tag -> {
            for (BlockPos cell : cells) dataEdits.put(cell, tag.copy());
        }));
    }

    /**
     * Opens the state menu for the chosen blocks, on the step of their change showing at the work bar's head (the first
     * block's), or a new step there. What's picked goes to all of them; a change left with no step stops.
     */
    private void pickState() {
        if (chosen.isEmpty() || !rules.motions().contains(Motion.CHANGE)) return;
        List<BlockPos> cells = new ArrayList<>(chosen);
        BlockPos first = cells.getFirst();
        BlockState look = looks.get(first);
        if (look == null || !Motion.CHANGE.fits(look)) return;
        float progress = progress(0);
        int now = Math.round(progress * 100);
        MotionSetup current = edits.get(first) == Motion.CHANGE ? setups.getOrDefault(first, MotionSetup.ALWAYS) : MotionSetup.ALWAYS;
        int index = current.stepAt(progress);
        boolean editing = index >= 0;
        String where = editing ? "step " + (index + 1) + " of " + current.steps().size() + ", from " + seconds(current.time(index))
                : "a new step from " + seconds(now);
        Map<String, String> start = editing ? current.steps().get(index) : Map.of();
        minecraft.setScreen(new StatePickerScreen(this, look, start, where, editing, (action, state) -> {
            for (BlockPos cell : cells) {
                BlockState own = looks.get(cell);
                if (own == null || !Motion.CHANGE.fits(own)) continue;
                MotionSetup setup = edits.get(cell) == Motion.CHANGE ? setups.getOrDefault(cell, MotionSetup.ALWAYS)
                        : setups.getOrDefault(cell, MotionSetup.ALWAYS).withoutSteps();
                int at = setup.stepAt(progress);
                setup = switch (action) {
                    case SET -> setup.withStep(at < 0 ? setup.steps().size() : at, state, now);
                    case ADD -> setup.withStep(setup.steps().size(), state, now);
                    case REMOVE -> at < 0 ? setup : setup.withoutStep(at);
                };
                setup = setup.tidy(Motion.CHANGE, own);
                if (setup.steps().isEmpty()) {
                    if (edits.get(cell) == Motion.CHANGE) {
                        edits.remove(cell);
                        setups.remove(cell);
                    }
                    continue;
                }
                edits.put(cell, Motion.CHANGE);
                putSetup(cell, setup);
            }
            stillDirty = true;
        }));
    }

    private List<String> choicesFor(StudioMark.Kind kind) {
        List<String> usable = new ArrayList<>();
        switch (kind) {
            case FIGURE -> rules.figures().forEach(id -> usable.add(id.toString()));
            case EFFECT -> {
                for (Identifier id : com.lealex.alchyx.animation.StudioEffects.ids()) {
                    if (com.lealex.alchyx.client.animation.Figures.get(id) != null) usable.add(id.toString());
                }
            }
            case ITEM -> usable.addAll(StudioMark.DISPLAYS);
            case SOUND -> usable.addAll(soundIds());
            case PARTICLES -> {
                for (Identifier id : rules.particles()) if (StudioMark.particle(id) != null) usable.add(id.toString());
            }
        }
        return usable;
    }

    private static String seconds100(int ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0F);
    }

    /** Every sound of the game (modded ones too), by id. */
    private static List<String> soundIds() {
        List<String> ids = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) ids.add(id.toString());
        ids.sort(null);
        return ids;
    }

    /** Plays a sound in the studio, to hear it. */
    private static void hear(String id) {
        Identifier sound = Identifier.tryParse(id);
        if (sound == null) return;
        BuiltInRegistries.SOUND_EVENT.getOptional(sound).ifPresent(event -> Minecraft.getInstance().getSoundManager()
                .play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, 1.0F)));
    }

    /** Opens the sound list for the chosen spot: its sound anchor's sound, or a new sound anchor once one is picked. */
    private void pickSound() {
        if (spot == null) return;
        BlockPos at = spot;
        StudioMark mark = marks.get(at);
        String current = mark != null && mark.kind() == StudioMark.Kind.SOUND ? mark.what() : null;
        minecraft.setScreen(new IdPickerScreen(this, "Pick a sound", soundIds(), current, StudioScreen::hear, id -> {
            StudioMark now = marks.get(at);
            if (now != null && now.kind() == StudioMark.Kind.SOUND) marks.put(at, now.withWhat(id));
            else if (now != null || marks.size() < StudioMark.MAX) marks.put(at, new StudioMark(at, StudioMark.Kind.SOUND, id, 0));
        }));
    }

    private void putMark(StudioMark.Kind kind) {
        if (spot == null) return;
        if (kind == StudioMark.Kind.SOUND) {
            pickingTo = false;
            pickSound();
            return;
        }
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
        if (mark.kind() == StudioMark.Kind.SOUND) { // once, or again every so often
            List<Integer> repeats = StudioMark.SOUND_REPEATS;
            marks.put(spot, mark.withYaw(repeats.get((Math.max(0, repeats.indexOf(mark.yaw())) + 1) % repeats.size())));
        } else if (mark.kind() == StudioMark.Kind.ITEM) {
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

    /** Opens the item list of the chosen spot's item anchor (a new item anchor once items are added there). */
    private void pickItems() {
        if (spot == null) return;
        BlockPos at = spot;
        StudioMark mark = marks.get(at);
        if (mark != null && mark.kind() == StudioMark.Kind.SOUND) {
            pickSound();
            return;
        }
        if (mark != null && mark.kind() == StudioMark.Kind.FIGURE) {
            nextAction(at, mark);
            return;
        }
        if (mark != null && mark.kind() != StudioMark.Kind.ITEM) return;
        StudioMark start = mark != null ? mark : new StudioMark(at, StudioMark.Kind.ITEM, StudioMark.DISPLAYS.getFirst(), 0);
        minecraft.setScreen(new ItemListScreen(this, start, (items, shows) -> {
            StudioMark now = marks.get(at);
            if (now == null) {
                if (items.isEmpty() || marks.size() >= StudioMark.MAX) return;
                now = start;
            }
            if (now.kind() == StudioMark.Kind.ITEM) marks.put(at, now.withItems(items, shows));
        }));
    }

    /** What a figure can do, in the order the Action button goes through them ("walk to": walks to a spot picked next). */
    private static final List<String> ACTIONS = List.of("still", "walk", "walk to", "swing", "flap", "hop", "spin");

    private static String actionName(StudioMark mark) {
        Effect.FigureAction action = mark.action();
        return action == Effect.FigureAction.WALK && mark.to().isPresent() ? "walk to" : action.getSerializedName();
    }

    /** The figure's next action; "walk to" asks for the spot it walks to. */
    private void nextAction(BlockPos at, StudioMark mark) {
        String next = ACTIONS.get((ACTIONS.indexOf(actionName(mark)) + 1) % ACTIONS.size());
        if (pickingTo && actionName(mark).equals("walk")) next = "swing"; // (it was waiting for a spot: move on)
        pickingTo = false;
        StudioMark changed = mark.withTo(java.util.Optional.empty());
        if (next.equals("walk to")) {
            changed = changed.withAction(Effect.FigureAction.WALK);
            pickingTo = true;
        } else {
            changed = changed.withAction(Effect.FigureAction.byName(next));
        }
        marks.put(at, changed);
        refreshButtons();
    }

    private static net.minecraft.world.item.ItemStack icon(String entry) {
        return ItemPickerScreen.icon(entry);
    }

    private static Component entryName(String entry) {
        return ItemPickerScreen.entryName(entry);
    }

    private void drawEntry(GuiGraphicsExtractor graphics, String entry, int x, int y) {
        ItemPickerScreen.drawEntry(graphics, font, entry, x, y);
    }

    private static final int ITEM_ROW = 18;

    /** Where the item list's icons start, and how many go in a row. */
    private int itemsY() { return LIST_TOP + 7 * ROW + 18; }

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
        Map<BlockPos, MotionSetup> timings = new LinkedHashMap<>();
        setups.forEach((cell, setup) -> {
            if (edits.containsKey(cell)) timings.put(cell.offset(min), setup);
        });
        Map<BlockPos, net.minecraft.nbt.CompoundTag> data = new LinkedHashMap<>();
        dataEdits.forEach((cell, tag) -> data.put(cell.offset(min), tag));
        Map<BlockPos, BlockPose> shaped = new LinkedHashMap<>();
        poses.forEach((cell, pose) -> shaped.put(cell.offset(min), pose));
        // The build: blocks put in and taken out (by offset from the core)
        Map<BlockPos, BlockState> added = new LinkedHashMap<>();
        List<BlockPos> removed = new ArrayList<>();
        looks.forEach((cell, look) -> {
            if (!builtFrom.containsKey(cell.offset(min))) added.put(cell.offset(min), look);
        });
        for (BlockPos offset : builtFrom.keySet()) if (!looks.containsKey(offset.subtract(min))) removed.add(offset);
        rebuilding = !added.isEmpty() || !removed.isEmpty();
        connection.send(new ServerboundCustomPayloadPacket(new MachineStudio.Save(corePos, motions, timings, sent, data, hideFile, shaped,
                added, removed)));
        dataEdits.clear();
        saved.clear();
        saved.putAll(edits);
        savedSetups.clear();
        savedSetups.putAll(setups);
        savedMarks.clear();
        savedMarks.putAll(marks);
        savedHideFile = hideFile;
        savedPoses.clear();
        savedPoses.putAll(poses);
        refreshButtons();
    }

    /**
     * Writes what's made here (as it stands in the studio, saved or not) as an animation file for a datapack, in the
     * game folder (alchyx_exports/<namespace>/machine_animation/, under the name picked in the Export window). Every machine of this kind
     * then plays it; with its own animation off, the file replaces the mod's ("replace": true).
     */
    private void exportTo(java.nio.file.Path path) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(corePos) instanceof MultiblockCoreBlockEntity core)) return;
        Map<BlockPos, Motion> motions = new LinkedHashMap<>();
        Map<BlockPos, MotionSetup> timings = new LinkedHashMap<>();
        edits.forEach((cell, motion) -> {
            motions.put(cell.offset(min), motion);
            MotionSetup setup = setups.get(cell);
            if (setup != null) timings.put(cell.offset(min), setup);
        });
        List<StudioMark> sent = new ArrayList<>();
        for (StudioMark mark : marks.values()) sent.add(mark.shifted(min));
        Map<BlockPos, BlockPose> shaped = new LinkedHashMap<>();
        poses.forEach((cell, pose) -> shaped.put(cell.offset(min), pose));
        com.lealex.alchyx.client.animation.Motions.Layer layer = com.lealex.alchyx.client.animation.Motions.layer(minecraft.level, corePos,
                motions, timings, sent, shaped, rules.motionWhen(), core.rotation());
        Identifier machine = core.patternId();
        com.lealex.alchyx.animation.MachineAnimation file = new com.lealex.alchyx.animation.MachineAnimation(java.util.Optional.of(machine),
                hideFile, layer.anchors(), layer.effects());
        String message;
        try {
            com.google.gson.JsonElement json = com.lealex.alchyx.animation.MachineAnimation.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, file).getOrThrow();
            // What was made here, for Import (in the pattern's own frame, like the anchors)
            List<com.lealex.alchyx.animation.StudioSave.Moving> moving = new ArrayList<>();
            motions.forEach((offset, motion) -> moving.add(new com.lealex.alchyx.animation.StudioSave.Moving(offset, motion,
                    timings.getOrDefault(offset, MotionSetup.ALWAYS))));
            List<com.lealex.alchyx.animation.StudioSave.Posed> posedList = new ArrayList<>();
            shaped.forEach((offset, pose) -> posedList.add(new com.lealex.alchyx.animation.StudioSave.Posed(offset, pose)));
            com.lealex.alchyx.animation.StudioSave studio = new com.lealex.alchyx.animation.StudioSave(moving, sent, hideFile, posedList)
                    .turned(MultiblockPattern.inverse(core.rotation()));
            json.getAsJsonObject().add("alchyx_studio", com.lealex.alchyx.animation.StudioSave.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, studio).getOrThrow());
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json));
            message = "Exported to " + minecraft.gameDirectory.toPath().relativize(path).toString().replace('\\', '/')
                    + " (put it in a datapack: data/" + machine.getNamespace() + "/machine_animation/)";
        } catch (Exception e) {
            AlchyX.LOGGER.warn("AlchyX: studio export failed", e);
            message = "Couldn't export: " + e.getMessage();
        }
        if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal(message));
    }

    /** The exports folder in the game folder. */
    private static java.nio.file.Path exportRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("alchyx_exports");
    }

    /** The Export or Import window: a file name to write, or a file to bring in. */
    private void openFiles(boolean exporting) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(corePos) instanceof MultiblockCoreBlockEntity core)) return;
        Identifier machine = core.patternId();
        java.nio.file.Path folder = exportRoot().resolve(machine.getNamespace()).resolve("machine_animation");
        String path = machine.getPath();
        java.nio.file.Path inner = folder;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            inner = folder.resolve(path.substring(0, slash));
            path = path.substring(slash + 1);
        }
        minecraft.setScreen(new StudioFileScreen(this, exporting, exportRoot(), inner, core.getBlockState().getBlock().getName().getString(),
                path + "_studio", exporting ? this::exportTo : this::importFrom));
    }

    /**
     * Brings an exported file back into the studio (its "alchyx_studio" part), in place of what's here: its motions,
     * anchors and whether the machine's own animation is off. Nothing changes on the machine until Save.
     */
    private void importFrom(java.nio.file.Path path) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(corePos) instanceof MultiblockCoreBlockEntity core)) return;
        String relative = minecraft.gameDirectory.toPath().relativize(path).toString().replace('\\', '/');
        String message;
        try {
            if (!java.nio.file.Files.exists(path)) {
                message = "Nothing to import: no " + relative + " (Export makes it; put a file there to bring it in)";
            } else {
                com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path));
                com.google.gson.JsonElement part = json.isJsonObject() ? json.getAsJsonObject().get("alchyx_studio") : null;
                if (part == null) {
                    message = "Can't import " + relative + ": it wasn't made by the studio's Export (no \"alchyx_studio\")";
                } else {
                    com.lealex.alchyx.animation.StudioSave studio = com.lealex.alchyx.animation.StudioSave.CODEC
                            .parse(com.mojang.serialization.JsonOps.INSTANCE, part).getOrThrow().turned(core.rotation());
                    int skipped = 0;
                    edits.clear();
                    setups.clear();
                    for (com.lealex.alchyx.animation.StudioSave.Moving moving : studio.motions()) {
                        BlockPos cell = moving.offset().subtract(min);
                        BlockState look = looks.get(cell);
                        if (!movable.contains(cell) || look == null || !rules.motions().contains(moving.motion()) || !moving.motion().fits(look)) {
                            skipped++;
                            continue;
                        }
                        edits.put(cell, moving.motion());
                        putSetup(cell, moving.setup().tidy(moving.motion(), look));
                    }
                    marks.clear();
                    for (StudioMark mark : studio.marks()) {
                        if (marks.size() >= StudioMark.MAX) {
                            skipped++;
                            continue;
                        }
                        StudioMark here = mark.shifted(BlockPos.ZERO.subtract(min));
                        marks.put(here.offset(), here);
                    }
                    hideFile = fixed && studio.hideFile();
                    poses.clear();
                    for (com.lealex.alchyx.animation.StudioSave.Posed posed : studio.poses()) {
                        BlockPos cell = posed.offset().subtract(min);
                        if (!movable.contains(cell)) {
                            skipped++;
                            continue;
                        }
                        BlockPose pose = posed.pose().tidy();
                        if (!pose.isNone()) poses.put(cell, pose);
                    }
                    chosen.clear();
                    spot = null;
                    stillDirty = true;
                    rebuildWidgets();
                    message = "Imported " + relative + ": " + edits.size() + " moving blocks, " + poses.size() + " reshaped, " + marks.size() + " anchors"
                            + (skipped > 0 ? " (" + skipped + " didn't fit this machine)" : "") + ". Save to keep it";
                    com.google.gson.JsonElement madeFor = json.getAsJsonObject().get("machine");
                    if (madeFor != null && madeFor.isJsonPrimitive() && !madeFor.getAsString().equals(core.patternId().toString())) {
                        message += ". It was made for " + madeFor.getAsString() + ", so things may sit oddly";
                    }
                }
            }
        } catch (Exception e) {
            AlchyX.LOGGER.warn("AlchyX: studio import failed", e);
            message = "Couldn't import " + relative + ": " + e.getMessage();
        }
        if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal(message));
    }

    // ---- Each tick: the machine must still be there ----

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(corePos) instanceof MultiblockCoreBlockEntity core)
                || !MachineStudio.opens(core) || (core.recordedShape() != null ? core.recordedShape() : core.pattern()) != shape
                || minecraft.player == null || minecraft.player.distanceToSqr(corePos.getCenter()) > 16 * 16) {
            if (rebuilding && minecraft.level != null && minecraft.level.getBlockEntity(corePos) instanceof MultiblockCoreBlockEntity again
                    && MachineStudio.opens(again)) {
                minecraft.setScreen(new StudioScreen(again, tab, yaw, pitch, zoom)); // the new build, to go on with it
                return;
            }
            onClose();
            return;
        }
        if (playing) {
            float before = progress(0);
            clock++;
            playSounds(before, progress(0));
        }
    }

    /** The preview's sounds, as the machine plays them: at their start, and again every so often for a repeating one. */
    private void playSounds(float before, float now) {
        boolean wrapped = now < before; // a new operation began
        for (StudioMark mark : marks.values()) {
            if (mark.kind() != StudioMark.Kind.SOUND) continue;
            float from = mark.times().showFrom() / 100.0F, until = mark.times().showUntil() / 100.0F;
            boolean reached = wrapped ? (now >= from || before < from) : before < from && now >= from;
            if (mark.times().shownAlways()) reached = wrapped;
            if (mark.yaw() > 0) {
                int tick = Math.round(now * OPERATION);
                reached = now >= from && now <= until && (tick - Math.round(from * OPERATION)) % mark.yaw() == 0;
            }
            if (reached) hear(mark.what());
        }
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
        for (BlockPos cell : looks.keySet()) if (cell.getY() <= top && !(tab == Tab.ANCHORS && poses.getOrDefault(cell, BlockPose.NONE).hidden())) shown.add(cell);
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
        int column = (int) ((x - left) / 18), row = (int) ((y - top) / ITEM_ROW);
        if (column >= ICONS_PER_ROW || (y - top) % ITEM_ROW >= 18) return -1;
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

    // ---- The timeline: over the work bar, a track for each thing timed (the chosen blocks' motion; the chosen
    // anchor, and each of its items), a bar from where it starts to where it ends. Drag a bar's ends (or the whole
    // bar) along the work; they snap to the player (the work bar's head) and to the other keyframes ----

    private static final int TRACK = 11, SNAP = 4;

    /** One timed thing: its name, its start and end (percent of the work), its color, and how to change them. */
    private record Track(String name, int from, int until, int color, java.util.function.BiConsumer<Integer, Integer> set) {}

    /** A change's step in a few words: what a cauldron holds, then its values. */
    private static String stepName(Map<String, String> step) {
        List<String> parts = new ArrayList<>();
        String block = step.get("block");
        if (block != null) parts.add(switch (block) {
            case "minecraft:cauldron" -> "empty";
            case "minecraft:water_cauldron" -> "water";
            case "minecraft:lava_cauldron" -> "lava";
            case "minecraft:powder_snow_cauldron" -> "snow";
            default -> block;
        });
        for (Map.Entry<String, String> entry : step.entrySet()) if (!entry.getKey().equals("block")) parts.add(entry.getKey() + " " + entry.getValue());
        return String.join(", ", parts);
    }

    private List<Track> tracks() {
        List<Track> tracks = new ArrayList<>();
        if (tab == Tab.SHAPE || tab == Tab.BUILD) return tracks;
        if (tab == Tab.BLOCKS) {
            if (chosen.isEmpty() || !edits.keySet().containsAll(chosen)) return tracks;
            BlockPos first = chosen.iterator().next();
            MotionSetup setup = setups.getOrDefault(first, MotionSetup.ALWAYS);
            List<BlockPos> cells = new ArrayList<>(chosen);
            if (edits.get(first) == Motion.CHANGE && !setup.steps().isEmpty()) { // a change: a bar for each step, keyframe to keyframe
                int count = setup.steps().size();
                for (int i = 0; i < count && i < 12; i++) {
                    int index = i;
                    int end = i + 1 < count ? setup.time(i + 1) : setup.until();
                    tracks.add(new Track("Step " + (i + 1) + ": " + stepName(setup.steps().get(i)), setup.time(i), end, ACCENT,
                            (from, until) -> {
                                for (BlockPos cell : cells) {
                                    MotionSetup own = setups.getOrDefault(cell, MotionSetup.ALWAYS);
                                    if (edits.get(cell) != Motion.CHANGE || own.steps().size() != count) continue;
                                    own = own.withStepTime(index, from);
                                    own = index + 1 < count ? own.withStepTime(index + 1, until) : own.withTimes(own.from(), until);
                                    putSetup(cell, own);
                                }
                                stillDirty = true;
                            }));
                }
                return tracks;
            }
            tracks.add(new Track(chosen.size() == 1 ? label(edits.get(first)) : chosen.size() + " blocks", setup.from(), setup.until(), ACCENT,
                    (from, until) -> {
                        for (BlockPos cell : cells) putSetup(cell, setups.getOrDefault(cell, MotionSetup.ALWAYS).withTimes(from, until));
                        stillDirty = true;
                    }));
            return tracks;
        }
        BlockPos at = spot;
        StudioMark mark = at == null ? null : marks.get(at);
        if (mark == null) return tracks;
        StudioMark.Times t = mark.times();
        tracks.add(new Track(capital(mark.kind().getSerializedName()), t.showFrom(), t.showUntil(), GOOD,
                (from, until) -> marks.computeIfPresent(at, (cell, m) -> m.withTimes(new StudioMark.Times(from, until, m.times().leave(), m.times().arrive())))));
        if (mark.kind() == StudioMark.Kind.ITEM && mark.items().isEmpty() && mark.to().isPresent()) {
            tracks.add(new Track("Travels", t.leave(), t.arrive(), TRAVEL_COLOR,
                    (from, until) -> marks.computeIfPresent(at, (cell, m) -> m.withTimes(new StudioMark.Times(m.times().showFrom(), m.times().showUntil(), from, until)))));
        }
        for (int i = 0; i < mark.items().size(); i++) {
            int index = i;
            StudioMark.ItemShow show = mark.show(i);
            tracks.add(new Track(entryName(mark.items().get(i)).getString(), show.from(), show.until(),
                    show.style() == StudioMark.Style.TRAVEL ? TRAVEL_COLOR : ITEM_COLOR,
                    (from, until) -> marks.computeIfPresent(at, (cell, m) -> {
                        List<StudioMark.ItemShow> shows = new ArrayList<>();
                        for (int j = 0; j < m.items().size(); j++) shows.add(m.show(j));
                        if (index < shows.size()) shows.set(index, new StudioMark.ItemShow(from, until, shows.get(index).style()));
                        return m.withItems(m.items(), shows);
                    })));
        }
        int room = Math.max(1, (viewY1() - viewY0() - 80) / TRACK); // (the view must still show the machine)
        return tracks.size() > room ? new ArrayList<>(tracks.subList(0, room)) : tracks;
    }

    private static final int TRAVEL_COLOR = 0xFF66B3FF, ITEM_COLOR = 0xFFFFB866;

    private int barLeft() { return viewX0() + 4; }
    private int barRight() { return viewX1() - 4; }
    private int trackTop(int count) { return viewY1() - 12 - count * TRACK; }
    private int xOf(int percent) { return barLeft() + Math.round((barRight() - barLeft()) * percent / 100.0F); }
    private int percentAt(double x) { return Mth.clamp((int) Math.round((x - barLeft()) * 100.0 / (barRight() - barLeft())), 0, 100); }

    /** The track under the mouse (its index), or -1. */
    private int trackUnder(List<Track> tracks, double x, double y) {
        int top = trackTop(tracks.size());
        if (x < barLeft() - 3 || x > barRight() + 3 || y < top || y >= top + tracks.size() * TRACK) return -1;
        return (int) ((y - top) / TRACK);
    }

    /** Where a keyframe dragged to {@code percent} lands: on the player or another keyframe if it is that close. */
    private int snapped(int percent, List<Track> tracks, int skip) {
        List<Integer> marksAt = new ArrayList<>(List.of(0, 100, Math.round(progress(0) * 100)));
        for (int i = 0; i < tracks.size(); i++) {
            if (i == skip) continue;
            marksAt.add(tracks.get(i).from());
            marksAt.add(tracks.get(i).until());
        }
        int best = percent, bestGap = SNAP + 1;
        for (int mark : marksAt) {
            int gap = Math.abs(xOf(mark) - xOf(percent));
            if (gap < bestGap) {
                best = mark;
                bestGap = gap;
            }
        }
        return best;
    }

    private int draggedTrack = -1, draggedPart;  // which track is held, and by what: 0 its start, 1 its end, 2 the whole bar
    private int grabbedAt;                       // where the whole bar was grabbed (percent of the work)

    /** The preview's time for a point of the work: the pretend operation lasts OPERATION ticks. */
    private static String seconds(int percent) {
        return String.format(Locale.ROOT, "%.1fs", percent * OPERATION / 100.0F / 20.0F);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            List<Track> tracks = tracks();
            int held = trackUnder(tracks, event.x(), event.y());
            if (held >= 0) {
                Track track = tracks.get(held);
                int x = (int) event.x(), start = xOf(track.from()), end = xOf(track.until());
                if (Math.abs(x - start) <= 3 && Math.abs(x - start) <= Math.abs(x - end)) draggedPart = 0;
                else if (Math.abs(x - end) <= 3) draggedPart = 1;
                else if (x > start && x < end) draggedPart = 2;
                else { // beside the bar: the player goes there
                    scrubbing = true;
                    if (playing) {
                        playing = false;
                        rebuildWidgets();
                    }
                    scrub(event.x());
                    return true;
                }
                draggedTrack = held;
                grabbedAt = percentAt(event.x());
                return true;
            }
        }
        int icon = iconUnder(event.x(), event.y());
        if (icon >= 0 && event.button() == 0) { // the anchor's item list, to change them
            pickItems();
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
        if (tab == Tab.BUILD && event.button() != 0 && inView(event.x(), event.y())) { // right: take out, middle: that block
            StudioView.Hit hit = under(event.x(), event.y());
            if (hit != null && event.button() == 1) takeOut(hit.cell());
            if (hit != null && event.button() == 2 && looks.get(hit.cell()) != null) {
                brush = looks.get(hit.cell());
                refreshButtons();
            }
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
        if (draggedTrack >= 0) {
            List<Track> tracks = tracks();
            if (draggedTrack >= tracks.size()) {
                draggedTrack = -1;
                return true;
            }
            Track track = tracks.get(draggedTrack);
            int at = snapped(percentAt(event.x()), tracks, draggedTrack);
            switch (draggedPart) {
                case 0 -> track.set().accept(Math.min(at, track.until()), track.until());
                case 1 -> track.set().accept(track.from(), Math.max(at, track.from()));
                default -> {
                    int length = track.until() - track.from();
                    int from = Mth.clamp(track.from() + percentAt(event.x()) - grabbedAt, 0, 100 - length);
                    int start = snapped(from, tracks, draggedTrack), end = snapped(from + length, tracks, draggedTrack);
                    if (start != from) from = start; // (the start snaps first, else the end)
                    else if (end != from + length) from = Mth.clamp(end - length, 0, 100 - length);
                    grabbedAt += from - track.from();
                    track.set().accept(from, from + length);
                }
            }
            refreshButtons();
            return true;
        }
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
        if (draggedTrack >= 0 && event.button() == 0) {
            draggedTrack = -1;
            refreshButtons();
            return true;
        }
        if (scrubbing && event.button() == 0) {
            scrubbing = false;
            return true;
        }
        if (dragging && event.button() == 0) {
            dragging = false;
            if (dragged < 3) {
                StudioView.Hit hit = under(event.x(), event.y());
                if (tab == Tab.BUILD) {
                    if (hit != null) putIn(hit.cell(), hit.face());
                } else if (tab != Tab.ANCHORS) choose(hit == null ? null : hit.cell(), event.hasShiftDown(), event.hasControlDown());
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
        graphics.submitPictureInPictureRenderState(new StudioRenderState(stillMesh(), pieces(a), figures(a), items(a), coded(a), center(), yaw, pitch,
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
            if (says != null) graphics.text(font, says, x0 + 4, y0 + 16, TEXT, true);
        }
        int barLeft = x0 + 4, barRight = x1 - 4, filled = barLeft + Math.round((barRight - barLeft) * progress(a));
        graphics.fill(barLeft, y1 - 6, barRight, y1 - 3, EDGE);
        graphics.fill(barLeft, y1 - 6, filled, y1 - 3, ACCENT);
        graphics.fill(filled - 1, y1 - 8, filled + 1, y1 - 1, TEXT);
        // The timeline: a track per timed thing, with the player's line through them
        List<Track> tracks = tracks();
        int tracksTop = trackTop(tracks.size());
        if (!tracks.isEmpty()) graphics.fill(x0 + 1, tracksTop - 2, x1 - 1, y1 - 10, 0xC0101018);
        int hoveredTrack = trackUnder(tracks, mouseX, mouseY);
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            int ty = tracksTop + i * TRACK, start = xOf(track.from()), end = Math.max(xOf(track.until()), start + 2);
            graphics.fill(barLeft, ty + 4, barRight, ty + 5, EDGE);
            graphics.fill(start, ty + 1, end, ty + TRACK - 2, (track.color() & 0x00FFFFFF) | (i == draggedTrack || i == hoveredTrack ? 0xFF000000 : 0xB0000000));
            graphics.fill(start, ty, start + 2, ty + TRACK - 1, TEXT); // its keyframes: where it starts and ends
            graphics.fill(end - 2, ty, end, ty + TRACK - 1, TEXT);
            String called = track.name();
            int room = end - start - 6;
            while (!called.isEmpty() && font.width(called) > room) called = called.substring(0, called.length() - 1);
            if (!called.isEmpty()) graphics.text(font, called, start + 4, ty + 2, 0xFF101018, false);
        }
        graphics.fill(filled - 1, tracks.isEmpty() ? y1 - 8 : tracksTop - 2, filled + 1, y1 - 1, TEXT); // the player
        String work = seconds(Math.round(progress(a) * 100)) + " / " + seconds(100);
        graphics.text(font, work, barRight - font.width(work), (tracks.isEmpty() ? y1 : tracksTop) - 12, FAINT, false);
        if (!tracks.isEmpty() && hoveredTrack < 0 && draggedTrack < 0 && inView(mouseX, mouseY) && mouseY >= tracksTop - 14) {
            graphics.text(font, "Drag a bar or its ends along the work", x0 + 4, tracksTop - 12, FAINT, false);
        }
        if (pickingTo) graphics.text(font, "Click the spot it travels to", x0 + 4, y0 + 4, WARN, true);

        panel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int icon = iconUnder(mouseX, mouseY);
        if (icon >= 0 && spot != null && marks.get(spot) != null) {
            StudioMark.ItemShow show = marks.get(spot).show(icon);
            graphics.setTooltipForNextFrame(font, List.of(entryName(marks.get(spot).items().get(icon)),
                    Component.literal(capital(show.style().getSerializedName()) + ", " + seconds(show.from()) + " to " + seconds(show.until()))
                            .withStyle(net.minecraft.ChatFormatting.GRAY),
                    Component.literal("Click: the item list").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)), java.util.Optional.empty(), mouseX, mouseY);
        } else if (hoveredTrack >= 0 && draggedTrack < 0) {
            Track track = tracks.get(hoveredTrack);
            graphics.setTooltipForNextFrame(font, List.of(Component.literal(track.name()),
                    Component.literal(seconds(track.from()) + " to " + seconds(track.until())).withStyle(net.minecraft.ChatFormatting.GRAY),
                    Component.literal("Drag its ends or the bar; they snap to the player").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)),
                    java.util.Optional.empty(), mouseX, mouseY);
        } else if (draggedTrack >= 0 && draggedTrack < tracks.size()) {
            Track track = tracks.get(draggedTrack);
            graphics.setTooltipForNextFrame(font, List.of(Component.literal(seconds(track.from()) + " to " + seconds(track.until()))),
                    java.util.Optional.empty(), mouseX, mouseY);
        }
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
        if (mark.kind() == StudioMark.Kind.SOUND && id != null) {
            return id.getPath().replace('_', ' ') + (mark.yaw() == 0 ? "" : ", every " + seconds100(mark.yaw()));
        }
        String name = id == null ? mark.what() : id.getPath();
        if (mark.kind() == StudioMark.Kind.ITEM && !mark.items().isEmpty()) {
            name = mark.items().size() == 1 ? entryName(mark.items().getFirst()).getString() : mark.items().size() + " items in turn";
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
            y = LIST_TOP + (motionRows + 2) * ROW + 6;
            if (!chosen.isEmpty()) { // when the (first) chosen block's motion plays, and what a change sets
                BlockPos cell = chosen.iterator().next();
                MotionSetup setup = setups.getOrDefault(cell, MotionSetup.ALWAYS);
                if (edits.containsKey(cell)) {
                    graphics.text(font, setup.always() ? "All along the work" : seconds(setup.from()) + " to " + seconds(setup.until()), tx, y, TEXT, false);
                    y += 11;
                    if (!setup.also().isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (Motion other : setup.also()) names.add(label(other));
                        graphics.text(font, clip("With: " + String.join(", ", names)), tx, y, ACCENT, false);
                        y += 11;
                    }
                }
                for (int i = 0; i < setup.steps().size(); i++) {
                    if (y > viewY1() - 30) break;
                    graphics.text(font, clip(seconds(setup.time(i)) + ": " + stepName(setup.steps().get(i))), tx, y, ACCENT, false);
                    y += 10;
                }
            }
            String when = switch (rules.motionWhen()) {
                case IDLE -> "Plays while it rests";
                case WORKING -> "Plays while it works";
                default -> "Plays all the time";
            };
            graphics.text(font, when, tx, y, playing ? GOOD : FAINT, false);
            y += 14;
            help = List.of("Drag: turn, wheel: zoom", "Click: choose a block", "Shift+click: add one", "Ctrl+click: all alike",
                    "Greyed: it can't do that", "Space: play / pause");
        } else if (tab == Tab.BUILD) {
            int addedCount = 0, takenCount = 0;
            for (BlockPos cell : looks.keySet()) if (!builtFrom.containsKey(cell.offset(min))) addedCount++;
            for (BlockPos offset : builtFrom.keySet()) if (!looks.containsKey(offset.subtract(min))) takenCount++;
            graphics.text(font, "Build it here", tx, ty, ACCENT, false);
            graphics.text(font, clip(size.getX() + " x " + size.getY() + " x " + size.getZ() + (building == null ? ""
                    : " (at most " + building.maxWidth() + " x " + building.maxHeight() + " x " + building.maxWidth() + ")")), tx, ty + 12, FAINT, false);
            y = LIST_TOP + 2 * ROW + 4;
            if (addedCount + takenCount > 0) {
                graphics.text(font, clip("+" + addedCount + " blocks, -" + takenCount + ": Save builds it"), tx, y, WARN, false);
                y += 12;
            }
            if (buildProblem != null) {
                for (String line : wrap(buildProblem)) {
                    graphics.text(font, line, tx, y, WARN, false);
                    y += 10;
                }
                y += 2;
            }
            help = List.of("Click a side: put a block", "Right click: take it out", "Middle click: pick that block",
                    "Every block becomes a part", "of the machine (Save)", "Drag: turn, wheel: zoom", "Hide layers to reach inside");
        } else if (tab == Tab.SHAPE) {
            if (chosen.isEmpty()) {
                graphics.text(font, "Click a block", tx, ty, ACCENT, false);
                graphics.text(font, poses.size() + " reshaped", tx, ty + 12, FAINT, false);
            } else {
                BlockPos cell = chosen.iterator().next();
                BlockPose pose = poses.getOrDefault(cell, BlockPose.NONE);
                graphics.text(font, clip(chosen.size() == 1 ? looks.get(cell).getBlock().getName().getString() : chosen.size() + " blocks chosen"),
                        tx, ty, ACCENT, false);
                String how = pose.hidden() ? "Hidden" : pose.isNone() ? "As built"
                        : "Size " + Math.round(pose.scale() * 100) + "%, moved " + round1(pose.offset().length());
                graphics.text(font, clip(how), tx, ty + 12, movable.containsAll(chosen) ? FAINT : WARN, false);
                if (!movable.containsAll(chosen)) graphics.text(font, "The core can't be reshaped", tx, ty + 24, WARN, false);
            }
            graphics.text(font, "Whole machine:", tx, LIST_TOP + 5 * ROW + 3, TEXT, false);
            y = LIST_TOP + 7 * ROW + 18;
            help = List.of("For the look only: the", "blocks stay where they", "are (and are mined there)", "Click: choose a block",
                    "Shift+click: add one", "Ctrl+click: all alike", "Hidden: a faint mark");
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
                    : "Shows " + seconds(mark.times().showFrom()) + " to " + seconds(mark.times().showUntil());
            graphics.text(font, when, tx, y, mark == null ? FAINT : TEXT, false);
            y = LIST_TOP + 7 * ROW + 18;
            if (mark != null && mark.kind() == StudioMark.Kind.ITEM) {
                int top = itemsY();
                y = itemList(graphics, mark, tx, top);
                if (mark.items().isEmpty()) graphics.text(font, "Floats the input", tx, y, FAINT, false);
                y += 12;
                if (mark.to().isPresent()) {
                    graphics.text(font, "Travels " + seconds(mark.times().leave()) + " to " + seconds(mark.times().arrive()), tx, y, TEXT, false);
                    y += 12;
                }
                if (!mark.items().isEmpty()) {
                    graphics.text(font, "Lit: showing now. Their", tx, y, FAINT, false);
                    graphics.text(font, "times: the timeline. Click", tx, y + 10, FAINT, false);
                    graphics.text(font, "one: the item list", tx, y + 20, FAINT, false);
                    y += 32;
                }
            }
            help = List.of("Click a side of a block:", "the spot in front of it", "Then Fx, a Mob, an Item", "or a Sound",
                    "Click an anchor: change it", "Drag the work bar, then", "Appear / Vanish here", "Hide layers to reach inside");
        }
        for (String line : help) {
            if (y > viewY1() - 10) break;
            graphics.text(font, line, tx, y, FAINT, false);
            y += 10;
        }
    }

    /** Draws an item anchor's items in a row, each with when it comes (the one showing now lit); returns the y under them. */
    private int itemList(GuiGraphicsExtractor graphics, StudioMark mark, int x, int top) {
        List<Integer> now = mark.shownItems(progress(0));
        for (int i = 0; i < mark.items().size(); i++) {
            int ix = x + (i % ICONS_PER_ROW) * 18, iy = top + (i / ICONS_PER_ROW) * ITEM_ROW;
            graphics.fill(ix - 1, iy - 1, ix + 17, iy + 17, now.contains(i) ? ACCENT : EDGE);
            drawEntry(graphics, mark.items().get(i), ix, iy);
        }
        return top + (mark.items().isEmpty() ? 0 : ((mark.items().size() + ICONS_PER_ROW - 1) / ICONS_PER_ROW) * ITEM_ROW + 2);
    }

    private static String round1(double value) {
        return Math.round(value * 100) / 100.0 + "";
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
        return poses.containsKey(cell) || anyMoves(cell) || (!hideFile && fileMoved.contains(cell) && !edits.containsKey(cell));
    }

    /**
     * How long ago (ticks) a block move of the machine's own file last started, at preview time {@code time}, or -1
     * if none is playing: the pretend work is always "working" here, an operation every {@link #OPERATION} ticks.
     */
    private float fileMoveAge(Effect.BlockMove move, float time) {
        com.lealex.alchyx.animation.Timing timing = move.timing();
        float duration = Keyframe.duration(move.keyframes());
        float started;
        if (timing.when().continuous()) {
            if (!timing.when().holds(true)) return -1;
            int every = Math.max(1, timing.interval());
            float since = time - timing.delay();
            if (since < 0) return -1;
            started = time - (since % every);
            if (timing.windowed()) { // only the starts inside its window count: the last of them
                for (int tries = 0; tries < 400 && !timing.within(true, (started % OPERATION) / OPERATION); tries++) started -= every;
                if (!timing.within(true, (started % OPERATION) / OPERATION)) return -1;
            }
        } else { // "start" (or its keyframe) and "finish": once an operation
            float opStart = (float) Math.floor(time / OPERATION) * OPERATION;
            float at = timing.when() == com.lealex.alchyx.animation.Trigger.START && timing.windowed() ? opStart + timing.from() * OPERATION : opStart;
            started = at + timing.delay();
            if (started > time) started -= OPERATION;
        }
        float age = time - started;
        return age >= 0 && age <= duration ? age : -1;
    }

    /** The machine's own file's block moves (a piston striking...), as they stand at preview time {@code time}. */
    private void fileMovePieces(List<StudioRenderState.Piece> pieces, float time) {
        if (hideFile) return;
        Set<BlockPos> shownApart = new java.util.HashSet<>();
        for (FileMove file : fileMoves) {
            BlockPos cell = file.cell();
            BlockState look = looks.get(cell);
            if (look == null || cell.getY() > top || edits.containsKey(cell)) continue;
            Effect.BlockMove move = file.move().effect();
            float age = playing || clock > 0 ? fileMoveAge(move, time) : -1;
            if (fileMoved.contains(cell) && shownApart.add(cell) && age < 0) { // the block itself, at rest
                addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(look), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
            }
            if (age < 0) continue;
            if (!move.hide() && fileMoved.contains(cell)) { // the block itself, swapped while the move lasts
                addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(BlockLook.apply(look, move.swap())), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
            }
            Keyframe pose = Keyframe.at(move.keyframes(), age);
            BlockState drawn = move.draw().map(com.lealex.alchyx.animation.BlockLook::build).orElse(look);
            Direction d = file.move().direction();
            if (move.align()) drawn = com.lealex.alchyx.client.animation.AnimationEngine.facing(drawn, d);
            drawn = BlockLook.apply(drawn, Keyframe.stateAt(move.keyframes(), age));
            Vec3 at = Vec3.atLowerCornerOf(cell).add(d.getStepX() * pose.move(), d.getStepY() * pose.move(), d.getStepZ() * pose.move())
                    .add(com.lealex.alchyx.client.animation.AnimationEngine.turned(pose.offset(), file.move().rotation()));
            addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(drawn), at, pose.rotation(), pose.scale()));
        }
    }

    /** What a cell looks like now in the preview: its own look, or the step a change of look is at. */
    private BlockState shownLook(BlockPos cell, BlockState look) {
        Motion motion = lookMotion(cell);
        if (motion == null) return look;
        MotionSetup setup = setups.getOrDefault(cell, MotionSetup.ALWAYS);
        List<Map<String, String>> steps = motion == edits.get(cell) ? motion.steps(look, setup) : motion.steps(look);
        float progress = progress(0);
        if (steps.isEmpty() || !setup.plays(progress)) return look;
        if (motion == Motion.CHANGE) { // each step from its keyframe
            int at = setup.stepAt(progress);
            return at < 0 ? look : BlockLook.apply(look, steps.get(at));
        }
        float span = (setup.until() - setup.from()) / 100.0F;
        float local = span < 1e-4F ? 1 : (progress - setup.from() / 100.0F) / span;
        int step = Mth.clamp((int) (local * steps.size()), 0, steps.size() - 1); // through its time, like on the machine
        return BlockLook.apply(look, steps.get(step));
    }

    /** Every motion a block plays: its own, then the others played with it. */
    private List<Motion> allMotions(BlockPos cell) {
        Motion motion = edits.get(cell);
        return motion == null ? List.of() : setups.getOrDefault(cell, MotionSetup.ALWAYS).all(motion);
    }

    private boolean anyMoves(BlockPos cell) {
        return !moves(cell).loops().isEmpty();
    }

    /** What moves a block plays (its motions' and mods' moves), as on the machine. */
    private Motion.Moves moves(BlockPos cell) {
        Motion motion = edits.get(cell);
        BlockState look = looks.get(cell);
        if (motion == null || look == null) return new Motion.Moves(List.of(), "up", null);
        return Motion.moves(motion, setups.getOrDefault(cell, MotionSetup.ALWAYS), look);
    }

    /** The motion that changes the block's own look (light, fill, change...), if it plays one. */
    private @Nullable Motion lookMotion(BlockPos cell) {
        for (Motion motion : allMotions(cell)) if (!motion.moves()) return motion;
        return null;
    }

    /** The blocks that stand still now, as one mesh (made again when the layers, the motions, play or a step change). */
    private BoxMesh stillMesh() {
        int step = (int) (progress(0) * 24); // (fine enough for the steps of any even change of look)
        for (Map.Entry<BlockPos, Motion> edit : edits.entrySet()) { // a change's keyframes, wherever they are
            if (edit.getValue() == Motion.CHANGE) step = step * 31 + setups.getOrDefault(edit.getKey(), MotionSetup.ALWAYS).stepAt(progress(0));
        }
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
                if (look == null || cell.getY() > top || !anyMoves(cell)) continue;
                BlockState shown = shownLook(cell, look);
                if (!setups.getOrDefault(cell, MotionSetup.ALWAYS).plays(progress(partialTicks))) { // outside its time: at rest
                    addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(shown), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
                    continue;
                }
                if (allMotions(cell).contains(Motion.STRIKE) && Motion.isPiston(look)) {
                    piston(pieces, cell, look, time);
                    continue;
                }
                // One motion, or several played together (a drill strikes and spins), mods' too
                Motion.Moves moves = moves(cell);
                List<Keyframe> frames = moves.loops().size() == 1 ? moves.loops().getFirst() : Motion.combineLoops(moves.loops());
                float loop = Math.max(1, Keyframe.duration(frames));
                Keyframe pose = Keyframe.at(frames, time % loop);
                shown = BlockLook.apply(shown, Keyframe.stateAt(frames, time % loop));
                Direction d = direction(moves.direction(), cell, look);
                Vec3 at = Vec3.atLowerCornerOf(cell).add(d.getStepX() * pose.move(), d.getStepY() * pose.move(), d.getStepZ() * pose.move())
                        .add(pose.offset());
                addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(shown), at, pose.rotation(), pose.scale()));
            }
        }
        fileMovePieces(pieces, time);
        for (Map.Entry<BlockPos, BlockPose> posed : poses.entrySet()) { // reshaped blocks standing still
            BlockPos cell = posed.getKey();
            BlockState look = looks.get(cell);
            if (look == null || cell.getY() > top || anyMoves(cell)
                    || (!hideFile && fileMoved.contains(cell) && !edits.containsKey(cell))) continue;
            if (posed.getValue().hidden()) {
                if (tab == Tab.SHAPE) pieces.add(mark(cell, HOVER_MARK, 0.5F)); // (so it can be chosen, and shown again)
                continue;
            }
            addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(shownLook(cell, look)), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
        }
        if (tab == Tab.BUILD && hovered != null) { // the block pointed at, and where a click puts one
            pieces.add(mark(hovered.cell(), HOVER_MARK, 1.05F));
            BlockPos front = hovered.cell().relative(hovered.face());
            if (!looks.containsKey(front)) pieces.add(new StudioRenderState.Piece(pieceMesh(brushFor(hovered.face())), Vec3.atLowerCornerOf(front), Vec3.ZERO, 0.6F));
        }
        if (tab != Tab.ANCHORS && tab != Tab.BUILD) {
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
            if (mark.kind() == StudioMark.Kind.SOUND) {
                pieces.add(new StudioRenderState.Piece(pieceMesh(SOUND_MARK), Vec3.atLowerCornerOf(mark.offset()), new Vec3(0, time * 2, 0), 0.3F));
            }
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
            for (int index : mark.shownItems(progress)) { // each one in its time, like on the machine
                net.minecraft.world.item.ItemStack stack = icon(mark.items().get(index)); // (a display shows its stand-in)
                if (stack.isEmpty()) continue;
                StudioMark.ItemShow show = mark.show(index);
                net.minecraft.client.renderer.item.ItemStackRenderState state = new net.minecraft.client.renderer.item.ItemStackRenderState();
                Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, stack,
                        net.minecraft.world.item.ItemDisplayContext.GROUND, level, null, index);
                Vec3 at = Vec3.atCenterOf(mark.offset());
                boolean travels = show.style() == StudioMark.Style.TRAVEL && mark.to().isPresent();
                boolean spins = show.style() == StudioMark.Style.SPIN || (show.style() == StudioMark.Style.TRAVEL && !travels);
                double bob = show.style() == StudioMark.Style.BOB ? 0.12 : spins ? 0.05 : 0;
                if (travels) at = at.lerp(Vec3.atCenterOf(mark.to().get()), show.travel(progress));
                else at = at.add(0, playing ? Math.sin(time / 10.0) * bob : 0, 0);
                items.add(new StudioRenderState.ItemPiece(state, at.add(0, -0.25, 0), spins ? time * 4 : 0, 1.0F));
            }
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
            addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(look), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
            return;
        }
        Direction facing = direction(Motion.STRIKE, cell, look);
        addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(BlockLook.apply(look, Map.of("extended", "true"))), Vec3.atLowerCornerOf(cell), Vec3.ZERO, 1));
        Keyframe pose = Keyframe.at(frames, elapsed);
        Map<String, String> state = new HashMap<>(Keyframe.stateAt(frames, elapsed));
        state.put("type", look.is(Blocks.STICKY_PISTON) ? "sticky" : "normal");
        state.put("facing", facing.getSerializedName());
        BlockState head = BlockLook.apply(Blocks.PISTON_HEAD.defaultBlockState(), state);
        Vec3 at = Vec3.atLowerCornerOf(cell).add(facing.getStepX() * pose.move(), facing.getStepY() * pose.move(), facing.getStepZ() * pose.move());
        addPiece(pieces, cell, new StudioRenderState.Piece(pieceMesh(head), at, Vec3.ZERO, 1));
    }

    /** Adds a piece of the block at {@code cell}, reshaped as the Shape tab says (or not at all, hidden). */
    private void addPiece(List<StudioRenderState.Piece> pieces, BlockPos cell, StudioRenderState.Piece piece) {
        BlockPose pose = poses.get(cell);
        if (pose == null) {
            pieces.add(piece);
            return;
        }
        if (pose.hidden()) return;
        pieces.add(new StudioRenderState.Piece(piece.mesh(), piece.at().add(pose.offset()), piece.rotation().add(pose.rotation()),
                piece.size() * pose.scale()));
    }

    private StudioRenderState.Piece mark(BlockPos cell, BlockState glass, float size) {
        return new StudioRenderState.Piece(pieceMesh(glass), Vec3.atLowerCornerOf(cell), Vec3.ZERO, size);
    }

    /** The effects mods draw at "Mod" anchors, through their time. */
    private List<StudioRenderState.CodedPiece> coded(float partialTicks) {
        List<StudioRenderState.CodedPiece> list = new ArrayList<>();
        Vec3 lit = Minecraft.getInstance().player == null ? Vec3.ZERO : Minecraft.getInstance().player.position();
        for (StudioMark mark : marks.values()) {
            if (mark.kind() != StudioMark.Kind.EFFECT || mark.offset().getY() > top || !mark.times().shows(progress(partialTicks))) continue;
            Identifier id = mark.id();
            com.lealex.alchyx.client.animation.CodedFigure figure = id == null ? null : com.lealex.alchyx.client.animation.Figures.get(id);
            if (figure == null) continue;
            com.lealex.alchyx.client.animation.CodedFigure.Context context = new com.lealex.alchyx.client.animation.CodedFigure.Context(
                    net.minecraft.world.item.ItemStack.EMPTY, playing, clock + (playing ? partialTicks : 0),
                    net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, this, lit, mark.yaw(), partialTicks);
            list.add(new StudioRenderState.CodedPiece(new com.lealex.alchyx.client.animation.FigureDrawer.Prepared(null, figure, context,
                    mark.yaw(), 1.0F, 0.0F), new Vec3(mark.offset().getX() + 0.5, mark.offset().getY(), mark.offset().getZ() + 0.5)));
        }
        return list;
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
                // Its action and its way (a figure that walks to a spot goes there through its show time)
                float time = clock + (playing ? partialTicks : 0);
                Effect.FigureAction action = playing || clock > 0 ? mark.action() : Effect.FigureAction.STILL;
                Vec3 feet = new Vec3(mark.offset().getX() + 0.5, mark.offset().getY(), mark.offset().getZ() + 0.5);
                float yaw = mark.yaw();
                if (mark.to().isPresent()) {
                    Vec3 end = new Vec3(mark.to().get().getX() + 0.5, mark.to().get().getY(), mark.to().get().getZ() + 0.5);
                    StudioMark.Times t = mark.times();
                    float along = com.lealex.alchyx.client.animation.AnimationEngine.figureAlong(
                            new com.lealex.alchyx.animation.Timing(com.lealex.alchyx.animation.Trigger.ALWAYS, 1, 1, 0, Map.of(),
                                    com.lealex.alchyx.animation.Timing.Where.EVERYWHERE).between(t.showFrom() / 100F, t.showUntil() / 100F),
                            progress(partialTicks));
                    Vec3 way = end.subtract(feet);
                    feet = feet.add(way.scale(along));
                    if (way.lengthSqr() > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-way.x, way.z));
                }
                if (action == Effect.FigureAction.SPIN) yaw = Mth.wrapDegrees(yaw + time * 6);
                if (action == Effect.FigureAction.HOP) feet = feet.add(0, Math.abs(Mth.sin(time * 0.25F)) * 0.35F, 0);
                entity.setPos(lit.x, lit.y, lit.z);
                entity.xo = entity.xOld = lit.x;
                entity.yo = entity.yOld = lit.y;
                entity.zo = entity.zOld = lit.z;
                entity.setYRot(yaw);
                entity.yRotO = yaw;
                entity.tickCount = (int) clock;
                if (entity instanceof LivingEntity living) {
                    living.yBodyRot = living.yBodyRotO = yaw;
                    living.yHeadRot = living.yHeadRotO = yaw;
                }
                EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(entity, partialTicks);
                state.shadowPieces.clear();
                state.shadowRadius = 0;
                state.nameTag = null;
                com.lealex.alchyx.client.animation.FigureDrawer.act(state, action, time);
                figures.add(new Puppets.Prepared(state, (float) feet.x, (float) feet.y, (float) feet.z));
            } catch (Throwable e) { // a mob that can't be shown must never take the game down
                standIns.put(figureId, null);
                AlchyX.LOGGER.warn("AlchyX: can't show {} in the Machine Studio: {}", figureId, e.toString());
            }
        }
        // A spawner with a mob in its data: that mob turning inside, small, like the real one
        for (Map.Entry<BlockPos, BlockState> entry : looks.entrySet()) {
            BlockPos cell = entry.getKey();
            if (cell.getY() > top || !entry.getValue().is(Blocks.SPAWNER)) continue;
            Identifier mobId = Identifier.tryParse(dataOf(cell).getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", ""));
            if (mobId == null || mobId.getPath().isEmpty()) continue;
            Entity entity = standIns.computeIfAbsent(mobId, id -> create(level, id));
            if (entity == null) continue;
            try {
                entity.setPos(lit.x, lit.y, lit.z);
                entity.xo = entity.xOld = lit.x;
                entity.yo = entity.yOld = lit.y;
                entity.zo = entity.zOld = lit.z;
                entity.setYRot(0);
                entity.yRotO = 0;
                entity.tickCount = (int) clock;
                if (entity instanceof LivingEntity living) {
                    living.yBodyRot = living.yBodyRotO = 0;
                    living.yHeadRot = living.yHeadRotO = 0;
                }
                EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(entity, partialTicks);
                state.shadowPieces.clear();
                state.shadowRadius = 0;
                state.nameTag = null;
                float size = 0.53125F / Math.max(0.5F, Math.max(entity.getBbWidth(), entity.getBbHeight()));
                float spin = (clock + (playing ? partialTicks : 0)) * 6.0F;
                figures.add(new Puppets.Prepared(state, cell.getX() + 0.5F, cell.getY() + 0.2F, cell.getZ() + 0.5F, size, spin));
            } catch (Throwable e) {
                standIns.put(mobId, null);
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
        return direction(motion.direction(), cell, look);
    }

    /** A move's way: "facing" (the block's, else toward the core), or a direction ("up", "north"...). */
    private Direction direction(String way, BlockPos cell, BlockState look) {
        if (!"facing".equals(way)) {
            Direction named = Direction.byName(way);
            return named == null ? Direction.UP : named;
        }
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

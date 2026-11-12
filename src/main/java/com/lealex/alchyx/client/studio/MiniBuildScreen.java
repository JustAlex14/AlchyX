package com.lealex.alchyx.client.studio;

import com.lealex.alchyx.animation.MiniStructure;
import com.lealex.alchyx.animation.Motion;
import com.lealex.alchyx.client.animation.CodedFigure;
import com.lealex.alchyx.client.animation.FigureDrawer;
import com.lealex.alchyx.client.animation.MiniFigure;
import com.lealex.alchyx.client.animation.MiniHost;
import com.lealex.alchyx.client.miniature.BoxMesh;
import com.lealex.alchyx.miniature.BlockBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds a mini structure of the machine's own, in a small 3D view like the studio's: click a side to put the
 * block in, right click to take one out, middle click to pick that block; on the Motion tool, click a block to give
 * it the motion shown (again: none). Play shows it moving as it will in the machine (one operation every two
 * seconds here; in the machine, timed on its recipe).
 */
public class MiniBuildScreen extends Screen implements MiniHost {
    private static final int BACK = 0xE0101018, VIEW_BACK = 0xFF1A1A26, EDGE = 0xFF3C3550, ACCENT = 0xFFD9A7FF, TEXT = 0xFFE8E4F0,
            FAINT = 0xFF9A93AA, WARN = 0xFFFFB866;
    private static final int PANEL = 150, MARGIN = 8, TOP = 22, ROW = 20;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Screen parent;
    private final Consumer<MiniStructure.Own> done;
    private final @Nullable Runnable delete;
    private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private final Map<BlockPos, Motion> motions = new LinkedHashMap<>();
    private final List<Motion> motionChoices = new ArrayList<>();
    private String name;
    private BlockState brush = Blocks.STONE.defaultBlockState();
    private boolean motionTool, playing;
    private int motionIndex;
    private float yaw = (float) Math.PI / 5, pitch = -0.5F, zoom = 1.0F;
    private boolean dragging;
    private double dragged;
    private @Nullable String problem;
    private @Nullable BoxMesh mesh, empty;
    private MiniStructure.@Nullable Own shown;
    private StudioView.@Nullable Hit hovered;
    private @Nullable EditBox nameBox;
    private final long opened = System.currentTimeMillis();

    /**
     * @param start  the mini to change (a new one: one block)
     * @param done   gets the mini as built, on Done
     * @param delete takes the mini away (null for a new one)
     */
    public MiniBuildScreen(Screen parent, MiniStructure.Own start, Consumer<MiniStructure.Own> done, @Nullable Runnable delete) {
        super(Component.literal("Mini structure"));
        this.parent = parent;
        this.done = done;
        this.delete = delete;
        this.name = start.name();
        blocks.putAll(start.blocks());
        motions.putAll(start.motions());
        if (blocks.isEmpty()) blocks.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        for (Motion motion : Motion.values()) if (MiniStructure.plays(motion)) motionChoices.add(motion);
    }

    // ---- Layout ----

    private int viewX0() {
        return MARGIN;
    }

    private int viewY0() {
        return TOP;
    }

    private int viewX1() {
        return width - PANEL - MARGIN * 2;
    }

    private int viewY1() {
        return height - MARGIN - 12;
    }

    private boolean inView(double x, double y) {
        return x >= viewX0() && x < viewX1() && y >= viewY0() && y < viewY1();
    }

    private BlockPos[] box() {
        return MiniStructure.box(blocks.keySet());
    }

    private Vec3 center() {
        BlockPos[] box = box();
        return new Vec3((box[1].getX() - box[0].getX() + 1) / 2.0, (box[1].getY() - box[0].getY() + 1) / 2.0,
                (box[1].getZ() - box[0].getZ() + 1) / 2.0);
    }

    private float scale() {
        BlockPos[] box = box();
        int sx = box[1].getX() - box[0].getX() + 3, sy = box[1].getY() - box[0].getY() + 1, sz = box[1].getZ() - box[0].getZ() + 3;
        return Math.min(viewX1() - viewX0(), viewY1() - viewY0()) / (float) Math.sqrt(sx * sx + sy * sy + sz * sz) * zoom;
    }

    @Override
    protected void init() {
        int px = width - PANEL - MARGIN, py = TOP;
        nameBox = addRenderableWidget(new EditBox(font, px, py, PANEL, 18, Component.literal("Name")));
        nameBox.setMaxLength(32);
        nameBox.setValue(name);
        nameBox.setResponder(text -> name = text);
        py += ROW + 4;
        Button brushButton = addRenderableWidget(Button.builder(Component.literal(clip("Block: " + brush.getBlock().getName().getString())),
                b -> pickBrush()).bounds(px, py, PANEL, 18).build());
        brushButton.setTooltip(Tooltip.create(Component.literal("The block a click puts in: any block, modded ones too. Middle click a block: that one")));
        py += ROW;
        Button tool = addRenderableWidget(Button.builder(Component.literal(motionTool ? "Tool: Motion" : "Tool: Build"), b -> {
            motionTool = !motionTool;
            rebuildWidgets();
        }).bounds(px, py, PANEL, 18).build());
        tool.setTooltip(Tooltip.create(Component.literal("Build: click a side to put a block in, right click to take one out. Motion: click a block to give it the motion below")));
        py += ROW;
        Button motion = addRenderableWidget(Button.builder(Component.literal("Motion: " + motionChoices.get(motionIndex).getSerializedName()), b -> {
            motionIndex = Math.floorMod(motionIndex + (Minecraft.getInstance().hasShiftDown() ? -1 : 1), motionChoices.size());
            motionTool = true;
            rebuildWidgets();
        }).bounds(px, py, PANEL, 18).build());
        motion.setTooltip(Tooltip.create(Component.literal("The motion the Motion tool gives (Shift: the one before). It plays while the machine works, timed on its recipe")));
        py += ROW;
        addRenderableWidget(Button.builder(Component.literal(playing ? "Stop" : "Play"), b -> {
            playing = !playing;
            rebuildWidgets();
        }).bounds(px, py, PANEL, 18).build());
        py += ROW + 8;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish()).bounds(px, py, PANEL / 2 - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(px + PANEL / 2 + 2, py, PANEL / 2 - 2, 18).build());
        py += ROW;
        if (delete != null) {
            Button gone = addRenderableWidget(Button.builder(Component.literal("Delete this mini"), b -> {
                delete.run();
                onClose();
            }).bounds(px, py, PANEL, 18).build());
            gone.setTooltip(Tooltip.create(Component.literal("Takes it out of the machine, and every anchor showing it")));
        }
    }

    private static String clip(String text) {
        return text.length() > 24 ? text.substring(0, 23) + "." : text;
    }

    private void pickBrush() {
        minecraft.setScreen(new ItemPickerScreen(this, id -> {
            Identifier parsed = Identifier.tryParse(id);
            net.minecraft.world.item.Item item = parsed == null ? null : BuiltInRegistries.ITEM.getValue(parsed);
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) brush = blockItem.getBlock().defaultBlockState();
        }, true));
    }

    private BlockState brushFor(Direction face) {
        BlockState state = brush;
        if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, face);
        else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && face.getAxis().isHorizontal())
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, face);
        else if (state.hasProperty(BlockStateProperties.AXIS)) state = state.setValue(BlockStateProperties.AXIS, face.getAxis());
        return state;
    }

    /** The mini as built now. */
    private MiniStructure.Own current() {
        return new MiniStructure.Own(name.isBlank() ? "Mini" : name.trim(), Map.copyOf(blocks), Map.copyOf(motions));
    }

    private void finish() {
        MiniStructure.Own own = current().tidy();
        if (own != null) done.accept(own);
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- Building ----

    /** The cells in view units (their box's lowest corner at 0 0 0). */
    private List<BlockPos> viewCells() {
        BlockPos low = box()[0];
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos cell : blocks.keySet()) cells.add(cell.subtract(low));
        return cells;
    }

    private StudioView.@Nullable Hit under(double x, double y) {
        if (!inView(x, y)) return null;
        StudioView.Hit hit = StudioView.pick(x - (viewX0() + viewX1()) / 2.0, y - (viewY0() + viewY1()) / 2.0, scale(), yaw, pitch, center(), viewCells());
        return hit == null ? null : new StudioView.Hit(hit.cell().offset(box()[0]), hit.face());
    }

    private void putIn(BlockPos cell, Direction face) {
        BlockPos at = cell.relative(face);
        if (blocks.containsKey(at)) return;
        BlockPos[] box = box();
        BlockPos low = BlockPos.min(box[0], at), high = BlockPos.max(box[1], at);
        if (high.getX() - low.getX() >= MiniStructure.MAX_SIDE || high.getY() - low.getY() >= MiniStructure.MAX_SIDE
                || high.getZ() - low.getZ() >= MiniStructure.MAX_SIDE) {
            problem = "A mini is at most " + MiniStructure.MAX_SIDE + " blocks across";
            return;
        }
        blocks.put(at.immutable(), brushFor(face));
        problem = null;
        mesh = null;
    }

    private void takeOut(BlockPos cell) {
        if (blocks.size() <= 1) {
            problem = "A mini keeps at least one block";
            return;
        }
        blocks.remove(cell);
        motions.remove(cell);
        problem = null;
        mesh = null;
    }

    private void giveMotion(BlockPos cell) {
        BlockState look = blocks.get(cell);
        if (look == null) return;
        Motion motion = motionChoices.get(motionIndex);
        if (motions.get(cell) == motion) {
            motions.remove(cell);
        } else if (!motion.fits(look)) {
            problem = capital(motion.getSerializedName()) + " doesn't fit " + look.getBlock().getName().getString();
            return;
        } else {
            motions.put(cell, motion);
        }
        problem = null;
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1).replace('_', ' ');
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (inView(event.x(), event.y())) {
            setFocused(null);
            StudioView.Hit hit = under(event.x(), event.y());
            if (event.button() == 1) {
                if (hit != null) {
                    if (motionTool) motions.remove(hit.cell());
                    else takeOut(hit.cell());
                }
                return true;
            }
            if (event.button() == 2) {
                if (hit != null && blocks.get(hit.cell()) != null) {
                    brush = blocks.get(hit.cell());
                    rebuildWidgets();
                }
                return true;
            }
            if (event.button() == 0) {
                dragging = true;
                dragged = 0;
                return true;
            }
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
            if (dragged < 3) {
                StudioView.Hit hit = under(event.x(), event.y());
                if (hit != null) {
                    if (motionTool) giveMotion(hit.cell());
                    else putIn(hit.cell(), hit.face());
                }
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (inView(x, y)) {
            zoom = Mth.clamp(zoom * (float) Math.pow(1.15, scrollY), 0.4F, 4.0F);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    // ---- Drawing ----

    private BoxMesh mesh() {
        if (mesh == null || mesh.isStale()) {
            BlockPos[] box = box();
            BlockPos biome = Minecraft.getInstance().player == null ? BlockPos.ZERO : Minecraft.getInstance().player.blockPosition();
            mesh = BoxMesh.build(Minecraft.getInstance().level, biome, BlockBox.of(box[1].getX() - box[0].getX() + 1,
                    box[1].getY() - box[0].getY() + 1, box[1].getZ() - box[0].getZ() + 1, false, (x, y, z) -> {
                        BlockState look = blocks.get(box[0].offset(x, y, z));
                        return look == null ? AIR : look;
                    }), 60_000);
        }
        return mesh;
    }

    private float time(float partialTicks) {
        return (System.currentTimeMillis() - opened) / 50.0F;
    }

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
        BlockPos low = box()[0];
        List<StudioRenderState.Piece> pieces = new ArrayList<>();
        List<StudioRenderState.CodedPiece> coded = new ArrayList<>();
        BoxMesh still = mesh();
        if (playing) { // drawn as the machine will draw it, moving
            shown = current();
            MiniFigure figure = MiniFigure.forId(MiniStructure.ownId(0));
            Vec3 lit = Minecraft.getInstance().player == null ? Vec3.ZERO : Minecraft.getInstance().player.position();
            if (figure != null) {
                CodedFigure.Context context = new CodedFigure.Context(ItemStack.EMPTY, true, time(a), LightCoordsUtil.FULL_BRIGHT, this, lit,
                        0.0F, a, 1.0F);
                Vec3 foot = MiniStructure.foot(blocks.keySet()).subtract(Vec3.atLowerCornerOf(low));
                coded.add(new StudioRenderState.CodedPiece(new FigureDrawer.Prepared(null, figure, context, 0.0F, 1.0F, 0.0F), foot));
            }
            if (empty == null) empty = BoxMesh.build(Minecraft.getInstance().level, BlockPos.ZERO, BlockBox.empty(), 1);
            still = empty;
        } else if (hovered != null && !motionTool) { // where a click puts the block
            BlockPos front = hovered.cell().relative(hovered.face());
            if (!blocks.containsKey(front)) pieces.add(new StudioRenderState.Piece(pieceMesh(brushFor(hovered.face())),
                    Vec3.atLowerCornerOf(front.subtract(low)), Vec3.ZERO, 0.6F));
        }
        graphics.submitPictureInPictureRenderState(new StudioRenderState(still, pieces, List.of(), List.of(), coded, center(), yaw, pitch,
                x0, y0, x1, y1, scale(), graphics.peekScissorStack()));

        graphics.text(font, "Mini structure", MARGIN, 7, ACCENT, false);
        graphics.text(font, blocks.size() + " blocks, " + motions.size() + " moving", MARGIN + font.width("Mini structure") + 8, 7, FAINT, false);
        int px = width - PANEL - MARGIN, ty = TOP + ROW * 5 + 4 + 8 + ROW * 2 + 6;
        if (hovered != null) {
            BlockState look = blocks.get(hovered.cell());
            Motion motion = motions.get(hovered.cell());
            if (look != null) {
                graphics.text(font, clip(look.getBlock().getName().getString()), px, ty, TEXT, false);
                graphics.text(font, motion == null ? "Still" : "Motion: " + motion.getSerializedName(), px, ty + 11, FAINT, false);
            }
        }
        if (problem != null) graphics.text(font, problem, MARGIN, height - MARGIN - 9, WARN, false);
        else graphics.text(font, motionTool ? "Click: give the motion (again: none). Right click: none. Drag: turn the view"
                : "Click a side: put the block in. Right click: take out. Middle click: pick. Drag: turn the view", MARGIN, height - MARGIN - 9, FAINT, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private final Map<BlockState, BoxMesh> pieceMeshes = new java.util.HashMap<>();

    private BoxMesh pieceMesh(BlockState state) {
        BoxMesh piece = pieceMeshes.get(state);
        if (piece == null || piece.isStale()) {
            piece = BoxMesh.build(Minecraft.getInstance().level, BlockPos.ZERO, BlockBox.of(1, 2, 1, false, (x, y, z) -> y == 1 ? state : AIR), 10_000);
            pieceMeshes.put(state, piece);
        }
        return piece;
    }

    // ---- What the mini is timed on here: one operation every two seconds ----

    @Override
    public boolean miniWorking() {
        return true;
    }

    @Override
    public float miniProgress(float time) {
        float now = time(0);
        return (now % MiniStructure.LENGTH) / MiniStructure.LENGTH;
    }

    @Override
    public long miniOperationStart() {
        return (long) (time(0) / MiniStructure.LENGTH);
    }

    @Override
    public int miniOperations() {
        return (int) (time(0) / MiniStructure.LENGTH);
    }

    @Override
    public int miniOperationTicks() {
        return MiniStructure.LENGTH;
    }

    @Override
    public List<MiniStructure.Own> miniOwn() {
        return shown == null ? List.of() : List.of(shown);
    }
}

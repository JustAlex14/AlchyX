package com.lealex.alchyx.client.studio;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The data menu the Machine Studio opens for a block that keeps block entity data (a spawner, a banner, a modded
 * block...): its data as text (SNBT, like /data shows it), to change. The machine keeps it and draws the block with it
 * (a spawner shows that mob turning inside); it never goes back into a real block. A spawner also gets a mob box:
 * type an entity id (minecraft:blaze) and Set puts it in the data.
 */
public class DataEditScreen extends Screen {
    private static final int BACK = 0xE0101018, ACCENT = 0xFFD9A7FF, FAINT = 0xFF9A93AA, WARN = 0xFFFF7A7A, GOOD = 0xFF8BE39B;
    private static final int WIDE = 320;

    private final Screen parent;
    private final BlockState look;
    private final Consumer<CompoundTag> done;
    private String text;
    private @Nullable String problem;
    private @Nullable MultiLineEditBox box;
    private @Nullable EditBox mobBox;

    public DataEditScreen(Screen parent, BlockState look, CompoundTag start, Consumer<CompoundTag> done) {
        super(Component.literal("Block data"));
        this.parent = parent;
        this.look = look;
        this.done = done;
        this.text = start.isEmpty() ? "{}" : NbtUtils.prettyPrint(start, true);
    }

    private boolean spawner() {
        return look.is(Blocks.SPAWNER);
    }

    @Override
    protected void init() {
        int left = (width - WIDE) / 2, top = 34, bottom = height - 30;
        int boxTop = spawner() ? top + 24 : top;
        if (spawner()) {
            String typed = mobBox == null ? "" : mobBox.getValue();
            mobBox = addRenderableWidget(new EditBox(font, left, top, WIDE - 64, 18, Component.literal("Mob")));
            mobBox.setValue(typed);
            mobBox.setHint(Component.literal("A mob's id: minecraft:blaze"));
            mobBox.setMaxLength(128);
            Button set = addRenderableWidget(Button.builder(Component.literal("Pick mob..."), b -> {
                java.util.List<String> mobs = new java.util.ArrayList<>();
                for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) mobs.add(id.toString());
                mobs.sort(null);
                minecraft.setScreen(new IdPickerScreen(this, "Pick the spawner's mob", mobs, null, null, id -> {
                    if (mobBox != null) mobBox.setValue(id);
                    setMob();
                }));
            }).bounds(left + WIDE - 60, top - 1, 60, 20).build());
            set.setTooltip(Tooltip.create(Component.literal("The spawner shows this mob turning inside (or type its id and press Enter)")));
        }
        box = addRenderableWidget(MultiLineEditBox.builder().setX(left).setY(boxTop).setPlaceholder(Component.literal("{}"))
                .build(font, WIDE, Math.max(40, bottom - 30 - boxTop), Component.literal("Data")));
        box.setCharacterLimit(com.lealex.alchyx.block.ChamberShellBlock.MAX_DATA_BYTES);
        box.setValue(text);
        box.setValueListener(value -> {
            text = value;
            problem = null;
        });
        setInitialFocus(box);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish(true)).bounds(width / 2 - 154, height - 26, 100, 20).build());
        Button clear = addRenderableWidget(Button.builder(Component.literal("Empty"), b -> {
            text = "{}";
            problem = null;
            if (box != null) box.setValue(text);
        }).bounds(width / 2 - 50, height - 26, 100, 20).build());
        clear.setTooltip(Tooltip.create(Component.literal("No data: the block is drawn as it is (a spawner without a mob)")));
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(false)).bounds(width / 2 + 54, height - 26, 100, 20).build());
    }

    /** A spawner: the mob it shows (its "SpawnData"), put in the data as it stands. */
    private void setMob() {
        if (mobBox == null) return;
        Identifier id = Identifier.tryParse(mobBox.getValue().trim());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            problem = "No such mob: " + mobBox.getValue().trim();
            return;
        }
        CompoundTag tag = parsed();
        if (tag == null) return;
        CompoundTag entity = new CompoundTag();
        entity.putString("id", id.toString());
        CompoundTag spawnData = new CompoundTag();
        spawnData.put("entity", entity);
        tag.put("SpawnData", spawnData);
        text = NbtUtils.prettyPrint(tag, true);
        if (box != null) box.setValue(text);
        problem = null;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if ((event.key() == 257 || event.key() == 335) && mobBox != null && mobBox.isFocused()) {
            setMob();
            return true;
        }
        return super.keyPressed(event);
    }

    private @Nullable CompoundTag parsed() {
        try {
            return text.isBlank() ? new CompoundTag() : TagParser.parseCompoundFully(text);
        } catch (CommandSyntaxException e) {
            problem = e.getMessage();
            return null;
        }
    }

    private void finish(boolean keep) {
        if (keep) {
            CompoundTag tag = parsed();
            if (tag == null) return; // (the problem shows)
            if (tag.sizeInBytes() > com.lealex.alchyx.block.ChamberShellBlock.MAX_DATA_BYTES) {
                problem = "Too much data";
                return;
            }
            done.accept(tag);
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        finish(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACK);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int left = (width - WIDE) / 2;
        graphics.item(new net.minecraft.world.item.ItemStack(look.getBlock().asItem()), left, 6);
        graphics.text(font, look.getBlock().getName().getString() + ": its block data", left + 20, 6, ACCENT, false);
        graphics.text(font, "The machine keeps it and draws the block with it (never a real block)", left + 20, 17, FAINT, false);
        if (problem != null) {
            String shown = problem;
            while (font.width(shown) > WIDE && shown.length() > 1) shown = shown.substring(0, shown.length() - 1);
            graphics.text(font, shown, left, height - 40, WARN, false);
        } else {
            graphics.text(font, "SNBT, like /data shows it", left, height - 40, GOOD, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }
}

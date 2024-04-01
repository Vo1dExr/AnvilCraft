package dev.dubhe.anvilcraft.block.entity;

import dev.dubhe.anvilcraft.AnvilCraft;
import dev.dubhe.anvilcraft.block.AutoCrafterBlock;
import dev.dubhe.anvilcraft.block.ChuteBlock;
import dev.dubhe.anvilcraft.init.ModBlockEntities;
import dev.dubhe.anvilcraft.init.ModBlocks;
import dev.dubhe.anvilcraft.inventory.ChuteMenu;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

public class ChuteBlockEntity extends BaseMachineBlockEntity implements IFilterBlockEntity, Hopper {
    private int cooldown = 0;
    @Getter
    @Setter
    private boolean record = false;
    @Getter
    private final NonNullList<Boolean> disabled = this.getNewDisabled();
    @Getter
    private final NonNullList<ItemStack> filter = this.getNewFilter();

    public ChuteBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.CHUTE, pos, blockState, 9);
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        if (this.getDisabled().get(index)) return false;
        ItemStack itemStack1 = this.items.get(index);
        ItemStack itemStack2 = this.getFilter().get(index);
        if (itemStack2.isEmpty() || ItemStack.isSameItemSameTags(itemStack1, itemStack2)) return true;
        return super.canPlaceItem(index, stack);
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("block.anvilcraft.chute");
    }

    @Override
    protected @NotNull AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new ChuteMenu(containerId, inventory, this);
    }

    public static void tick(Level level, BlockPos pos, BlockEntity e) {
        if (!(e instanceof ChuteBlockEntity entity)) return;
        entity.cooldown -= 1;
        if (entity.cooldown < 0) entity.refreshCooldown();
        BlockState state = level.getBlockState(pos);
        if (!state.getValue(ChuteBlock.ENABLED)) return;
        if (!entity.isOnCooldown()) {
            ChuteBlockEntity.tryMoveItems(level, pos, state, entity, () -> ChuteBlockEntity.suckInItems(level, entity));
            entity.dropOrInsert(level, pos);
        }
    }

    public void dropOrInsert(Level level, BlockPos pos) {
        for (int i = this.items.size() - 1; i >= 0; i--) {
            ItemStack stack = this.items.get(i);
            if (stack.isEmpty()) continue;
            if (this.insertOrDropItem(this.getDirection(), level, pos, this, i, false, false, true)) return;
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.cooldown = tag.getInt("cooldown");
        this.loadTag(tag);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("cooldown", this.cooldown);
        this.saveTag(tag);
    }

    @SuppressWarnings("UnusedReturnValue")
    private static boolean tryMoveItems(@NotNull Level level, BlockPos pos, BlockState state, ChuteBlockEntity blockEntity, BooleanSupplier validator) {
        if (level.isClientSide) {
            return false;
        }
        if (!blockEntity.isOnCooldown() && state.getValue(HopperBlock.ENABLED)) {
            boolean bl = false;
            if (!blockEntity.isEmpty()) {
                bl = HopperBlockEntity.ejectItems(level, pos, state, blockEntity);
            }
            if (!blockEntity.inventoryFull()) {
                bl |= validator.getAsBoolean();
            }
            if (bl) {
                HopperBlockEntity.setChanged(level, pos, state);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static boolean suckInItems(Level level, Hopper hopper) {
        Container container = HopperBlockEntity.getSourceContainer(level, hopper);
        if (container != null) {
            Direction direction = Direction.DOWN;
            if (HopperBlockEntity.isEmptyContainer(container, direction)) {
                return false;
            }
            return HopperBlockEntity.getSlots(container, direction).anyMatch(i -> ChuteBlockEntity.tryTakeInItemFromSlot(hopper, container, i, direction));
        } else {
            BlockPos sourcePos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0D, hopper.getLevelZ());
            Storage<ItemVariant> source = ItemStorage.SIDED.find(level, sourcePos, Direction.DOWN);
            if (source != null) {
                long moved = StorageUtil.move(
                        source,
                        InventoryStorage.of(hopper, Direction.UP),
                        iv -> true,
                        64,
                        null
                );
                return moved >= 1;
            }
        }
        for (ItemEntity itemEntity : HopperBlockEntity.getItemsAtAndAbove(level, hopper)) {
            if (!HopperBlockEntity.addItem(hopper, itemEntity)) continue;
            return true;
        }
        return false;
    }

    private static boolean tryTakeInItemFromSlot(Hopper hopper, @NotNull Container container, int slot, Direction direction) {
        ItemStack itemStack = container.getItem(slot);
        if (!itemStack.isEmpty() && HopperBlockEntity.canTakeItemFromContainer(hopper, container, itemStack, slot, direction)) {
            ItemStack itemStack3 = HopperBlockEntity.addItem(container, hopper, itemStack, null);
            container.setItem(slot, itemStack3);
            return true;
        }
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean isOnCooldown() {
        return this.cooldown > 0;
    }

    private boolean inventoryFull() {
        for (ItemStack itemStack : this.items) {
            if (!itemStack.isEmpty() && itemStack.getCount() == itemStack.getMaxStackSize()) continue;
            return false;
        }
        return true;
    }

    @Override
    public Direction getDirection() {
        if (this.level == null) return Direction.UP;
        BlockState state = this.level.getBlockState(this.getBlockPos());
        if (state.is(ModBlocks.CHUTE)) return state.getValue(ChuteBlock.FACING);
        return Direction.UP;
    }

    @Override
    public void setDirection(Direction direction) {
        if (direction == Direction.UP) return;
        BlockPos pos = this.getBlockPos();
        Level level = this.getLevel();
        if (null == level) return;
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.CHUTE)) return;
        level.setBlockAndUpdate(pos, state.setValue(ChuteBlock.FACING, direction));
    }

    private void refreshCooldown() {
        this.cooldown = AnvilCraft.config.chuteMaxCooldown;
    }

    @Override
    public double getLevelX() {
        return this.getBlockPos().getCenter().x;
    }

    @Override
    public double getLevelY() {
        return this.getBlockPos().getCenter().y;
    }

    @Override
    public double getLevelZ() {
        return this.getBlockPos().getCenter().z;
    }
}
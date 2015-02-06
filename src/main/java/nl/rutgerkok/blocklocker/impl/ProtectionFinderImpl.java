package nl.rutgerkok.blocklocker.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import nl.rutgerkok.blocklocker.ChestSettings;
import nl.rutgerkok.blocklocker.ProtectionFinder;
import nl.rutgerkok.blocklocker.ProtectionSign;
import nl.rutgerkok.blocklocker.ProtectionType;
import nl.rutgerkok.blocklocker.SearchMode;
import nl.rutgerkok.blocklocker.SignType;
import nl.rutgerkok.blocklocker.impl.blockfinder.BlockFinder;
import nl.rutgerkok.blocklocker.impl.protection.ContainerProtectionImpl;
import nl.rutgerkok.blocklocker.impl.protection.DoorProtectionImpl;
import nl.rutgerkok.blocklocker.impl.protection.TrapDoorProtectionImpl;
import nl.rutgerkok.blocklocker.profile.Profile;
import nl.rutgerkok.blocklocker.protection.Protection;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

/**
 * Finds a protection.
 *
 * See {@link SearchMode} for a description of protection blocks, supporting
 * blocks and sign blocks.
 */
class ProtectionFinderImpl implements ProtectionFinder {
    private final BlockFinder blockFinder;
    private final ChestSettings settings;

    ProtectionFinderImpl(BlockFinder lookup, ChestSettings settings) {
        blockFinder = lookup;
        this.settings = settings;
    }

    @Override
    public Optional<Protection> findExistingProtectionForNewSign(Block signBlock) {
        BlockState blockState = signBlock.getState();
        if (!(blockState instanceof Sign)) {
            return Optional.absent();
        }

        Sign sign = (Sign) blockState;
        Optional<Block> protectionBlock = this.findProtectableForSign(sign);
        if (!protectionBlock.isPresent()) {
            return Optional.absent();
        }

        return findProtectionForBlock(protectionBlock.get(), SearchMode.NO_SUPPORTING_BLOCKS);
    }

    /**
     * Gets the protection block the sign protects.
     *
     * @param sign
     *            The sign, whether properly formatted or not.
     * @return A block that can be protected, and may or may not be protected
     *         currently.
     */
    private Optional<Block> findProtectableForSign(Sign sign) {
        Block attachedBlock = blockFinder.findSupportingBlock(sign.getBlock());
        if (settings.canProtect(ProtectionType.CONTAINER, attachedBlock.getType())) {
            return Optional.of(attachedBlock);
        }

        return findProtectableForSupportingBlock(attachedBlock);
    }

    /**
     * Gets the protection block that the given block is supporting.
     *
     * @param supportingBlock
     *            The block that is supporting a protection block.
     * @return The protection block, if any.
     */
    private Optional<Block> findProtectableForSupportingBlock(Block supportingBlock) {
        // Search above and below that block for doors
        for (BlockFace doorFace : BlockFinder.VERTICAL_FACES) {
            Block maybeDoor = supportingBlock.getRelative(doorFace);
            if (settings.canProtect(ProtectionType.DOOR, maybeDoor.getType())) {
                return Optional.of(maybeDoor);
            }
        }

        // Search around for trap doors
        for (BlockFace trapDoorFace : BlockFinder.CARDINAL_FACES) {
            Block maybeTrapDoor = supportingBlock.getRelative(trapDoorFace);
            if (settings.canProtect(ProtectionType.TRAP_DOOR, maybeTrapDoor.getType())) {
                return Optional.of(maybeTrapDoor);
            }
        }

        return Optional.absent();
    }

    @Override
    public Optional<Protection> findProtection(Block block) {
        return findProtection(block, SearchMode.ALL);
    }

    @Override
    public Optional<Protection> findProtection(Block block, SearchMode searchMode) {
        Preconditions.checkNotNull(block);
        Material blockMaterial = block.getType();

        // Check for sign
        if (searchMode.searchForSigns() &&
                (blockMaterial == Material.WALL_SIGN || blockMaterial == Material.SIGN_POST)) {
            Sign sign = (Sign) block.getState();
            return findProtectionForExistingSign(sign);
        }

        // Check for other blocks
        return findProtectionForBlock(block, searchMode);
    }

    /**
     * Finds a protection for a protection/supporting block.
     * 
     * @param block
     *            The protection/supporting block.
     * @param searchMode
     * @return The protection, if any.
     */
    private Optional<Protection> findProtectionForBlock(Block block, SearchMode searchMode) {
        // Try as protection block
        Optional<Protection> protection = findProtectionForProtectionBlock(block);
        if (protection.isPresent()) {
            return protection;
        }

        // Try as supporting block
        if (searchMode.searchForSupportingBlocks()) {
            Optional<Block> protectionBlock = findProtectableForSupportingBlock(block);
            if (protectionBlock.isPresent() && settings.canProtect(protectionBlock.get().getType())) {
                return findProtectionForProtectionBlock(protectionBlock.get());
            }
        }

        // Failed
        return Optional.absent();
    }

    private Optional<Protection> findProtectionForExistingSign(Sign sign) {
        // Get type of sign
        Optional<ProtectionSign> parsed = blockFinder.getSignParser().parseSign(sign);
        if (!parsed.isPresent()) {
            // Not actually a protection sign, so no protection
            return Optional.absent();
        }

        Optional<Block> protectionBlock = findProtectableForSign(sign);
        if (protectionBlock.isPresent()) {
            return findProtectionForProtectionBlock(protectionBlock.get(), parsed.get());
        }

        return Optional.absent();
    }

    private Optional<Protection> findProtectionForProtectionBlock(Block protectionBlock) {
        Optional<ProtectionType> protectionType = settings.getProtectionType(protectionBlock.getType());
        if (!protectionType.isPresent()) {
            return Optional.absent();
        }

        // We don't know yet if signs are attached, so we have to check for that
        // in each case of the switch statement

        switch (protectionType.get()) {
            case CONTAINER:
                List<Block> blocks = blockFinder.findContainerNeighbors(protectionBlock);
                Collection<ProtectionSign> signs = blockFinder.findAttachedSigns(blocks);
                if (signs.isEmpty()) {
                    return Optional.absent();
                }
                return Optional.of(ContainerProtectionImpl.fromBlocksWithSigns(
                        signs, blocks, blockFinder));
            case DOOR:
                Door door = new Door(protectionBlock);
                Collection<ProtectionSign> doorSigns = blockFinder.findAttachedSigns(door.getBlocksForSigns());
                if (doorSigns.isEmpty()) {
                    return Optional.absent();
                }
                return Optional.of(DoorProtectionImpl.fromDoorWithSigns(
                        doorSigns, blockFinder, door));
            case TRAP_DOOR:
                Collection<ProtectionSign> trapDoorSigns = blockFinder.findAttachedSigns(
                        blockFinder.findSupportingBlock(protectionBlock));
                if (trapDoorSigns.isEmpty()) {
                    return Optional.absent();
                }
                return Optional.of(TrapDoorProtectionImpl.fromDoorWithSigns(
                        trapDoorSigns, blockFinder, protectionBlock));
            default:
                throw new UnsupportedOperationException("Don't know how to handle protection type " + protectionType.get());
        }

    }

    /**
     * Gets the {@link Protection} for the given block, which is already part of
     * a protection; it must be protection block, not a supporting block or a
     * sign. A sign is given as a hint to this method, so that the
     * {@link Protection#getOwner()} method executes a little faster.
     *
     * @param containerBlock
     *            The block that represents the protection (is a door or
     *            container block).
     * @param sign
     *            The sign used for finding the block.
     * @return The created protection.
     */
    private Optional<Protection> findProtectionForProtectionBlock(Block protectionBlock, ProtectionSign sign) {
        Optional<ProtectionType> protectionType = settings.getProtectionType(protectionBlock.getType());
        if (!protectionType.isPresent()) {
            return Optional.absent();
        }

        switch (protectionType.get()) {
            case CONTAINER:
                Collection<Block> blocks = blockFinder.findContainerNeighbors(protectionBlock);
                return Optional.of(ContainerProtectionImpl.fromBlocksWithSign(
                        sign, blocks, blockFinder));
            case DOOR:
                Door door = new Door(protectionBlock);
                return Optional.of(DoorProtectionImpl.fromDoorWithSign(sign, blockFinder, door));
            case TRAP_DOOR:
                return Optional.of(TrapDoorProtectionImpl.fromDoorWithSign(sign, blockFinder, protectionBlock));
            default:
                throw new UnsupportedOperationException("Don't know how to handle protection type " + protectionType.get());
        }
    }

    @Override
    public boolean isSignNearbyProtection(Block signBlock) {
        BlockState blockState = signBlock.getState();
        if (blockState instanceof Sign) {
            return findProtectableForSign((Sign) blockState).isPresent();
        }
        // Not a sign, so definitely not a sign nearby a protection block
        return false;
    }

    @Override
    public ProtectionSign newProtectionSign(Sign sign, SignType signType, Profile onFirstLine) {
        return new ProtectionSignImpl(sign.getLocation(), signType, Collections.singletonList(onFirstLine));
    }

}

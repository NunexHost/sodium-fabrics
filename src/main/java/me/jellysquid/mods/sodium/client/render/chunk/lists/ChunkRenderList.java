package me.jellysquid.mods.sodium.client.render.chunk.lists;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionFlags;
import me.jellysquid.mods.sodium.client.util.iterator.ByteIterator;
import me.jellysquid.mods.sodium.client.util.iterator.ReversibleByteArrayIterator;
import me.jellysquid.mods.sodium.client.util.iterator.ByteArrayIterator;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;

public class ChunkRenderList {
    private final RenderRegion region;

    private final byte[] sectionsWithGeometry = new byte[RenderRegion.REGION_SIZE];
    private int sectionsWithGeometryCount = 0;

    private final byte[] sectionsWithSprites = new byte[RenderRegion.REGION_SIZE];
    private int sectionsWithSpritesCount = 0;

    private final byte[] sectionsWithEntities = new byte[RenderRegion.REGION_SIZE];
    private int sectionsWithEntitiesCount = 0;

    private int size;

    private int lastVisibleFrame;

    public ChunkRenderList(RenderRegion region) {
        this.region = region;
    }

    public void reset(int frame) {
        this.sectionsWithGeometryCount = 0;
        this.sectionsWithSpritesCount = 0;
        this.sectionsWithEntitiesCount = 0;

        this.size = 0;
        this.lastVisibleFrame = frame;
    }

    public void add(RenderSection render) {
        if (this.size >= RenderRegion.REGION_SIZE) {
            throw new ArrayIndexOutOfBoundsException("Render list is full");
        }

        this.size++;

        int index = render.getSectionIndex();
        int flags = render.getFlags();

        // Optimized: Use bitwise OR to set the corresponding bit in the sections array.
        this.sectionsWithGeometry[index >> 3] |= (1 << (index & 7));
        this.sectionsWithSprites[index >> 3] |= (1 << (index & 7));
        this.sectionsWithEntities[index >> 3] |= (1 << (index & 7));
    }

    public @Nullable ByteIterator sectionsWithGeometryIterator(boolean reverse) {
        if (this.sectionsWithGeometryCount == 0) {
            return null;
        }

        // Optimized: Use a bitmask to quickly find the first section with geometry.
        int firstIndex = findFirstSetBit(this.sectionsWithGeometry);

        // Optimized: Use the bitmask to quickly iterate over the sections with geometry.
        return new ReversibleByteArrayIterator(this.sectionsWithGeometry, firstIndex, reverse);
    }

    public @Nullable ByteIterator sectionsWithSpritesIterator() {
        if (this.sectionsWithSpritesCount == 0) {
            return null;
        }

        // Optimized: Use a bitmask to quickly find the first section with sprites.
        int firstIndex = findFirstSetBit(this.sectionsWithSprites);

        // Optimized: Use the bitmask to quickly iterate over the sections with sprites.
        return new ByteArrayIterator(this.sectionsWithSprites, firstIndex);
    }

    public @Nullable ByteIterator sectionsWithEntitiesIterator() {
        if (this.sectionsWithEntitiesCount == 0) {
            return null;
        }

        // Optimized: Use a bitmask to quickly find the first section with entities.
        int firstIndex = findFirstSetBit(this.sectionsWithEntities);

        // Optimized: Use the bitmask to quickly iterate over the sections with entities.
        return new ByteArrayIterator(this.sectionsWithEntities, firstIndex);
    }

    private static int findFirstSetBit(byte[] array) {
        int index = 0;
        while (index < array.length && array[index] == 0) {
            index++;
        }

        return index;
    }
}


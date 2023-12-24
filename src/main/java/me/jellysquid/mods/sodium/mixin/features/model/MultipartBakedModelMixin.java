package me.jellysquid.mods.sodium.mixin.features.model;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.MultipartBakedModel;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Predicate;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MultipartBakedModel.class)
public class MultipartBakedModelMixin {

    @Unique
    private static final int MAX_CACHE_SIZE = 1024;

    @Unique
    private final Map<Pair<BlockState, Direction>, BakedModel[]> stateCacheFast = new Reference2ReferenceOpenHashMap<>(MAX_CACHE_SIZE);
    @Unique
    private final StampedLock lock = new StampedLock();

    @Shadow
    @Final
    private List<Pair<Predicate<BlockState>, BakedModel>> components;

    /**
     * @author JellySquid
     * @reason Avoid expensive allocations and replace bitfield indirection
     */
    @Overwrite
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {

        if (state == null) {
            return Collections.emptyList();
        }

        Pair<BlockState, Direction> key = Pair.of(state, face);
        BakedModel[] models;

        long readStamp = this.lock.readLock();
        try {
            models = this.stateCacheFast.get(key);
        } finally {
            this.lock.unlockRead(readStamp);
        }

        if (models == null) {
            long writeStamp = this.lock.writeLock();
            try {
                List<BakedModel> modelList = new ArrayList<>(this.components.size());

                for (Pair<Predicate<BlockState>, BakedModel> pair : this.components) {
                    if (pair.getLeft().test(state)) {
                        if (pair.getRight().isFacePresent(face)) {
                            modelList.add(pair.getRight());
                        }
                    }
                }

                models = modelList.toArray(BakedModel[]::new);
                this.stateCacheFast.put(key, models);
            } finally {
                this.lock.unlockWrite(writeStamp);
            }
        }

        List<BakedQuad> quads = new ArrayList<>();
        for (BakedModel model : models) {
            quads.addAll(model.getQuads(state, face, random));
        }

        return quads;
    }

}

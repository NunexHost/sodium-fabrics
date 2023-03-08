package me.jellysquid.mods.sodium.client.render.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.render.chunk.graph.*;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderBuildTask;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderEmptyBuildTask;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderRebuildTask;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import me.jellysquid.mods.sodium.common.util.collections.WorkStealingFutureDrain;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import org.apache.commons.lang3.Validate;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RenderSectionManager {
    /**
     * The maximum distance a chunk can be from the player's camera in order to be eligible for blocking updates.
     */
    private static final double NEARBY_CHUNK_DISTANCE = Math.pow(32, 2.0);

    /**
     * The minimum distance the culling plane can be from the player's camera. This helps to prevent mathematical
     * errors that occur when the fog distance is less than 8 blocks in width, such as when using a blindness potion.
     */
    private static final float FOG_PLANE_MIN_DISTANCE = (float) Math.pow(8.0f, 2.0);

    /**
     * The distance past the fog's far plane at which to begin culling. Distance calculations use the center of each
     * chunk from the camera's position, and as such, special care is needed to ensure that the culling plane is pushed
     * back far enough. I'm sure there's a mathematical formula that should be used here in place of the constant,
     * but this value works fine in testing.
     */
    private static final float FOG_PLANE_OFFSET = 12.0f;

    private final ChunkBuilder builder;

    private final RenderRegionManager regions;
    private final ClonedChunkSectionCache sectionCache;

    private final Long2ReferenceMap<RenderSection> sections = new Long2ReferenceOpenHashMap<>();
    public final InnerNode root = Octree.newRoot();
    private final Int2ReferenceMap<ObjectArrayList<QueueEntry>> iterationQueues = new Int2ReferenceOpenHashMap<>(200);
    private int nonEmptyQueues = 0;
    private int queueIndexLowerBound = 0;

    private final Map<ChunkUpdateType, PriorityQueue<RenderSection>> rebuildQueues = new EnumMap<>(ChunkUpdateType.class);

    private final ObjectList<RenderSection> tickableChunks = new ObjectArrayList<>();
    private final ObjectList<BlockEntity> visibleBlockEntities = new ObjectArrayList<>();

    private final RegionChunkRenderer chunkRenderer;

    private final SodiumWorldRenderer worldRenderer;
    private final ClientWorld world;

    private final int renderDistance;

    private float cameraX, cameraY, cameraZ;
    private int centerChunkX, centerChunkZ;

    private boolean needsUpdate;

    private boolean useFogCulling;
    private boolean useOcclusionCulling;

    private double fogRenderCutoff;

    private Frustum frustum;

    private int currentFrame = 0;
    private boolean alwaysDeferChunkUpdates;

    private final ChunkTracker tracker;

    private ChunkRenderList chunkRenderList;

    private static final int EXPAND_BOX_WAIT = 1000;
    private static final int BOX_PURGE_INTERVAL = 30;
    private static final float PURGE_BOX_THRESHOLD = 0.05f;
    private Vector3f[][] visibleBoxes;
    private int[] visibleBoxHits;
    private int maxVisibleBoxes = 5;
    private int visibleBoxCount = 0;
    private int expandBoxWaitCount = 0;

    private int frustumCheckActualCount = 0;
    private int frustumCheckPotentialCount = 0;
    private int frustumCheckBoxCount = 0;
    private int boxTestCount = 0;
    private int clearedBoxes = 0;

    public RenderSectionManager(SodiumWorldRenderer worldRenderer, ClientWorld world, int renderDistance, CommandList commandList) {
        this.chunkRenderer = new RegionChunkRenderer(RenderDevice.INSTANCE, ChunkMeshFormats.COMPACT);

        this.worldRenderer = worldRenderer;
        this.world = world;

        this.builder = new ChunkBuilder(ChunkMeshFormats.COMPACT);
        this.builder.init(world);

        this.needsUpdate = true;
        this.renderDistance = renderDistance;

        this.regions = new RenderRegionManager(commandList);
        this.sectionCache = new ClonedChunkSectionCache(this.world);

        for (ChunkUpdateType type : ChunkUpdateType.values()) {
            this.rebuildQueues.put(type, new ObjectArrayFIFOQueue<>());
        }

        this.tracker = this.worldRenderer.getChunkTracker();
    }

    public void reloadChunks(ChunkTracker tracker) {
        tracker.getChunks(ChunkStatus.FLAG_HAS_BLOCK_DATA)
                .forEach(pos -> this.onChunkAdded(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos)));
    }

    public void update(Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.resetLists();

        var list = new ChunkRenderListBuilder();

        this.setup(camera);
        this.iterateChunks(list, camera, frustum, frame, spectator);

        this.chunkRenderList = list.build();
        this.needsUpdate = false;
    }

    private void setup(Camera camera) {
        Vec3d cameraPos = camera.getPos();

        this.cameraX = (float) cameraPos.x;
        this.cameraY = (float) cameraPos.y;
        this.cameraZ = (float) cameraPos.z;

        var options = SodiumClientMod.options();

        this.useFogCulling = options.performance.useFogOcclusion;
        this.alwaysDeferChunkUpdates = options.performance.alwaysDeferChunkUpdates;

        if (this.useFogCulling) {
            float dist = RenderSystem.getShaderFogEnd() + FOG_PLANE_OFFSET;

            if (dist == 0.0f) {
                this.fogRenderCutoff = Double.POSITIVE_INFINITY;
            } else {
                this.fogRenderCutoff = Math.max(FOG_PLANE_MIN_DISTANCE, dist * dist);
            }
        }
    }

    private void schedulePendingUpdates(RenderSection section) {
        if (section.getPendingUpdate() == null || !this.tracker.hasMergedFlags(section.getChunkX(), section.getChunkZ(), ChunkStatus.FLAG_ALL)) {
            return;
        }

        PriorityQueue<RenderSection> queue = this.rebuildQueues.get(section.getPendingUpdate());

        if (queue.size() >= 32) {
            return;
        }

        queue.enqueue(section);
    }

    private void addChunkToVisible(ChunkRenderListBuilder list, RenderSection render) {
        list.add(render);

        if (render.isTickable()) {
            this.tickableChunks.add(render);
        }
    }

    private void addEntitiesToRenderLists(RenderSection render) {
        Collection<BlockEntity> blockEntities = render.getData()
                .getBlockEntities();

        if (!blockEntities.isEmpty()) {
            this.visibleBlockEntities.addAll(blockEntities);
        }
    }

    private void resetLists() {
        for (PriorityQueue<RenderSection> queue : this.rebuildQueues.values()) {
            queue.clear();
        }

        this.visibleBlockEntities.clear();
        this.tickableChunks.clear();

        this.chunkRenderList = null;
    }

    public Collection<BlockEntity> getVisibleBlockEntities() {
        return this.visibleBlockEntities;
    }

    public void onChunkAdded(int x, int z) {
        for (int y = this.world.getBottomSectionCoord(); y < this.world.getTopSectionCoord(); y++) {
            this.needsUpdate |= this.loadSection(x, y, z);
        }
    }

    public void onChunkRemoved(int x, int z) {
        for (int y = this.world.getBottomSectionCoord(); y < this.world.getTopSectionCoord(); y++) {
            this.needsUpdate |= this.unloadSection(x, y, z);
        }
    }

    private boolean loadSection(int x, int y, int z) {
        // if (this.sections.containsKey(ChunkSectionPos.asLong(x, y, z))) {
        //     throw new IllegalStateException("why are sections being loaded twice?");
        // }
        if (y != 4) {
            // return false;
        }
        RenderSection render = new RenderSection(this.worldRenderer, x, y, z);

        this.sections.put(ChunkSectionPos.asLong(x, y, z), render);
        root.setSection(render);

        Chunk chunk = this.world.getChunk(x, z);
        ChunkSection section = chunk.getSectionArray()[this.world.sectionCoordToIndex(y)];

        if (section.isEmpty()) {
            render.setData(ChunkRenderData.EMPTY);
        } else {
            render.markForUpdate(ChunkUpdateType.INITIAL_BUILD);
        }

        this.connectNeighborNodes(render);

        return true;
    }

    private boolean unloadSection(int x, int y, int z) {
        RenderSection chunk = this.sections.remove(ChunkSectionPos.asLong(x, y, z));
        if (this.nonEmptyQueues > 0) {
            throw new IllegalStateException("why are sections being unloaded while the bfs is running?");
        }
        if (chunk == null) {
            return false;
        }
        root.removeSection(chunk);

        RenderRegion region = this.regions.getRegion(RenderRegion.getRegionKeyForChunk(x, y, z));
        if (region != null) {
            region.deleteSection(chunk);
        }

        if (chunk == null) {
            throw new IllegalStateException("Chunk is not loaded: " + ChunkSectionPos.from(x, y, z));
        }

        chunk.delete();

        this.disconnectNeighborNodes(chunk);

        return true;
    }

    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z) {
        Validate.notNull(this.chunkRenderList, "Render list is null");

        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        this.chunkRenderer.render(matrices, commandList, this.regions, this.chunkRenderList, pass, new ChunkCameraContext(x, y, z));

        commandList.flush();
    }

    public void tickVisibleRenders() {
        for (RenderSection render : this.tickableChunks) {
            render.tick();
        }
    }

    /**
     * Checks if the sections contained within (include of both bounds) the given
     * section-space box are visible.
     */
    public boolean isSectionBoxVisible(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return this.root.isBoxVisible(this.currentFrame, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void updateChunks() {
        updateChunks(false);
    }

    public void updateAllChunksNow() {
        updateChunks(true);

        // Also wait for any rebuilds which had already been scheduled before this method was called
        this.needsUpdate |= this.performAllUploads();
    }

    private void updateChunks(boolean allImmediately) {
        var blockingFutures = new LinkedList<CompletableFuture<ChunkBuildResult>>();

        this.submitRebuildTasks(ChunkUpdateType.IMPORTANT_REBUILD, blockingFutures);
        this.submitRebuildTasks(ChunkUpdateType.INITIAL_BUILD, allImmediately ? blockingFutures : null);
        this.submitRebuildTasks(ChunkUpdateType.REBUILD, allImmediately ? blockingFutures : null);

        // Try to complete some other work on the main thread while we wait for rebuilds to complete
        this.needsUpdate |= this.performPendingUploads();

        if (!blockingFutures.isEmpty()) {
            this.needsUpdate = true;
            this.regions.upload(RenderDevice.INSTANCE.createCommandList(), new WorkStealingFutureDrain<>(blockingFutures, this.builder::stealTask));
        }

        this.regions.cleanup();
    }

    private void submitRebuildTasks(ChunkUpdateType filterType, LinkedList<CompletableFuture<ChunkBuildResult>> immediateFutures) {
        int budget = immediateFutures != null ? Integer.MAX_VALUE : this.builder.getSchedulingBudget();

        PriorityQueue<RenderSection> queue = this.rebuildQueues.get(filterType);

        while (budget > 0 && !queue.isEmpty()) {
            RenderSection section = queue.dequeue();

            if (section.isDisposed()) {
                continue;
            }

            // Sections can move between update queues, but they won't be removed from the queue they were
            // previously in to save CPU cycles. We just filter any changed entries here instead.
            if (section.getPendingUpdate() != filterType) {
                continue;
            }

            ChunkRenderBuildTask task = this.createRebuildTask(section);
            CompletableFuture<?> future;

            if (immediateFutures != null) {
                CompletableFuture<ChunkBuildResult> immediateFuture = this.builder.schedule(task);
                immediateFutures.add(immediateFuture);

                future = immediateFuture;
            } else {
                future = this.builder.scheduleDeferred(task);
            }

            section.onBuildSubmitted(future);

            budget--;
        }
    }

    private boolean performPendingUploads() {
        Iterator<ChunkBuildResult> it = this.builder.createDeferredBuildResultDrain();

        if (!it.hasNext()) {
            return false;
        }

        this.regions.upload(RenderDevice.INSTANCE.createCommandList(), it);

        return true;
    }

    /**
     * Processes all build task uploads, blocking for tasks to complete if necessary.
     */
    private boolean performAllUploads() {
        boolean anythingUploaded = false;

        while (true) {
            // First check if all tasks are done building (and therefore the upload queue is final)
            boolean allTasksBuilt = this.builder.isIdle();

            // Then process the entire upload queue
            anythingUploaded |= this.performPendingUploads();

            // If the upload queue was the final one
            if (allTasksBuilt) {
                // then we are done
                return anythingUploaded;
            } else {
                // otherwise we need to wait for the worker threads to make progress
                try {
                    // This code path is not the default one, it doesn't need super high performance, and having the
                    // workers notify the main thread just for it is probably not worth it.
                    //noinspection BusyWait
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
    }

    public ChunkRenderBuildTask createRebuildTask(RenderSection render) {
        ChunkRenderContext context = WorldSlice.prepare(this.world, render.getChunkPos(), this.sectionCache);
        int frame = this.currentFrame;

        if (context == null) {
            return new ChunkRenderEmptyBuildTask(render, frame);
        }

        return new ChunkRenderRebuildTask(render, context, frame);
    }

    public void markGraphDirty() {
        this.needsUpdate = true;
    }

    public boolean isGraphDirty() {
        // return this.needsUpdate; TODO: Re-enable this
        return true;
    }

    public ChunkBuilder getBuilder() {
        return this.builder;
    }

    public void destroy() {
        this.resetLists();

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.regions.delete(commandList);
        }

        this.chunkRenderer.delete();
        this.builder.stopWorkers();
    }

    public int getTotalSections() {
        return this.sections.size();
    }

    public int getVisibleChunkCount() {
        return this.chunkRenderList.getCount();
    }

    public void scheduleRebuild(int x, int y, int z, boolean important) {
        this.sectionCache.invalidate(x, y, z);

        RenderSection section = this.sections.get(ChunkSectionPos.asLong(x, y, z));

        if (section != null && section.isBuilt()) {
            if (!this.alwaysDeferChunkUpdates && (important || this.isChunkPrioritized(section))) {
                section.markForUpdate(ChunkUpdateType.IMPORTANT_REBUILD);
            } else {
                section.markForUpdate(ChunkUpdateType.REBUILD);
            }
        }

        this.needsUpdate = true;
    }

    public boolean isChunkPrioritized(RenderSection render) {
        return render.getSquaredDistance(this.cameraX, this.cameraY, this.cameraZ) <= NEARBY_CHUNK_DISTANCE;
    }

    public void onChunkRenderUpdates(int x, int y, int z, ChunkRenderData data) {
        RenderSection node = this.getRenderSection(x, y, z);

        if (node != null) {
            node.setOcclusionData(data.getOcclusionData());
        }
    }

    /**
     * First checks with canCull if the node has been searched in this direction already.
     * Checks if the given chunk graph info for a node (a section) in the graph
     * allows visibility to traverse through it from a direction to another.
     */
    private boolean isCulled(ChunkGraphInfo node, Direction from, Direction to) {
        if (node.canCull(to)) {
            return true;
        }

        return this.useOcclusionCulling && from != null && !node.isVisibleThrough(from, to);
    }

    private int updateCount = 0;

    private static class AcceptableBox {
        Vector3f[] box;
        int hits;

        private AcceptableBox(Vector3f[] box, int hits) {
            this.box = box;
            this.hits = hits;
        }
    }

     /**
     * Tests if the given box is acceptable for box testing with the given frustum.
     * It tests if the box that is 16 blocks smaller in each of the 6 directions is
     * inside the frustum. Since box tests only need to ensure they contain only
     * boxes that intersect with the frustum this is sufficient. Testing a smaller
     * box allows the boxes to become bigger and intersect with the frustum, thus
     * covering more sections that can be efficiently included. Testing if the box
     * intersects with the frustum doesn't work, since it could then be for example
     * mostly outside the frustum and just touch it, allowing sections outside the
     * frustum to be wrongly included.
     */
    private boolean boxIsAcceptable(Frustum frustum, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustum.testBox(minX + 16f, minY + 16f, minZ + 16f, maxX - 16f, maxY - 16f, maxZ - 16f) == Frustum.Visibility.INSIDE;
    }

    private void compactBoxes() {
        // find the boxes that are within the frustum and only keep those
        List<AcceptableBox> acceptableBoxes = new ArrayList<>();
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f boxMin = visibleBoxes[i][0];
            Vector3f boxMax = visibleBoxes[i][1];
            if (boxIsAcceptable(frustum, boxMin.x, boxMin.y, boxMin.z, boxMax.x, boxMax.y, boxMax.z)) {
                acceptableBoxes.add(new AcceptableBox(visibleBoxes[i], visibleBoxHits[i]));
            }
        }

        // sort by hits descending
        acceptableBoxes.sort(Comparator.comparingInt(o -> -o.hits));

        // update the number of actually present visible boxes,
        // and make sure it doesn't exceed the number of allowed visible boxes
        visibleBoxCount = Math.min(acceptableBoxes.size(), maxVisibleBoxes);

        // clear boxes that contribute too few hits relative to the best boxes
        if (visibleBoxCount >= 2 && ++updateCount >= BOX_PURGE_INTERVAL) {
            int firstSecondAverage = visibleBoxCount >= 3 
                ? (acceptableBoxes.get(0).hits + acceptableBoxes.get(1).hits) / 2
                : acceptableBoxes.get(0).hits;
            int hitThreshold = (int)(PURGE_BOX_THRESHOLD * firstSecondAverage);
            int previousVisibleBoxCount = visibleBoxCount;
            while ((float)acceptableBoxes.get(visibleBoxCount - 1).hits < hitThreshold) {
                visibleBoxCount--;
                if (visibleBoxCount == 0) break;
            }
            clearedBoxes = previousVisibleBoxCount - visibleBoxCount;

            // reset the hit counters
            for (AcceptableBox box : acceptableBoxes) {
                box.hits = 0;
            }

            updateCount = 0;
        }
        for (int i = visibleBoxCount; i < maxVisibleBoxes; i++) {
            visibleBoxHits[i] = 0;
        }

        // copy over as many sorted boxes as allowed
        for (int i = 0; i < visibleBoxCount; i++) {
            AcceptableBox box = acceptableBoxes.get(i);
            visibleBoxes[i][0].set(box.box[0]);
            visibleBoxes[i][1].set(box.box[1]);
            visibleBoxHits[i] = box.hits;
        }
    }

    private void initBoxTestState() {
        // when the allowed number of boxes increases, recreate the array and copy over the boxes
        int prevLength = visibleBoxes == null ? 0 : visibleBoxes.length;
        if (maxVisibleBoxes > prevLength) {
            Vector3f[][] newVisibleBoxes = new Vector3f[maxVisibleBoxes][2];
            int[] newVisibleBotHits = new int[maxVisibleBoxes];
            if (prevLength > 0) {
                System.arraycopy(visibleBoxes, 0, newVisibleBoxes, 0, prevLength);
                System.arraycopy(visibleBoxHits, 0, newVisibleBotHits, 0, prevLength);
            }
            visibleBoxes = newVisibleBoxes;
            visibleBoxHits = newVisibleBotHits;
            for (int i = prevLength; i < maxVisibleBoxes; i++) {
                visibleBoxes[i][0] = new Vector3f();
                visibleBoxes[i][1] = new Vector3f();
                visibleBoxHits[i] = 0;
            }
        }

        // update boxes with the frustum, combine if possible, sort by size,
        // and update the number of visible boxes
        compactBoxes();

        // reset performance counters
        frustumCheckActualCount = 0;
        frustumCheckBoxCount = 0;
        boxTestCount = 0;
        frustumCheckPotentialCount = 0;

        // reset the expand box wait count with random value up the maximum count,
        // since the box expansion happens at a regular interval this ensures
        // roughly random sections are considered for box expansion each frame
        expandBoxWaitCount = (int)(Math.random() * EXPAND_BOX_WAIT);
    }

    /**
     * Initializes the BFS queue state to search for visible chunks. If the camera
     * is in a section, only the current section is added to the queue. If the
     * camera is not in a section, all sections at the top of the world/bottom of
     * the world within render distance and the frustum are added to the queue.
     */
    private void initSearch(ChunkRenderListBuilder list, Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.currentFrame = frame;
        this.frustum = frustum;
        this.useOcclusionCulling = MinecraftClient.getInstance().chunkCullingEnabled;

        initBoxTestState();

        // queues are cleared in iteration

        BlockPos origin = camera.getBlockPos();

        int chunkX = origin.getX() >> 4;
        int chunkY = origin.getY() >> 4;
        int chunkZ = origin.getZ() >> 4;

        this.centerChunkX = chunkX;
        this.centerChunkZ = chunkZ;

        RenderSection rootRender = this.getRenderSection(chunkX, chunkY, chunkZ);

        if (rootRender != null) {
            ChunkGraphInfo rootInfo = rootRender.getGraphInfo();
            rootInfo.resetCullingState();

            if (spectator && this.world.getBlockState(origin).isOpaqueFullCube(this.world, origin)) {
                this.useOcclusionCulling = false;
            }

            this.addVisible(list, rootRender.octreeLeaf, null, origin);
        } else {
            chunkY = MathHelper.clamp(origin.getY() >> 4, this.world.getBottomSectionCoord(), this.world.getTopSectionCoord() - 1);

            for (int x2 = -this.renderDistance; x2 <= this.renderDistance; ++x2) {
                for (int z2 = -this.renderDistance; z2 <= this.renderDistance; ++z2) {
                    RenderSection render = this.getRenderSection(chunkX + x2, chunkY, chunkZ + z2);

                    if (render == null) {
                        continue;
                    }

                    ChunkGraphInfo info = render.getGraphInfo();

                    float minX = render.getOriginX();
                    float minY = render.getOriginY();
                    float minZ = render.getOriginZ();
                    float maxX = minX + 16;
                    float maxY = minY + 16;
                    float maxZ = minZ + 16;
                    if (isFrustumCulled(minX, minY, minZ, maxX, maxY, maxZ)) {
                        continue;
                    }

                    info.resetCullingState();

                    this.addVisible(list, render.octreeLeaf, null, origin);
                }
            }
        }
    }

    private boolean isVisibleInBox(float x, float y, float z) {
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f[] corners = visibleBoxes[i];
            Vector3f min = corners[0];
            Vector3f max = corners[1];
            boxTestCount++;
            if (min.x <= x && x < max.x && min.y <= y && y < max.y && min.z <= z && z < max.z) {
                visibleBoxHits[i]++;
                return true;
            }
        }
        return false;
    }

    private void expandBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        boolean expanded = false;
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f[] corners = visibleBoxes[i];

            // combine with this box
            float newBoxMinX = Math.min(corners[0].x, minX);
            float newBoxMinY = Math.min(corners[0].y, minY);
            float newBoxMinZ = Math.min(corners[0].z, minZ);
            float newBoxMaxX = Math.max(corners[1].x, maxX);
            float newBoxMaxY = Math.max(corners[1].y, maxY);
            float newBoxMaxZ = Math.max(corners[1].z, maxZ);

            // check that the box is still within the frustum
            frustumCheckBoxCount++;
            if (boxIsAcceptable(frustum, newBoxMinX, newBoxMinY, newBoxMinZ, newBoxMaxX, newBoxMaxY, newBoxMaxZ)) {
                // replace the box
                corners[0].set(newBoxMinX, newBoxMinY, newBoxMinZ);
                corners[1].set(newBoxMaxX, newBoxMaxY, newBoxMaxZ);
                visibleBoxHits[i]++;
                expanded = true;
                break;
            }
        }

        // didn't combine with any box, add a new one if possible
        if (!expanded && visibleBoxCount < maxVisibleBoxes) {
            visibleBoxes[visibleBoxCount][0].set(minX, minY, minZ);
            visibleBoxes[visibleBoxCount][1].set(maxX, maxY, maxZ);
            visibleBoxHits[visibleBoxCount] = 1;
            visibleBoxCount++;
        }
    }

    private boolean isFrustumCulled(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        frustumCheckPotentialCount++;

        // check if within a visible box to avoid checking the frustum
        // TODO: do any more corners need to be checked?
        // TODO: Do both corners need to be check if they are just single sections?
        if (!(isVisibleInBox(minX, minY, minZ) || isVisibleInBox(maxX, maxY, maxZ))) {
            frustumCheckActualCount++;
            if(!frustum.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }

            // visible, add to visible boxes
            // iterate the existing boxes to check if we can add to them
            if (++expandBoxWaitCount > EXPAND_BOX_WAIT) {
                expandBoxWaitCount = 0;
                expandBox(minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
        return false;
    }

    public class QueueEntry {
        public final Octree node;
        public final Direction flow;
 
        public QueueEntry(Octree node, Direction flow) {
            this.node = node;
            this.flow = flow;
        }
    }

    /**
     * This is the main BFS search loop. It iterates over the queue of chunks to be
     * processed until it is empty. For each iterated chunk it checks adjacent
     * chunks in all directions that are connected visibly and schedules them if
     * they are within the render distance and not frustum culled.
     * {@code bfsEnqueue} handles updating the section's culling state and the
     * frustum check.
     */
    private void iterateChunks(ChunkRenderListBuilder list, Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.initSearch(list, camera, frustum, frame, spectator);

        // idea: find the largest skippable face adjacent octree and add it to the queue for each direction we want to explore. if it doesn't exist (the adjacent section isn't empty), add the adjacent section (its octree leaf) instead

        int distance = 0;
        while (this.nonEmptyQueues > 0) {
            ObjectArrayList<QueueEntry> queue = this.iterationQueues.get(distance);
            if (queue == null) {
                continue;
            }
            for (int i = 0; i < queue.size(); i++) {
                QueueEntry entry = queue.get(i);
                Octree node = entry.node;
                Direction flow = entry.flow;

                // TODO: temporary?
                node.iterateWholeTree((subNode) -> {
                    schedulePendingUpdates(subNode.section);
                });
                // if (node.isLeaf()) {
                //     schedulePendingUpdates(node.section);
                // }

                for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
                    // TODO: deal with this only working sometimes
                    if (node instanceof LeafNode leafNode) {
                        RenderSection section = leafNode.section;
                        if (section == null) {
                            continue; // TODO: make unnecessary
                            // throw new IllegalStateException("null section");
                        }
                        if (this.isCulled(section.getGraphInfo(), flow, dir)) {
                            // continue;
                        }
                    }

                    // iterate the adjacent nodes, skipping over the contents of skippable nodes
                    node.iterateFaceAdjacentNodes((faceAdjacent) -> {
                        this.bfsEnqueue(list, camera, node, faceAdjacent, DirectionUtil.getOpposite(dir));
                    }, dir, true);
                }
            }
            queue.clear();
            nonEmptyQueues--;

            // move the next queue to look at to the lower bound of the distance
            // in case a queue with a lower distance was added
            distance = Math.min(distance, this.queueIndexLowerBound);
        }
        if (lastCulledNodes != null && !lastCulledNodes.equals(culledNodes)) {
            System.out.println("culled nodes changed");
        }
        lastCulledNodes = culledNodes;
        culledNodes = new HashSet<>();
    }

    private Set<Octree> culledNodes = new HashSet<>();
    private Set<Octree> lastCulledNodes;

    /**
     * Adds a render section to the BFS queue. It checks that the section hasn't
     * already been processed as visible and that it's still inside the frustum.
     * Before adding it to the queue, the last visible frame is updated so that it
     * isn't added again in this frame if it has already been determined to be
     * visible. The culling state is updated with the culling state of the parent
     * section that led to this section being added to the queue.
     */
    private void bfsEnqueue(ChunkRenderListBuilder list, Camera camera, Octree parent, Octree node, Direction flow) {
        if (!node.isWithinDistance(this.renderDistance, this.centerChunkX, this.centerChunkZ)) {
            return;
        }

        if (node.isWholeSubtreeVisibleAt(this.currentFrame)) {
            return;
        }

        if (!this.frustum.isBoxVisible(node.getBlockX(), node.getBlockY(), node.getBlockZ(),
            node.getBlockMaxX(), node.getBlockMaxY(), node.getBlockMaxZ())) {
            culledNodes.add(node);
            return;
        }

        // Vec3d cameraPos = camera.getPos();
        // if (node.isOccluded((float)cameraPos.x, (float)cameraPos.y, (float)cameraPos.z)) {
        //     return;
        // }

        if (parent instanceof LeafNode parentLeaf && node instanceof LeafNode nodeLeaf) {
            ChunkGraphInfo info = nodeLeaf.section.getGraphInfo();
            info.setCullingState(parentLeaf.section.getGraphInfo().getCullingState(), flow);
        }

        this.addVisible(list, node, flow, camera.getBlockPos());
    }

    private void addVisible(ChunkRenderListBuilder list, Octree node, Direction flow, BlockPos origin) {
        int distance = node.getCameraDistance(origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4);
        ObjectArrayList<QueueEntry> queue = this.iterationQueues.get(distance);
        if (queue == null) {
            queue = new ObjectArrayList<>();
            this.iterationQueues.put(distance, queue);
        }
        if (queue.isEmpty()) {
            this.nonEmptyQueues++;
            this.queueIndexLowerBound = Math.min(this.queueIndexLowerBound, distance);
        }
        queue.add(new QueueEntry(node, flow));

        // TODO: using iterateUnskippableTree results in culling issues: at -766, 76, -656 on seed 6820040458059637458 facing down and north, move left and right to see the issue
        // TODO: when iterating large nodes, do resursive descent and cull the nodes outside the frustum/render distance
        node.iterateWholeTree((leafNode) -> {
            // don't add the same sections twice
            if (leafNode.isWholeSubtreeVisibleAt(currentFrame)) {
                return;
            }

            RenderSection render = leafNode.section;

            if (this.useFogCulling && render.getSquaredDistanceXZ(this.cameraX, this.cameraZ) >= this.fogRenderCutoff) {
                return;
            }

            int flags = render.getFlags();

            if ((flags & RenderSectionFlags.HAS_BLOCK_GEOMETRY) != 0) {
                this.addChunkToVisible(list, render);
            }

            if ((flags & RenderSectionFlags.HAS_BLOCK_ENTITIES) != 0) {
                this.addEntitiesToRenderLists(render);
            }
        });

        // the node is only marked as visible here, because otherwise all contaiend
        // nodes wouldn't render since they find a parent with a matching lower visible
        // frame bound
        node.setSubtreeVisibleNow(this.currentFrame);
    }

    private void connectNeighborNodes(RenderSection render) {
        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            RenderSection adj = this.getRenderSection(render.getChunkX() + dir.getOffsetX(),
                    render.getChunkY() + dir.getOffsetY(),
                    render.getChunkZ() + dir.getOffsetZ());

            if (adj != null) {
                adj.setAdjacentNode(DirectionUtil.getOpposite(dir), render);
                render.setAdjacentNode(dir, adj);
            }
        }
    }

    private void disconnectNeighborNodes(RenderSection render) {
        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            RenderSection adj = render.getAdjacent(dir);

            if (adj != null) {
                adj.setAdjacentNode(DirectionUtil.getOpposite(dir), null);
                render.setAdjacentNode(dir, null);
            }
        }
    }

    private RenderSection getRenderSection(int x, int y, int z) {
        return this.sections.get(ChunkSectionPos.asLong(x, y, z));
    }

    public Collection<String> getDebugStrings() {
        List<String> list = new ArrayList<>();

        int count = 0;

        long deviceUsed = 0;
        long deviceAllocated = 0;

        for (var region : this.regions.getLoadedRegions()) {
            deviceUsed += region.getDeviceUsedMemory();
            deviceAllocated += region.getDeviceAllocatedMemory();

            count++;
        }

        list.add(String.format("Device buffer objects: %d", count));
        list.add(String.format("Device memory: %d/%d MiB", MathUtil.toMib(deviceUsed), MathUtil.toMib(deviceAllocated)));
        list.add(String.format("Staging buffer: %s", this.regions.getStagingBuffer().toString()));

        int totalFrustumChecks = frustumCheckActualCount + frustumCheckBoxCount;
        list.add(String.format("%s Visible box count", visibleBoxCount));
        list.add(String.format("%s chunk frustum checks actual", frustumCheckActualCount));
        list.add(String.format("%s total frustum checks", totalFrustumChecks));
        list.add(String.format("%s chunk frustum checks potential", frustumCheckPotentialCount));
        list.add(String.format("%d%% fewer frustum checks", frustumCheckPotentialCount == 0 ? 0 : 100 - 100 * totalFrustumChecks / frustumCheckPotentialCount));
        list.add(String.format("%s additional box frustum checks", frustumCheckBoxCount));
        list.add(String.format("%s box tests", boxTestCount));
        list.add(String.format("%s boxes cleared",clearedBoxes));
        for (int i = 0; i < visibleBoxCount; i++) {
            list.add(String.format("%s hits on box %d", visibleBoxHits[i], i));
        }
        return list;
    }
}

package me.jellysquid.mods.sodium.client.render.chunk.graph;

import java.util.*;

import org.joml.Vector3f;

import me.jellysquid.mods.sodium.client.util.frustum.Frustum;

public class FrustumBoxTest extends FrustumTest {
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

    private int updateCount = 0;

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
            if (boxIsAcceptable(newBoxMinX, newBoxMinY, newBoxMinZ, newBoxMaxX, newBoxMaxY, newBoxMaxZ)) {
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

    @Override
    public boolean isFrustumCulled(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ) {
        frustumCheckPotentialCount++;

        // check if within a visible box to avoid checking the frustum
        // TODO: do any more corners need to be checked?
        // TODO: Do both corners need to be check if they are just single sections?
        if (!(isVisibleInBox(minX, minY, minZ) || isVisibleInBox(maxX, maxY, maxZ))) {
            frustumCheckActualCount++;
            if (!this.frustum.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ)) {
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
    private boolean boxIsAcceptable(float minX, float minY, float minZ, float maxX, float maxY,
            float maxZ) {
        return this.frustum.testBox(minX + 16f, minY + 16f, minZ + 16f, maxX - 16f, maxY - 16f,
                maxZ - 16f) == Frustum.Visibility.INSIDE;
    }

    private void compactBoxes() {
        // find the boxes that are within the frustum and only keep those
        List<AcceptableBox> acceptableBoxes = new ArrayList<>();
        for (int i = 0; i < visibleBoxCount; i++) {
            Vector3f boxMin = visibleBoxes[i][0];
            Vector3f boxMax = visibleBoxes[i][1];
            if (boxIsAcceptable(boxMin.x, boxMin.y, boxMin.z, boxMax.x, boxMax.y, boxMax.z)) {
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
            int hitThreshold = (int) (PURGE_BOX_THRESHOLD * firstSecondAverage);
            int previousVisibleBoxCount = visibleBoxCount;
            while ((float) acceptableBoxes.get(visibleBoxCount - 1).hits < hitThreshold) {
                visibleBoxCount--;
                if (visibleBoxCount == 0)
                    break;
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

    @Override
    public void initWithFrustum(Frustum frustum) {
        super.initWithFrustum(frustum);
        this.maxVisibleBoxes = 5;

        // when the allowed number of boxes increases, recreate the array and copy over
        // the boxes
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
        expandBoxWaitCount = (int) (Math.random() * EXPAND_BOX_WAIT);
    }

    @Override
    public void collectDebugStrings(List<String> list) {
        int totalFrustumChecks = frustumCheckActualCount + frustumCheckBoxCount;
        list.add(String.format("%s Visible box count", visibleBoxCount));
        list.add(String.format("%s chunk frustum checks actual", frustumCheckActualCount));
        list.add(String.format("%s total frustum checks", totalFrustumChecks));
        list.add(String.format("%s chunk frustum checks potential", frustumCheckPotentialCount));
        list.add(String.format("%d%% fewer frustum checks",
                frustumCheckPotentialCount == 0 ? 0 : 100 - 100 * totalFrustumChecks / frustumCheckPotentialCount));
        list.add(String.format("%s additional box frustum checks", frustumCheckBoxCount));
        list.add(String.format("%s box tests", boxTestCount));
        list.add(String.format("%s boxes cleared", clearedBoxes));
        for (int i = 0; i < visibleBoxCount; i++) {
            list.add(String.format("%s hits on box %d", visibleBoxHits[i], i));
        }
    }
}

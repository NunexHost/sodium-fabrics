package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree;

import java.util.ArrayList;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

/**
 * test scenario: test world, 1991 events, total 538121 quads, 32 rd, 15 chunk
 * builder threads
 * 
 * at 128406c9743eab8ec90dfceeac34af6fe932af97
 * (baseline):
 * sort 15-23ns per quad avg, build 230-233ns per quad avg
 * 
 * at 2aabb0a7a6a54f139db3cc2beb83881219561678
 * (with compression of interval points as longs):
 * sort 15-23ns per quad avg, build 150-165ns per quad avg
 * 
 * at 4f1fb35495b3e5adab2bbc9823cbd6cbf2e5b438
 * (with sorting compressed interval points as longs):
 * sort 15-23ns per quad avg, build 130-140ns per quad avg
 */
public class TimingRecorder {
    static record TimedEvent(int size, long ns) {
    }

    private static final int WARMUP_COUNT = 500;
    private static ArrayList<TimingRecorder> recorders = new ArrayList<>();

    private ReferenceArrayList<TimedEvent> events = new ReferenceArrayList<>(1000);
    private boolean warmedUp = false;

    private final String name;
    private int remainingWarmup;
    private boolean printEvents;

    public TimingRecorder(String name, int warmupCount, boolean printEvents) {
        this.name = name;
        this.remainingWarmup = warmupCount;
        this.printEvents = printEvents;

        recorders.add(this);
    }

    public TimingRecorder(String name) {
        this(name, WARMUP_COUNT, false);
    }

    public void recordNow(int size, long startNanos) {
        this.recordDelta(size, System.nanoTime() - startNanos);
    }

    synchronized public void recordDelta(int size, long delta) {
        if (!this.warmedUp) {
            this.remainingWarmup--;
            if (this.remainingWarmup == 0) {
                System.out.println("Warmed up recorder " + this.name);
            }
            return;
        }

        this.events.add(new TimedEvent(size, delta));

        if (this.printEvents) {
            System.out.println("Event for " + this.name + ": " + size + " quads, " + delta + "ns " +
                    "(" + (delta / size) + "ns per quad)");
        }
    }

    public void print() {
        var builder = new StringBuilder();
        builder.append("size,ns\n");

        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;
        long totalSize = 0;

        for (var event : this.events) {
            builder.append(event.size).append(",").append(event.ns).append(";");
            totalTime += event.ns;
            minTime = Math.min(minTime, event.ns);
            maxTime = Math.max(maxTime, event.ns);
            totalSize += event.size;
        }

        int eventCount = this.events.size();
        System.out.println("Timings for " + this.name + ":");
        System.out.println("min " + minTime +
                "ns, max " + maxTime +
                "ns, avg " + (totalTime / eventCount) +
                "ns. Total size " + totalSize +
                ", avg size " + (totalSize / eventCount) +
                ". Avg time per quad " + (totalTime / totalSize) +
                "ns. Avg quads per event " + (totalSize / eventCount) +
                ". " + eventCount + " events.");
        // System.out.println(builder.toString());
    }

    private void resetAfterWarmup() {
        if (this.remainingWarmup <= 0) {
            if (!this.events.isEmpty()) {
                this.print();
            }

            this.warmedUp = true;
            System.out.println("Started recorder " + this.name);
        }

        this.events.clear();
    }

    public static void resetAll() {
        for (var recorder : recorders) {
            recorder.resetAfterWarmup();
        }
    }
}

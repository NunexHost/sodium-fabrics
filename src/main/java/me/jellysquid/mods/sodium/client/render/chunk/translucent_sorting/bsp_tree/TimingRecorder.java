package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

/**
 * Compression results on 1992 sections:
 * compression candidates 55084, compression performed 1202 (ratio: 2.1%)
 * uncompressed size 397665, compressed size 170944 (ratio: 42.9%)
 * Removing the compresson minimum size results in a total compression ratio of
 * 34% and a 92% success rate. This isn't much of an improvement, it seems the
 * large candidates make up most of the compressable data. Increasing the
 * minimum size to 16 lowers the success rate to 3.4% while the total
 * compression ratio is 39%.
 * 
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
 * 
 * at d4f220080c2bf980e8f920d4ad96e4c8be465db1
 * (fixed child partition planes not being added to workspace on node reuse):
 * rebuild with node reuse 120ns per quad avg,
 * rebuild without node reuse 202ns per quad avg
 * previously it was more like 105ns per quad avg but the child partition planes
 * were missing (though it wasn't noticeable in many situations)
 */
public class TimingRecorder {
    static record TimedEvent(int size, long ns) {
    }

    private static final int WARMUP_COUNT = 500;
    private static ArrayList<TimingRecorder> recorders = new ArrayList<>();
    private static HashMap<Counter, AtomicLong> counters = new HashMap<>();

    public static enum Counter {
        UNIQUE_TRIGGERS,

        QUADS,
        BSP_SECTIONS,

        COMPRESSION_CANDIDATES,
        COMPRESSION_SUCCESS,
        COMPRESSED_SIZE,
        UNCOMPRESSED_SIZE
    }

    private ReferenceArrayList<TimedEvent> events = new ReferenceArrayList<>(1000);
    private boolean warmedUp = false;

    private final String name;
    private int remainingWarmup;
    private boolean printEvents;
    private boolean printData;

    public TimingRecorder(String name, int warmupCount, boolean printEvents) {
        this.name = name;
        this.remainingWarmup = warmupCount;
        this.printEvents = printEvents;

        recorders.add(this);
    }

    public TimingRecorder(String name, int warmupCount) {
        this(name, warmupCount, false);
    }

    public TimingRecorder(String name) {
        this(name, WARMUP_COUNT);
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
            if (this.printData) {
                builder.append(event.size).append(",").append(event.ns).append(";");
            }
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

        if (this.printData) {
            System.out.println(builder.toString());
        }
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

    public static void incrementBy(Counter counter, long amount) {
        getCounter(counter).addAndGet(amount);
    }

    public static AtomicLong getCounter(Counter counter) {
        return counters.computeIfAbsent(counter, (c) -> new AtomicLong());
    }

    public static void resetAll() {
        for (var recorder : recorders) {
            recorder.resetAfterWarmup();
        }

        for (var key : counters.keySet()) {
            System.out.println(key + ": " + getCounter(key).get());
        }

        if (counters.containsKey(Counter.UNIQUE_TRIGGERS)
                && counters.containsKey(Counter.QUADS)
                && counters.containsKey(Counter.BSP_SECTIONS)) {
            System.out.println("Triggers per quad: " +
                    ((double) getCounter(Counter.UNIQUE_TRIGGERS).get() / getCounter(Counter.QUADS).get()));
            System.out.println("Triggers per section: " +
                    (getCounter(Counter.UNIQUE_TRIGGERS).get() / getCounter(Counter.BSP_SECTIONS).get()));
        }
        if (counters.containsKey(Counter.COMPRESSION_CANDIDATES)
                && counters.containsKey(Counter.COMPRESSION_SUCCESS)
                && counters.containsKey(Counter.COMPRESSED_SIZE)
                && counters.containsKey(Counter.UNCOMPRESSED_SIZE)) {
            System.out.println("Compressed size ratio: " +
                    ((double) getCounter(Counter.COMPRESSED_SIZE).get() / getCounter(Counter.UNCOMPRESSED_SIZE).get()));
            System.out.println("Compression success ratio: " +
                    ((double) getCounter(Counter.COMPRESSION_SUCCESS).get()
                            / getCounter(Counter.COMPRESSION_CANDIDATES).get()));
        }

        counters.clear();
    }
}

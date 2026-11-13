package uniandes.tsdl.itdroid.helper;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.simple.JSONObject;

/**
 * Wall time per phase plus a light sampler of the machine while ITDroid runs: the
 * {@code performance} and {@code environment} sections of {@code report.json}, which the web
 * report shows. The LLM server and the emulator are separate processes, so what is measured here
 * is the JVM itself (heap, CPU time) and the host as a whole (CPU load, memory in use).
 */
public class RunMetrics {

    /** Reported in {@code report.json} and by the web report; keep in sync with pom.xml. */
    public static final String VERSION = "1.0.0";

    private static RunMetrics current;

    private final long startedAtMs = System.currentTimeMillis();
    private final Map<String, Double> phases = new LinkedHashMap<>();
    private final Map<String, Long> open = new LinkedHashMap<>();
    private final Sampler sampler = new Sampler();

    private RunMetrics() {
    }

    /** Starts the run clock and the sampler (idempotent). */
    public static synchronized RunMetrics start() {
        if (current == null) {
            current = new RunMetrics();
            current.sampler.setDaemon(true);
            current.sampler.start();
        }
        return current;
    }

    public static synchronized RunMetrics get() {
        return current;
    }

    public synchronized void begin(String phase) {
        open.put(phase, System.nanoTime());
    }

    public synchronized void end(String phase) {
        Long t0 = open.remove(phase);
        if (t0 != null) {
            phases.merge(phase, (System.nanoTime() - t0) / 1e9, Double::sum);
        }
    }

    public String startedAtIso() {
        return Instant.ofEpochMilli(startedAtMs).toString();
    }

    public double elapsedSec() {
        return (System.currentTimeMillis() - startedAtMs) / 1000.0;
    }

    /**
     * The performance section: total and per-phase seconds, the LLM totals handed in by the
     * translator, and what the sampler saw of the JVM and the host.
     */
    @SuppressWarnings("unchecked")
    public synchronized JSONObject performance(JSONObject llmStats) {
        for (String p : new ArrayList<>(open.keySet())) {
            end(p);   // phases cut short by an abort still get their time
        }
        JSONObject o = new JSONObject();
        o.put("totalSec", round(elapsedSec()));
        JSONObject ph = new JSONObject();
        phases.forEach((k, v) -> ph.put(k, round(v)));
        o.put("phases", ph);
        if (llmStats != null) {
            o.put("llm", llmStats);
        }
        JSONObject proc = new JSONObject();
        com.sun.management.OperatingSystemMXBean os = osBean();
        if (os != null) {
            proc.put("cpuSeconds", round(os.getProcessCpuTime() / 1e9));
        }
        proc.put("peakHeapMB", round(sampler.heapPeakMB));
        proc.put("samples", sampler.samples);
        proc.put("sampleIntervalSec", Sampler.INTERVAL_MS / 1000.0);
        o.put("jvm", proc);
        JSONObject host = new JSONObject();
        if (sampler.cpuLoadSamples > 0) {
            host.put("cpuLoadMeanPct", round(100.0 * sampler.cpuLoadSum / sampler.cpuLoadSamples));
            host.put("cpuLoadPeakPct", round(100.0 * sampler.cpuLoadPeak));
        }
        host.put("memoryUsedPeakMB", round(sampler.hostMemUsedPeakMB));
        host.put("memoryUsedMeanMB", sampler.samples > 0 ? round(sampler.hostMemUsedSumMB / sampler.samples) : 0);
        o.put("host", host);
        o.put("note", "jvm = the ITDroid process; host = the whole machine (includes the emulator and the LLM server, which run as separate processes)");
        return o;
    }

    /** The environment section: host, device, translation engine and the run configuration. */
    @SuppressWarnings("unchecked")
    public static JSONObject environment(JSONObject device, JSONObject llm, JSONObject config) {
        JSONObject o = new JSONObject();
        JSONObject host = new JSONObject();
        host.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        host.put("arch", System.getProperty("os.arch"));
        host.put("cores", Runtime.getRuntime().availableProcessors());
        com.sun.management.OperatingSystemMXBean os = osBean();
        if (os != null) {
            host.put("totalMemoryMB", round(os.getTotalMemorySize() / 1048576.0));
        }
        host.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        // No user name and no host name: the results folder is meant to be shared.
        o.put("host", host);
        o.put("itdroidVersion", VERSION);
        if (device != null) {
            o.put("device", device);
        }
        if (llm != null) {
            o.put("llm", llm);
        }
        if (config != null) {
            o.put("config", config);
        }
        return o;
    }

    private static com.sun.management.OperatingSystemMXBean osBean() {
        java.lang.management.OperatingSystemMXBean b = ManagementFactory.getOperatingSystemMXBean();
        return b instanceof com.sun.management.OperatingSystemMXBean ? (com.sun.management.OperatingSystemMXBean) b : null;
    }

    static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Samples the JVM heap and the host CPU/memory every couple of seconds; cheap enough to run always. */
    private static final class Sampler extends Thread {
        static final long INTERVAL_MS = 2000;
        volatile long samples;
        volatile double heapPeakMB;
        volatile double cpuLoadSum;
        volatile double cpuLoadPeak;
        volatile int cpuLoadSamples;
        volatile double hostMemUsedPeakMB;
        volatile double hostMemUsedSumMB;

        Sampler() {
            super("itdroid-metrics");
        }

        @Override
        public void run() {
            com.sun.management.OperatingSystemMXBean os = osBean();
            Runtime rt = Runtime.getRuntime();
            while (true) {
                try {
                    double heap = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
                    heapPeakMB = Math.max(heapPeakMB, heap);
                    if (os != null) {
                        double load = os.getCpuLoad();   // -1 when the platform cannot tell
                        if (load >= 0) {
                            cpuLoadSum += load;
                            cpuLoadPeak = Math.max(cpuLoadPeak, load);
                            cpuLoadSamples++;
                        }
                        double used = (os.getTotalMemorySize() - os.getFreeMemorySize()) / 1048576.0;
                        hostMemUsedPeakMB = Math.max(hostMemUsedPeakMB, used);
                        hostMemUsedSumMB += used;
                    }
                    samples++;
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    // a failing sample must never disturb the run
                }
            }
        }
    }
}

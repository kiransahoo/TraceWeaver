package com.tracer.genericagent.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.common.Attributes;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;

public class SystemMetrics {

    private static boolean registered = false;

    public static void registerGauges() {
        if (registered) return;
        registered = true;

        try {
            Meter meter = GlobalOpenTelemetry.getMeter("generic-agent-meter");
            OperatingSystemMXBean osBean =
                    (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

            // CPU load - negative values can occur before the first measurement,
            // hence we ensure non-negative values
            meter.gaugeBuilder("system.process.cpu.load")
                    .setDescription("Process CPU load [0..1]")
                    .buildWithCallback(obs -> {
                        double val = osBean.getProcessCpuLoad();
                        // Prevent negative values which can occur before first measurement
                        if (val < 0) val = 0.0;
                        obs.record(val, Attributes.empty());
                    });

            // Memory usage
            meter.gaugeBuilder("runtime.memory.used")
                    .setDescription("Used memory in bytes")
                    .buildWithCallback(obs -> {
                        long totalMem = Runtime.getRuntime().totalMemory();
                        long freeMem = Runtime.getRuntime().freeMemory();
                        obs.record((double)(totalMem - freeMem), Attributes.empty());
                    });

            // Add memory pools
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            meter.gaugeBuilder("runtime.memory.heap")
                    .setDescription("Heap memory usage")
                    .buildWithCallback(obs -> {
                        long used = memoryBean.getHeapMemoryUsage().getUsed();
                        obs.record((double)used, Attributes.empty());
                    });

            // Thread metrics
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            meter.gaugeBuilder("runtime.threads.count")
                    .setDescription("Thread count")
                    .buildWithCallback(obs -> {
                        int threadCount = threadBean.getThreadCount();
                        obs.record(threadCount, Attributes.empty());
                    });

            // GC metrics
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String gcName = gcBean.getName().replace(" ", "_").toLowerCase();

                meter.gaugeBuilder("runtime.gc." + gcName + ".count")
                        .setDescription("GC collection count for " + gcName)
                        .buildWithCallback(obs -> {
                            long count = gcBean.getCollectionCount();
                            if (count >= 0) { // Some GC beans return -1 if count is undefined
                                obs.record(count, Attributes.empty());
                            }
                        });
            }

            System.out.println("[SystemMetrics] Successfully registered system metrics gauges");
        } catch (Throwable t) {
            System.err.println("[SystemMetrics] Error registering metrics: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
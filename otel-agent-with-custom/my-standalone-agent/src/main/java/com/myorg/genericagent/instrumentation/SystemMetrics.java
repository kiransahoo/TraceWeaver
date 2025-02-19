package com.myorg.genericagent.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.common.Attributes;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

public class SystemMetrics {

    private static boolean registered = false;

    public static void registerGauges() {
        if (registered) return;
        registered = true;

        Meter meter = GlobalOpenTelemetry.getMeter("generic-agent-meter");
        OperatingSystemMXBean osBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // CPU load
        meter.gaugeBuilder("system.process.cpu.load")
                .setDescription("Process CPU load [0..1]")
                .buildWithCallback(obs -> {
                    double val = osBean.getProcessCpuLoad();
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
    }
}

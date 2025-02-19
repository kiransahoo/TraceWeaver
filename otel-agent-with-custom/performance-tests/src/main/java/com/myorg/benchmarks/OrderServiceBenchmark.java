package com.myorg.benchmarks;

import com.myorg.app.OrderService;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * A more "profound" JMH benchmark:
 * - multiple threads (Threads=4)
 * - multiple forks (Fork=2)
 * - longer warmup/measurement
 * - random logic in subProcess to simulate real work
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput) // or Mode.SampleTime, Mode.AverageTime
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Threads(4) // concurrency
@Fork(2)    // run multiple JVM forks
@OutputTimeUnit(TimeUnit.SECONDS)
public class OrderServiceBenchmark {

    private OrderService svc;
    private Random random;

    @Setup
    public void setup() {
        svc = new OrderService();
        random = new Random(1234L);
    }

    @Benchmark
    public void benchmarkProcessOrder() {
        String orderId = "BENCH-" + random.nextInt(100_000);
        svc.processOrder(orderId);
    }
}

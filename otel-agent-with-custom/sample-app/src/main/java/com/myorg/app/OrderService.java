package com.myorg.app;

/**
 * Two methods: processOrder -> subProcess
 * So we see parent->child or sequential spans.
 */
public class OrderService {
    public void processOrder(String orderId) {
        System.out.println("[OrderService] Processing order: " + orderId);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subProcess(orderId);
    }

    public void subProcess(String orderId) {
        System.out.println("[OrderService] subProcess for order: " + orderId);

        // e.g., do a quick random sum
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += randomMath(i);
        }
        // pretend we do something with sum
        if (sum == 123456789) {
            System.out.println("Impossible check, just to avoid optimization");
        }

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long randomMath(int i) {
        return (long) (Math.sin(i) * Math.cos(i + 0.5) * 100_000);
    }
}

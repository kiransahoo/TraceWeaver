package com.myorg.app;

public class SampleApp {
    public static void main(String[] args) {
        OrderService svc = new OrderService();
        svc.processOrder("A-123");
        svc.processOrder("B-456");
        System.out.println("[SampleApp] Done.");
    }
}

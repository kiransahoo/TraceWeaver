package com.example.service2.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProcessingService {

    private final CalculationService calculationService;

    @Autowired
    public ProcessingService(CalculationService calculationService) {
        this.calculationService = calculationService;
    }

    public String processIncomingData(String data) {
        // This method will be traced by the agent
        System.out.println("Processing incoming data in ProcessingService: " + data);
        
        // Add a small delay to make the trace more visible
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Call internal method to demonstrate nested spans
        String transformedData = transformData(data);
        
        // Call calculation service to demonstrate service-to-service tracing
        return calculationService.performCalculation(transformedData);
    }
    
    private String transformData(String data) {
        // This method will also be traced by the agent
        System.out.println("Transforming data in ProcessingService");
        
        // Simulate processing time
        try {
            Thread.sleep(75);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return data + " (transformed)";
    }
}

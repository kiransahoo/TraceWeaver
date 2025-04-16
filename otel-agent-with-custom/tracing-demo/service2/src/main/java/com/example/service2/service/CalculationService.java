package com.example.service2.service;

import org.springframework.stereotype.Service;

@Service
public class CalculationService {

    public String performCalculation(String input) {
        // This method will be traced by the agent
        System.out.println("Performing calculation in CalculationService on: " + input);
        
        // Simulate processing time
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Call internal method to demonstrate another nested span
        analyzeData(input);
        
        return "Calculated result for: " + input;
    }
    
    private void analyzeData(String data) {
        // This method will also be traced by the agent
        System.out.println("Analyzing data in CalculationService");
        
        // Simulate analysis
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

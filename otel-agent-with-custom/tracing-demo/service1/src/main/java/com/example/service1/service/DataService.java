package com.example.service1.service;

import org.springframework.stereotype.Service;

@Service
public class DataService {

    public String storeData(String data) {
        // This method will be traced by the agent
        System.out.println("Storing data in DataService: " + data);
        
        // Simulate processing time
        try {
            Thread.sleep(75);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Call internal method to demonstrate another nested span
        validateData(data);
        
        return "Stored: " + data;
    }
    
    private void validateData(String data) {
        // This method will also be traced by the agent
        System.out.println("Validating data in DataService");
        
        // Simulate validation
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

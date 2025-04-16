package com.example.service1.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BusinessService {

    private final DataService dataService;

    @Autowired
    public BusinessService(DataService dataService) {
        this.dataService = dataService;
    }

    public String processRequest(String requestData) {
        // This method will be traced by the agent
        System.out.println("Processing request in BusinessService: " + requestData);
        
        // Add a small delay to make the trace more visible
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Call another service method to demonstrate nested spans
        String enrichedData = enrichData(requestData);
        
        // Call the data service to demonstrate service-to-service tracing
        return dataService.storeData(enrichedData);
    }
    
    private String enrichData(String data) {
        // This method will also be traced by the agent
        System.out.println("Enriching data in BusinessService");
        
        // Simulate processing time
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return data + " (enriched)";
    }
}

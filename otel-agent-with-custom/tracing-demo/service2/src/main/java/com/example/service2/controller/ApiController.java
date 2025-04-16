package com.example.service2.controller;

import com.example.service2.service.ProcessingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {

    private final ProcessingService processingService;

    @Autowired
    public ApiController(ProcessingService processingService) {
        this.processingService = processingService;
    }

    @GetMapping("/api/handle/{data}")
    public String handleRequest(@PathVariable String data) {
        // This controller method will be traced by the agent
        System.out.println("Service2 received request with data: " + data);
        
        // The trace context from Service1 will be automatically
        // propagated to this span due to the agent
        
        // Process the request through our service layer
        String result = processingService.processIncomingData(data);
        
        return result;
    }
}

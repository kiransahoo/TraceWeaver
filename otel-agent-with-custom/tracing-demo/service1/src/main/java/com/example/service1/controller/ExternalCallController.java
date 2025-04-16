package com.example.service1.controller;

import com.example.service1.service.BusinessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class ExternalCallController {

    private final RestTemplate restTemplate;
    private final BusinessService businessService;
    
    @Value("${service2.url:http://localhost:8082}")
    private String service2Url;

    @Autowired
    public ExternalCallController(RestTemplate restTemplate, BusinessService businessService) {
        this.restTemplate = restTemplate;
        this.businessService = businessService;
    }

    @GetMapping("/api/process/{data}")
    public ResponseEntity<String> processData(@PathVariable String data) {
        // This controller method will be traced by the agent
        
        // First process the data internally
        String processedData = businessService.processRequest(data);
        
        // Then call service2 - trace context will be propagated
        String service2Response = callService2(processedData);
        
        // Return combined result
        return ResponseEntity.ok("Service1 processed: " + processedData + 
                                 ", Service2 response: " + service2Response);
    }
    
    private String callService2(String data) {
        // This method call will be traced by the agent
        String url = service2Url + "/api/handle/" + data;
        
        System.out.println("Calling Service2 at: " + url);
        
        // The RestTemplate will propagate the trace context because of the agent
        return restTemplate.getForObject(url, String.class);
    }
}

package com.test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // Print Java version for verification
        System.out.println("Java version: " + System.getProperty("java.version"));
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        // FIX FOR ZSCALER: Bypass proxy for localhost
        System.setProperty("http.proxyHost", "");
        System.setProperty("https.proxyHost", "");
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                if (connection instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) connection).setHostnameVerifier((hostname, session) ->
                            hostname.equals("localhost") || hostname.equals("127.0.0.1"));
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);

        RestTemplate restTemplate = new RestTemplate(factory);

        // Add request/response logging interceptor
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new LoggingInterceptor());
        restTemplate.setInterceptors(interceptors);

        return restTemplate;
    }

    // Interceptor to log HTTP headers for debugging context propagation
    public static class LoggingInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            System.out.println("\n=== HTTP Request Headers ===");
            request.getHeaders().forEach((key, value) -> {
                System.out.println(key + ": " + value);
            });

            ClientHttpResponse response = execution.execute(request, body);

            System.out.println("\n=== HTTP Response Headers ===");
            response.getHeaders().forEach((key, value) -> {
                System.out.println(key + ": " + value);
            });

            return response;
        }
    }

    // ============= CONTROLLERS =============

    @RestController
    public static class TestController {

        @Autowired
        private RestTemplate restTemplate;

        @GetMapping("/test")
        public String test() {
            System.out.println("\n=== /test endpoint called ===");

            // First level method - should use parent context
            firstLevelMethod();

            try {
                // Give time for first level to be processed
                TimeUnit.SECONDS.sleep(1);

                // Make HTTP call to another endpoint
                // This should propagate trace context via HTTP headers
                System.out.println("\n=== Making HTTP call to /downstream ===");
                ResponseEntity<String> response = restTemplate.getForEntity(
                        "http://localhost:6070/downstream", String.class);

                System.out.println("=== /test completed with status: " +
                        response.getStatusCode() + ", body: " + response.getBody() + " ===");

                return "Trace test completed: " + response.getBody();
            } catch (Exception e) {
                System.err.println("Error in test endpoint: " + e.getMessage());
                e.printStackTrace();
                return "Error: " + e.getMessage();
            }
        }

        @GetMapping("/downstream")
        public String downstream() {
            System.out.println("\n=== /downstream endpoint called ===");

            // This should continue the same trace from the parent request
            secondLevelMethod();

            System.out.println("=== /downstream completed ===");
            return "Downstream processed";
        }

        public void firstLevelMethod() {
            System.out.println("=== firstLevelMethod called ===");

            // Traced method - should be captured by your agent
            try {
                TimeUnit.MILLISECONDS.sleep(500);
                System.out.println("First level method completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void secondLevelMethod() {
            System.out.println("=== secondLevelMethod called ===");

            // Traced method - should be captured by your agent
            try {
                TimeUnit.MILLISECONDS.sleep(500);
                System.out.println("Second level method completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @RestController
    public static class UserController {

        @Autowired
        private UserService userService;

        @GetMapping("/users")
        public List<Map<String, Object>> getUsers() {
            System.out.println("\n=== /users endpoint called ===");

            // Call service layer - trace should propagate
            List<Map<String, Object>> users = userService.findAllUsers();

            System.out.println("=== /users completed with " + users.size() + " users ===");
            return users;
        }

        @GetMapping("/users/{id}")
        public Map<String, Object> getUser(@PathVariable String id) {
            System.out.println("\n=== /users/" + id + " endpoint called ===");

            // Call service layer with parameter - trace should propagate
            Map<String, Object> user = userService.findUserById(id);

            System.out.println("=== /users/" + id + " completed ===");
            return user;
        }

        @GetMapping("/users/{id}/profile")
        public Map<String, Object> getUserProfile(@PathVariable String id) {
            System.out.println("\n=== /users/" + id + "/profile endpoint called ===");

            // Call service layer for enriched data - should trigger external calls
            Map<String, Object> profile = userService.getUserProfileWithExternalData(id);

            System.out.println("=== /users/" + id + "/profile completed ===");
            return profile;
        }

        @GetMapping("/external-api")
        public Map<String, Object> externalApi() {
            System.out.println("\n=== /external-api endpoint called (simulating external service) ===");

            // Simulate external API response
            Map<String, Object> response = new HashMap<>();
            response.put("data", "External API data");
            response.put("timestamp", System.currentTimeMillis());
            response.put("status", "success");

            // Simulate processing time
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("=== /external-api completed ===");
            return response;
        }
    }

    // ============= SERVICE LAYER =============

    @Service
    public static class UserService {

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private ExternalApiService externalApiService;

        @Autowired
        private NotificationService notificationService;

        public List<Map<String, Object>> findAllUsers() {
            System.out.println("=== UserService.findAllUsers called ===");

            // Call repository layer - trace should propagate
            List<Map<String, Object>> users = userRepository.findAll();

            // Call another service method - trace should propagate
            enrichUserData(users);

            System.out.println("UserService.findAllUsers completed with " + users.size() + " users");
            return users;
        }

        public Map<String, Object> findUserById(String id) {
            System.out.println("=== UserService.findUserById called for ID: " + id + " ===");

            // Call repository layer - trace should propagate
            Map<String, Object> user = userRepository.findById(id);

            // Call notification service - trace should propagate
            notificationService.logUserAccess(id);

            System.out.println("UserService.findUserById completed for ID: " + id);
            return user;
        }

        public Map<String, Object> getUserProfileWithExternalData(String id) {
            System.out.println("=== UserService.getUserProfileWithExternalData called for ID: " + id + " ===");

            // Call repository layer
            Map<String, Object> user = userRepository.findById(id);

            // Call external API service - this will make HTTP calls
            Map<String, Object> externalData = externalApiService.fetchUserExternalData(id);

            // Merge data
            Map<String, Object> profile = new HashMap<>(user);
            profile.put("externalData", externalData);

            System.out.println("UserService.getUserProfileWithExternalData completed for ID: " + id);
            return profile;
        }

        public void enrichUserData(List<Map<String, Object>> users) {
            System.out.println("=== UserService.enrichUserData called ===");

            // Simulate data enrichment
            for (Map<String, Object> user : users) {
                user.put("enriched", true);
                user.put("enrichedAt", System.currentTimeMillis());
            }

            // Simulate processing time
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("UserService.enrichUserData completed");
        }
    }

    @Service
    public static class ExternalApiService {

        @Autowired
        private RestTemplate restTemplate;

        public Map<String, Object> fetchUserExternalData(String userId) {
            System.out.println("=== ExternalApiService.fetchUserExternalData called for user: " + userId + " ===");

            try {
                // Make HTTP call to external API - trace should propagate
                System.out.println("Making HTTP call to external API...");
                ResponseEntity<Map> response = restTemplate.getForEntity(
                        "http://localhost:6070/external-api", Map.class);

                Map<String, Object> externalData = response.getBody();
                externalData.put("userId", userId);
                externalData.put("fetchedAt", System.currentTimeMillis());

                // Call helper method - trace should propagate
                processExternalData(externalData);

                System.out.println("ExternalApiService.fetchUserExternalData completed for user: " + userId);
                return externalData;

            } catch (Exception e) {
                System.err.println("Error fetching external data: " + e.getMessage());

                // Return fallback data
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("error", "External API unavailable");
                fallback.put("userId", userId);
                return fallback;
            }
        }

        public void processExternalData(Map<String, Object> data) {
            System.out.println("=== ExternalApiService.processExternalData called ===");

            // Simulate data processing
            data.put("processed", true);
            data.put("processedAt", System.currentTimeMillis());

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("ExternalApiService.processExternalData completed");
        }
    }

    @Service
    public static class NotificationService {

        public void logUserAccess(String userId) {
            System.out.println("=== NotificationService.logUserAccess called for user: " + userId + " ===");

            // Simulate logging/notification processing
            validateUser(userId);
            recordAccess(userId);

            System.out.println("NotificationService.logUserAccess completed for user: " + userId);
        }

        public void validateUser(String userId) {
            System.out.println("=== NotificationService.validateUser called for user: " + userId + " ===");

            // Simulate user validation
            try {
                TimeUnit.MILLISECONDS.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("NotificationService.validateUser completed for user: " + userId);
        }

        public void recordAccess(String userId) {
            System.out.println("=== NotificationService.recordAccess called for user: " + userId + " ===");

            // Simulate access recording
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("NotificationService.recordAccess completed for user: " + userId);
        }
    }

    // ============= REPOSITORY LAYER =============

    @Repository
    public static class UserRepository {

        public List<Map<String, Object>> findAll() {
            System.out.println("=== UserRepository.findAll called ===");

            // Simulate database call
            try {
                TimeUnit.MILLISECONDS.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Create mock users
            List<Map<String, Object>> users = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                Map<String, Object> user = new HashMap<>();
                user.put("id", String.valueOf(i));
                user.put("name", "User " + i);
                user.put("email", "user" + i + "@example.com");
                users.add(user);
            }

            System.out.println("UserRepository.findAll completed with " + users.size() + " users");
            return users;
        }

        public Map<String, Object> findById(String id) {
            System.out.println("=== UserRepository.findById called for ID: " + id + " ===");

            // Simulate database call
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Create mock user
            Map<String, Object> user = new HashMap<>();
            user.put("id", id);
            user.put("name", "User " + id);
            user.put("email", "user" + id + "@example.com");
            user.put("createdAt", System.currentTimeMillis());

            System.out.println("UserRepository.findById completed for ID: " + id);
            return user;
        }
    }
}
package com.jobcraft.orchestrator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobcraft.orchestrator.model.Job;
import com.jobcraft.orchestrator.model.JobPriority;
import com.jobcraft.orchestrator.service.JobService;
import com.jobcraft.orchestrator.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    @Autowired
    public JobController(JobService jobService, RateLimiterService rateLimiterService) {
        this.jobService = jobService;
        this.rateLimiterService = rateLimiterService;
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping
    public ResponseEntity<List<Job>> getAllJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    @PostMapping
    public ResponseEntity<?> submitJob(@RequestBody(required = false) JobSubmissionRequest request) {
        if (request == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Request body cannot be empty");
        }

        if (request.getType() == null || request.getType().trim().isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Job type is required");
        }

        String sanitizedType = request.getType().trim().toUpperCase();
        if (!sanitizedType.matches("^[A-Z0-9_-]{2,50}$")) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Invalid job type format. Must contain only alphanumeric characters, underscores, or hyphens (2-50 chars)");
        }

        // Validate JSON payload
        String payload = request.getPayload();
        if (payload != null && !payload.trim().isEmpty()) {
            try {
                objectMapper.readTree(payload);
            } catch (Exception e) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Payload must be a valid JSON string: " + e.getMessage());
            }
        } else {
            payload = "{}";
        }

        // Validate priority
        JobPriority priority = JobPriority.MEDIUM;
        if (request.getPriority() != null && !request.getPriority().trim().isEmpty()) {
            try {
                priority = JobPriority.valueOf(request.getPriority().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Invalid priority level. Allowed values: LOW, MEDIUM, HIGH");
            }
        }

        // Validate maxRetries
        int maxRetries = 3;
        if (request.getMaxRetries() != null) {
            if (request.getMaxRetries() < 1 || request.getMaxRetries() > 10) {
                return errorResponse(HttpStatus.BAD_REQUEST, "maxRetries must be between 1 and 10");
            }
            maxRetries = request.getMaxRetries();
        }

        Job job = jobService.submitJob(
                sanitizedType,
                payload,
                priority,
                maxRetries
        );
        return ResponseEntity.ok(job);
    }

    @PostMapping("/ai-route")
    public ResponseEntity<?> routeAndSubmitJob(@RequestBody(required = false) AiRoutingRequest request,
                                               HttpServletRequest httpRequest) {
        if (request == null || request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Prompt is required for AI routing");
        }

        if (request.getPrompt().trim().length() > 2000) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Prompt cannot exceed 2000 characters");
        }

        String clientIp = getClientIp(httpRequest);
        if (!rateLimiterService.tryAcquire(clientIp)) {
            return errorResponse(HttpStatus.TOO_MANY_REQUESTS, "AI Route rate limit exceeded (" + rateLimiterService.getRequestsPerMinute() + " requests/minute). Please wait before submitting another request.");
        }

        Job job = jobService.routeAndSubmitJob(request.getPrompt().trim());
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retryJob(@PathVariable("id") UUID id) {
        try {
            Job job = jobService.retryJob(id);
            return ResponseEntity.ok(job);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.NOT_FOUND, "Job not found with ID: " + id);
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteJob(@PathVariable("id") UUID id) {
        try {
            jobService.deleteJob(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return errorResponse(HttpStatus.NOT_FOUND, "Job not found with ID: " + id);
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(jobService.getStats());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("error", status.getReasonPhrase());
        errorBody.put("message", message);
        errorBody.put("status", status.value());
        return ResponseEntity.status(status).body(errorBody);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    // DTO Classes
    public static class JobSubmissionRequest {
        private String type;
        private String payload;
        private String priority;
        private Integer maxRetries;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getPayload() { return payload; }
        public void setPayload(String payload) { this.payload = payload; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public Integer getMaxRetries() { return maxRetries; }
        public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class AiRoutingRequest {
        private String prompt;

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
    }
}

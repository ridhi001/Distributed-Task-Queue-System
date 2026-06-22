package com.jobcraft.orchestrator.controller;

import com.jobcraft.orchestrator.model.Job;
import com.jobcraft.orchestrator.model.JobPriority;
import com.jobcraft.orchestrator.service.JobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = "*")
public class JobController {

    private final JobService jobService;

    @Autowired
    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public ResponseEntity<List<Job>> getAllJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    @PostMapping
    public ResponseEntity<Job> submitJob(@RequestBody JobSubmissionRequest request) {
        JobPriority priority = JobPriority.MEDIUM;
        if (request.getPriority() != null) {
            try {
                priority = JobPriority.valueOf(request.getPriority().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Keep default MEDIUM
            }
        }
        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : 3;
        
        Job job = jobService.submitJob(
                request.getType(),
                request.getPayload(),
                priority,
                maxRetries
        );
        return ResponseEntity.ok(job);
    }

    @PostMapping("/ai-route")
    public ResponseEntity<Job> routeAndSubmitJob(@RequestBody AiRoutingRequest request) {
        if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Job job = jobService.routeAndSubmitJob(request.getPrompt());
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Job> retryJob(@PathVariable("id") UUID id) {
        try {
            Job job = jobService.retryJob(id);
            return ResponseEntity.ok(job);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable("id") UUID id) {
        try {
            jobService.deleteJob(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(jobService.getStats());
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

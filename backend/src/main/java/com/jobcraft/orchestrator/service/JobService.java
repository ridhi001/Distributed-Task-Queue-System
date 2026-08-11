package com.jobcraft.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobcraft.orchestrator.model.Job;
import com.jobcraft.orchestrator.model.JobPriority;
import com.jobcraft.orchestrator.model.JobStatus;
import com.jobcraft.orchestrator.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class JobService {

    private static final Logger logger = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final GeminiClient geminiClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public JobService(JobRepository jobRepository, GeminiClient geminiClient, SimpMessagingTemplate messagingTemplate) {
        this.jobRepository = jobRepository;
        this.geminiClient = geminiClient;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves all jobs in the database.
     */
    public List<Job> getAllJobs() {
        return jobRepository.findAll();
    }

    /**
     * Submits a standard job.
     */
    @Transactional
    public Job submitJob(String type, String payload, JobPriority priority, int maxRetries) {
        Job job = new Job(type, payload, priority, maxRetries);
        Job saved = jobRepository.save(job);
        broadcastUpdate(saved);
        logger.info("Job submitted: {} (ID: {})", type, saved.getId());
        return saved;
    }

    /**
     * Agentic Router: Routes a natural language request, parses it into job specs, and submits it.
     */
    public Job routeAndSubmitJob(String userPrompt) {
        logger.info("Routing natural language request: '{}'", userPrompt);
        String routingJsonResponse = geminiClient.routeJob(userPrompt);

        String type = "GENERATE_REPORT";
        JobPriority priority = JobPriority.MEDIUM;
        String payload = "{}";

        try {
            JsonNode root = objectMapper.readTree(routingJsonResponse);
            if (root.has("jobType")) {
                type = root.get("jobType").asText().toUpperCase();
            }
            if (root.has("priority")) {
                priority = JobPriority.valueOf(root.get("priority").asText().toUpperCase());
            }
            if (root.has("payload")) {
                payload = objectMapper.writeValueAsString(root.get("payload"));
            }
        } catch (Exception e) {
            logger.error("Failed to parse AI Router response: '{}'. Using fallback values.", routingJsonResponse, e);
        }

        return submitJob(type, payload, priority, 3);
    }

    /**
     * Executes a job (simulates work and handles success/retry/failure lifecycle).
     */
    public void executeJob(Job job) {
        // 1. Mark as running
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        job = jobRepository.save(job);
        broadcastUpdate(job);

        logger.info("Executing Job {} of type {} (Retry count: {})", job.getId(), job.getType(), job.getRetryCount());

        try {
            // Simulate work latency based on Job Type
            long workDuration = 2000; // default 2 seconds
            if ("RESIZE_IMAGE".equalsIgnoreCase(job.getType())) {
                workDuration = 3500;
            } else if ("GENERATE_REPORT".equalsIgnoreCase(job.getType())) {
                workDuration = 5000;
            }
            Thread.sleep(workDuration);

            // Check if job should fail based on payload configuration
            checkForForcedFailure(job);

            // Success state
            job.setStatus(JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            job = jobRepository.save(job);
            broadcastUpdate(job);
            logger.info("Job completed successfully: {}", job.getId());

        } catch (Exception e) {
            logger.warn("Job failed during execution: {} - Error: {}", job.getId(), e.getMessage());
            handleJobFailure(job, e.getMessage());
        }
    }

    private void checkForForcedFailure(Job job) throws Exception {
        if (job.getPayload() != null) {
            JsonNode payloadNode = objectMapper.readTree(job.getPayload());
            if (payloadNode.has("forceFailure") && payloadNode.get("forceFailure").asBoolean()) {
                // Raise errors based on type
                if ("SEND_EMAIL".equalsIgnoreCase(job.getType())) {
                    throw new RuntimeException("SMTP Connection Timeout: Failed to connect to mail server at smtp.gmail.com:587.");
                } else if ("RESIZE_IMAGE".equalsIgnoreCase(job.getType())) {
                    throw new RuntimeException("Image Corruption: The input file does not contain valid PNG or JPEG magic bytes.");
                } else if ("GENERATE_REPORT".equalsIgnoreCase(job.getType())) {
                    throw new RuntimeException("Database Connection Failed: Unable to fetch row count for report generation due to deadlock.");
                } else {
                    throw new RuntimeException("Execution Error: Job execution was aborted due to forced failure command.");
                }
            }
        }
    }

    private void handleJobFailure(Job job, String errorMsg) {
        job.setErrorMessage(errorMsg);

        if (job.getRetryCount() < job.getMaxRetries()) {
            // Schedule Retry with Exponential Backoff
            job.setRetryCount(job.getRetryCount() + 1);
            job.setStatus(JobStatus.RETRYING);

            int backoffSeconds = (int) Math.pow(2, job.getRetryCount());
            job.setScheduledAt(LocalDateTime.now().plusSeconds(backoffSeconds));

            job = jobRepository.save(job);
            broadcastUpdate(job);
            logger.info("Job {} scheduled for retry in {} seconds (Attempt {}/{})",
                    job.getId(), backoffSeconds, job.getRetryCount(), job.getMaxRetries());
        } else {
            // Move to Dead Letter Queue (DLQ)
            job.setStatus(JobStatus.DLQ);
            job.setCompletedAt(LocalDateTime.now());
            final Job savedJob = jobRepository.save(job);
            broadcastUpdate(savedJob);
            logger.info("Job {} moved to DLQ. Executing AI Failure Analysis.", savedJob.getId());

            final UUID jobId = savedJob.getId();
            final String type = savedJob.getType();
            final String payload = savedJob.getPayload();

            // Asynchronously run Gemini AI Failure Analyzer
            CompletableFuture.runAsync(() -> {
                try {
                    String suggestion = geminiClient.analyzeFailure(type, payload, errorMsg);
                    // Reload job to avoid stale state issues in separate thread
                    Job freshJob = jobRepository.findById(jobId).orElse(null);
                    if (freshJob != null) {
                        freshJob.setAiAnalysis(suggestion);
                        jobRepository.save(freshJob);
                        broadcastUpdate(freshJob);
                        logger.info("AI Analysis completed for failed job {}", jobId);
                    }
                } catch (Exception e) {
                    logger.error("AI Failure Analyzer thread failed for job {}", jobId, e);
                }
            });
        }
    }

    /**
     * Retries a failed job from DLQ.
     */
    @Transactional
    public Job retryJob(UUID id) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + id));

        if (job.getStatus() != JobStatus.DLQ && job.getStatus() != JobStatus.FAILED) {
            throw new IllegalStateException("Only DLQ or FAILED jobs can be retried manually.");
        }

        job.setStatus(JobStatus.PENDING);
        job.setRetryCount(0);
        job.setErrorMessage(null);
        job.setAiAnalysis(null);
        job.setScheduledAt(LocalDateTime.now());
        job.setStartedAt(null);
        job.setCompletedAt(null);

        Job saved = jobRepository.save(job);
        broadcastUpdate(saved);
        logger.info("Job {} manually released from DLQ/FAILED for reprocessing.", id);
        return saved;
    }

    /**
     * Deletes a job.
     */
    @Transactional
    public void deleteJob(UUID id) {
        jobRepository.deleteById(id);
        // Broadcast a delete event (we can pass a payload with action: 'delete', id: id)
        Map<String, Object> message = new HashMap<>();
        message.put("action", "DELETE");
        message.put("id", id.toString());
        messagingTemplate.convertAndSend("/topic/jobs", (Object) message);
        logger.info("Job deleted from system: {}", id);
    }

    /**
     * Calculates system execution stats.
     */
    public Map<String, Object> getStats() {
        long queueDepth = jobRepository.getQueueDepth();
        long success = jobRepository.countByStatus(JobStatus.COMPLETED);
        long failure = jobRepository.countByStatus(JobStatus.DLQ);
        long total = jobRepository.count();

        double successRate = 100.0;
        if (success + failure > 0) {
            successRate = ((double) success / (success + failure)) * 100.0;
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("queueDepth", queueDepth);
        stats.put("successCount", success);
        stats.put("failureCount", failure);
        stats.put("totalCount", total);
        stats.put("successRate", Math.round(successRate * 10.0) / 10.0); // round to 1 decimal place

        return stats;
    }

    /**
     * Broadcasts job state update over WebSockets.
     */
    public void broadcastUpdate(Job job) {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "UPDATE");
        message.put("job", job);
        // Also attach updated metrics
        message.put("stats", getStats());
        messagingTemplate.convertAndSend("/topic/jobs", (Object) message);
    }

    /**
     * Locks and transitions PENDING/RETRYING jobs to RUNNING in a single transaction.
     * This ensures multiple instances or worker threads don't pick up the same job.
     */
    @Transactional
    public List<Job> claimRunnableJobs(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<Job> runnableJobs = jobRepository.findRunnableJobs(now, PageRequest.of(0, limit));

        for (Job job : runnableJobs) {
            // Pre-claim by updating status and started time
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
        }

        // Save status changes. This commits inside the transaction.
        return jobRepository.saveAll(runnableJobs);
    }
}

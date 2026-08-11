package com.jobcraft.orchestrator.service;

import com.jobcraft.orchestrator.model.Job;
import com.jobcraft.orchestrator.model.JobPriority;
import com.jobcraft.orchestrator.model.JobStatus;
import com.jobcraft.orchestrator.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class JobServiceIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    public void setup() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("Pessimistic Locking Test: Concurrent workers never claim the same job")
    public void testConcurrentClaimingNoDuplicateJobs() throws InterruptedException, ExecutionException {
        int totalJobs = 20;
        int threadCount = 8;
        int batchSizePerThread = 5;

        // 1. Seed 20 runnable pending jobs
        for (int i = 0; i < totalJobs; i++) {
            Job job = new Job("SEND_EMAIL", "{\"index\":" + i + "}", JobPriority.MEDIUM, 3);
            job.setScheduledAt(LocalDateTime.now().minusSeconds(10));
            jobRepository.save(job);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<List<Job>>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> jobService.claimRunnableJobs(batchSizePerThread));
        }

        // 2. Invoke all threads simultaneously
        List<Future<List<Job>>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // 3. Aggregate all claimed jobs and check for duplicates
        ConcurrentHashMap<UUID, AtomicInteger> claimCounts = new ConcurrentHashMap<>();
        int totalClaimed = 0;

        for (Future<List<Job>> future : futures) {
            List<Job> claimedBatch = future.get();
            for (Job job : claimedBatch) {
                totalClaimed++;
                claimCounts.computeIfAbsent(job.getId(), k -> new AtomicInteger(0)).incrementAndGet();
            }
        }

        // 4. Assert correctness: No single job ID must ever be claimed more than once
        assertEquals(totalJobs, totalClaimed, "All runnable jobs should be claimed exactly once across all worker threads");
        for (Map.Entry<UUID, AtomicInteger> entry : claimCounts.entrySet()) {
            assertEquals(1, entry.getValue().get(),
                    "Job ID " + entry.getKey() + " was claimed " + entry.getValue().get() + " times! Pessimistic locking violation.");
        }
    }

    @Test
    @DisplayName("Exponential Backoff Test: Failures increment retry counter and schedule backoff")
    public void testExponentialBackoffCalculation() {
        // Create a job designed to force failure
        Job job = jobService.submitJob("SEND_EMAIL", "{\"forceFailure\": true}", JobPriority.HIGH, 3);
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(0, job.getRetryCount());

        // Execute 1st attempt
        jobService.executeJob(job);

        Job updatedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, updatedJob.getStatus());
        assertEquals(1, updatedJob.getRetryCount());
        assertNotNull(updatedJob.getErrorMessage());
        // Scheduled time should be in the future (approx now + 2 seconds)
        assertTrue(updatedJob.getScheduledAt().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    @DisplayName("Dead Letter Queue Test: Exceeding max retries transitions job to DLQ")
    public void testDlqTransitionAfterMaxRetries() {
        // Job with maxRetries = 1
        Job job = jobService.submitJob("GENERATE_REPORT", "{\"forceFailure\": true}", JobPriority.LOW, 1);
        job.setRetryCount(1); // Simulate already tried once
        job = jobRepository.save(job);

        // Execute failure
        jobService.executeJob(job);

        Job finalJob = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.DLQ, finalJob.getStatus(), "Job should transition to DLQ when retryCount equals maxRetries");
        assertNotNull(finalJob.getCompletedAt(), "completedAt timestamp should be set upon entering DLQ");
        assertNotNull(finalJob.getErrorMessage());
    }
}

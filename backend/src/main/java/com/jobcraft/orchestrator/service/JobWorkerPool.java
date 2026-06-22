package com.jobcraft.orchestrator.service;

import com.jobcraft.orchestrator.model.Job;
import com.jobcraft.orchestrator.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class JobWorkerPool {

    private static final Logger logger = LoggerFactory.getLogger(JobWorkerPool.class);

    private final JobRepository jobRepository;
    private final JobService jobService;
    private ThreadPoolExecutor threadPoolExecutor;
    
    private final int maxPoolSize = 5;

    @Autowired
    public JobWorkerPool(JobRepository jobRepository, JobService jobService) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
    }

    @PostConstruct
    public void init() {
        // Create a fixed thread pool of size 5
        this.threadPoolExecutor = new ThreadPoolExecutor(
                maxPoolSize,
                maxPoolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        logger.info("Initialized Job Worker Pool with max size: {}", maxPoolSize);
    }

    @PreDestroy
    public void shutdown() {
        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdown();
            try {
                if (!threadPoolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPoolExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPoolExecutor.shutdownNow();
            }
            logger.info("Job Worker Pool shut down successfully.");
        }
    }

    /**
     * Polls the database for pending jobs.
     * We run this inside a transaction with pessimistic locking, claim the jobs by setting status to RUNNING,
     * commit, and then execute them asynchronously.
     */
    @Scheduled(fixedDelay = 1000)
    public void pollAndExecute() {
        int activeTasks = threadPoolExecutor.getActiveCount();
        int availableCapacity = maxPoolSize - activeTasks;

        if (availableCapacity <= 0) {
            // Worker pool is fully occupied
            return;
        }

        try {
            // Claim jobs that are ready to run
            List<Job> jobsToRun = jobService.claimRunnableJobs(availableCapacity);

            if (!jobsToRun.isEmpty()) {
                logger.info("Polled {} jobs from database for execution.", jobsToRun.size());
                for (Job job : jobsToRun) {
                    threadPoolExecutor.submit(() -> {
                        try {
                            jobService.executeJob(job);
                        } catch (Exception e) {
                            logger.error("Unexpected worker exception during job execution: ", e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            logger.error("Error in job polling scheduler loop: ", e);
        }
    }
}

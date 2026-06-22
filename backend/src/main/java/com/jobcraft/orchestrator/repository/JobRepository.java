package com.jobcraft.orchestrator.repository;

import com.jobcraft.orchestrator.model.Job;
import com.jobcraft.orchestrator.model.JobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    // Lock and retrieve the highest priority runnable jobs that are due
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j WHERE (j.status = com.jobcraft.orchestrator.model.JobStatus.PENDING OR j.status = com.jobcraft.orchestrator.model.JobStatus.RETRYING) AND j.scheduledAt <= :now ORDER BY j.priority DESC, j.createdAt ASC")
    List<Job> findRunnableJobs(@Param("now") LocalDateTime now, Pageable pageable);

    long countByStatus(JobStatus status);

    @Query("SELECT COUNT(j) FROM Job j WHERE j.status = com.jobcraft.orchestrator.model.JobStatus.PENDING OR j.status = com.jobcraft.orchestrator.model.JobStatus.RUNNING OR j.status = com.jobcraft.orchestrator.model.JobStatus.RETRYING")
    long getQueueDepth();
}

package com.jobcraft.orchestrator.model;

public enum JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    RETRYING,
    DLQ
}

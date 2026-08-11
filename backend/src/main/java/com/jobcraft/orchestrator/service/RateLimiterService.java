package com.jobcraft.orchestrator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class RateLimiterService {

    @Value("${app.rate-limit.ai-route.requests-per-minute:10}")
    private int requestsPerMinute;

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> clientRequestTimestamps = new ConcurrentHashMap<>();

    /**
     * Checks whether a request from the given key (e.g. client IP) is allowed under the sliding window rate limit.
     *
     * @param clientKey identifier for the rate-limited client
     * @return true if request is within allowed limits, false if rate-limited.
     */
    public boolean tryAcquire(String clientKey) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L; // 1 minute sliding window

        ConcurrentLinkedQueue<Long> timestamps = clientRequestTimestamps.computeIfAbsent(
                clientKey,
                k -> new ConcurrentLinkedQueue<>()
        );

        // Evict expired timestamps outside the sliding window
        while (!timestamps.isEmpty() && timestamps.peek() < windowStart) {
            timestamps.poll();
        }

        synchronized (timestamps) {
            if (timestamps.size() < requestsPerMinute) {
                timestamps.add(now);
                return true;
            }
            return false;
        }
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }
}

package com.company.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed Lock Coordinator for multi-replica enterprise deployments.
 * Backed by Redis distributed SETNX locks when available, with atomic in-memory fallback.
 */
@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);
    private static final String LOCK_PREFIX = "lock:";

    private final StringRedisTemplate redisTemplate;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, LockInfo> inMemoryLocks = new ConcurrentHashMap<>();

    private record LockInfo(String ownerId, Instant expiresAt) {}

    public DistributedLockService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempt to acquire a distributed lock.
     *
     * @param lockName     Name of the lock resource (e.g. "itsm-scheduled-sync")
     * @param lockDuration Maximum duration before the lock automatically expires
     * @return true if the lock was successfully acquired, false otherwise
     */
    public boolean tryLock(String lockName, Duration lockDuration) {
        String key = LOCK_PREFIX + lockName;

        if (redisTemplate != null) {
            try {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, instanceId, lockDuration);
                return Boolean.TRUE.equals(acquired);
            } catch (Exception e) {
                log.debug("Redis distributed lock failed, checking in-memory fallback: {}", e.getMessage());
            }
        }

        // In-memory atomic fallback
        Instant now = Instant.now();
        Instant expiry = now.plus(lockDuration);

        return inMemoryLocks.compute(lockName, (k, current) -> {
            if (current == null || current.expiresAt.isBefore(now)) {
                return new LockInfo(instanceId, expiry);
            }
            return current; // lock already held
        }).ownerId().equals(instanceId);
    }

    /**
     * Release a previously acquired distributed lock.
     */
    public boolean releaseLock(String lockName) {
        String key = LOCK_PREFIX + lockName;

        if (redisTemplate != null) {
            try {
                String currentOwner = redisTemplate.opsForValue().get(key);
                if (instanceId.equals(currentOwner)) {
                    redisTemplate.delete(key);
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.debug("Redis lock release failed: {}", e.getMessage());
            }
        }

        LockInfo current = inMemoryLocks.get(lockName);
        if (current != null && current.ownerId().equals(instanceId)) {
            inMemoryLocks.remove(lockName);
            return true;
        }
        return false;
    }

    /**
     * Execute a task only if the distributed lock can be acquired.
     * Automatically releases the lock when execution finishes.
     */
    public boolean executeWithLock(String lockName, Duration lockDuration, Runnable task) {
        if (!tryLock(lockName, lockDuration)) {
            log.debug("[LOCK] Lock '{}' already held by another cluster node. Skipping execution.", lockName);
            return false;
        }

        try {
            task.run();
            return true;
        } finally {
            releaseLock(lockName);
        }
    }
}

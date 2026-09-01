package com.company.mcp.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockServiceTest {

    private final DistributedLockService lockService = new DistributedLockService(null);

    @Test
    void testLockAcquisitionAndRelease() {
        String lockName = "test-resource-lock";

        // First attempt acquires lock
        assertThat(lockService.tryLock(lockName, Duration.ofMinutes(1))).isTrue();

        // Second attempt on same node with same lock re-acquires/holds
        assertThat(lockService.tryLock(lockName, Duration.ofMinutes(1))).isTrue();

        // Release
        assertThat(lockService.releaseLock(lockName)).isTrue();
    }

    @Test
    void testExecuteWithLockRunsTask() {
        String lockName = "task-lock";
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = lockService.executeWithLock(lockName, Duration.ofMinutes(1), () -> {
            ran.set(true);
        });

        assertThat(executed).isTrue();
        assertThat(ran.get()).isTrue();

        // Lock is released after task completion, allowing subsequent task
        AtomicBoolean ranSecond = new AtomicBoolean(false);
        boolean executedSecond = lockService.executeWithLock(lockName, Duration.ofMinutes(1), () -> {
            ranSecond.set(true);
        });

        assertThat(executedSecond).isTrue();
        assertThat(ranSecond.get()).isTrue();
    }
}

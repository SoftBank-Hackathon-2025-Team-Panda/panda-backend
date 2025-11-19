package com.panda.backend.feature.deploy.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class DeploymentTaskExecutor {

    private static final int CORE_POOL_SIZE = 5;
    private static final int MAX_POOL_SIZE = 10;
    private static final long KEEP_ALIVE_TIME = 60; // 초
    private static final long DEPLOYMENT_TIMEOUT = 30; // 분

    private final ExecutorService executorService;
    private final Map<String, CompletableFuture<Void>> deploymentFutures = new ConcurrentHashMap<>();
    private final Map<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    public DeploymentTaskExecutor() {
        this.executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r);
                        t.setName("deployment-worker-" + count.incrementAndGet());
                        t.setDaemon(false);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        log.info("DeploymentTaskExecutor initialized with pool size: {}-{}", CORE_POOL_SIZE, MAX_POOL_SIZE);
    }

    // 배포 작업을 비동기로 실행
    public CompletableFuture<Void> executeDeployment(String deploymentId, Runnable task) {
        try {
            // 이미 실행 중인 배포가 있으면 취소
            cancelDeployment(deploymentId);

            // 배포 시작 시간 기록
            deploymentStartTimes.put(deploymentId, System.currentTimeMillis());

            // CompletableFuture 생성 및 작업 제출
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> {
                        try {
                            log.info("Deployment {} started in thread: {}", deploymentId, Thread.currentThread().getName());
                            task.run();
                            log.info("Deployment {} completed successfully", deploymentId);
                        } catch (Exception e) {
                            log.error("Deployment {} failed with exception", deploymentId, e);
                            throw new CompletionException(e);
                        }
                    },
                    executorService
            );

            // 타임아웃 처리 (30분)
            CompletableFuture<Void> futureWithTimeout = future.orTimeout(
                    DEPLOYMENT_TIMEOUT,
                    TimeUnit.MINUTES
            );

            // 완료 또는 예외 발생 시 정리 작업 수행
            futureWithTimeout.whenComplete((result, exception) -> {
                if (exception != null) {
                    if (exception instanceof TimeoutException) {
                        log.error("Deployment {} timed out after {} minutes", deploymentId, DEPLOYMENT_TIMEOUT);
                    } else {
                        log.error("Deployment {} failed with exception", deploymentId, exception);
                    }
                }
                cleanupDeployment(deploymentId);
            });

            // Future 저장
            deploymentFutures.put(deploymentId, futureWithTimeout);

            log.info("Deployment {} submitted to executor service", deploymentId);
            return futureWithTimeout;

        } catch (RejectedExecutionException e) {
            log.error("Failed to submit deployment {} due to executor rejection", deploymentId, e);
            cleanupDeployment(deploymentId);
            throw new RuntimeException("Deployment queue is full. Please try again later.", e);
        }
    }

    // 배포 작업 취소 (중복 배포 방지용)
    public void cancelDeployment(String deploymentId) {
        CompletableFuture<Void> future = deploymentFutures.get(deploymentId);
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                log.info("Deployment {} cancelled", deploymentId);
            } else {
                log.warn("Failed to cancel deployment {} (already completed)", deploymentId);
            }
        }
    }

    // 배포 완료 후 정리
    private void cleanupDeployment(String deploymentId) {
        deploymentFutures.remove(deploymentId);
        deploymentStartTimes.remove(deploymentId);
        log.info("Deployment {} cleanup completed", deploymentId);
    }

    // 모든 배포 작업 기다리기 (종료 시)
    public void awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        executorService.shutdown();
        if (!executorService.awaitTermination(timeout, unit)) {
            executorService.shutdownNow();
            log.warn("Executor service did not terminate in time, forcing shutdown");
        }
    }

}

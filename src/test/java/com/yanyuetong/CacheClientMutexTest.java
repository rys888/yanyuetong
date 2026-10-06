package com.yanyuetong;

import com.yanyuetong.utils.CacheClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@SpringBootTest
class CacheClientMutexTest {

    private static final String KEY_PREFIX = "test:mutex:";

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final List<String> keysToDelete = new ArrayList<>();

    @AfterEach
    void cleanUpRedis() {
        if (!keysToDelete.isEmpty()) {
            stringRedisTemplate.delete(keysToDelete);
        }
    }

    @Test
    void shouldOnlyCallFallbackOnceUnderConcurrentCacheMiss() throws Exception {
        String id = "concurrent-" + System.nanoTime();
        String key = KEY_PREFIX + id;
        String lockKey = "lock:" + key;
        keysToDelete.add(key);
        keysToDelete.add(lockKey);

        ValueOperations<String, String> valueOperations = spy(stringRedisTemplate.opsForValue());
        StringRedisTemplate controlledTemplate = spy(stringRedisTemplate);
        doReturn(valueOperations).when(controlledTemplate).opsForValue();
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("delayed-mutex-worker")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!(Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))
                        && !Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey)))) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("timed out waiting for the first cache rebuild");
                    }
                    Thread.yield();
                }
            }
            return invocation.callRealMethod();
        }).when(valueOperations).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        CacheClient controlledCacheClient = new CacheClient(controlledTemplate);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("mutex-worker");
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger workerNumber = new AtomicInteger();
        AtomicInteger dbCalls = new AtomicInteger();
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    if (workerNumber.getAndIncrement() == 0) {
                        Thread.currentThread().setName("delayed-mutex-worker");
                    }
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return controlledCacheClient.queryWithMutex(
                            KEY_PREFIX,
                            id,
                            String.class,
                            ignored -> {
                                dbCalls.incrementAndGet();
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(e);
                                }
                                return "{}";
                            },
                            5L,
                            TimeUnit.MINUTES);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("{}", future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, dbCalls.get());
    }

    @Test
    void shouldNotDeleteLockOwnedByAnotherHolder() {
        String id = "ownership-" + System.nanoTime();
        String key = KEY_PREFIX + id;
        String lockKey = "lock:" + key;
        keysToDelete.add(key);
        keysToDelete.add(lockKey);

        String result = cacheClient.queryWithMutex(
                KEY_PREFIX,
                id,
                String.class,
                ignored -> {
                    // 模拟：本线程回源期间锁 TTL 到期，锁已被其他线程取得
                    stringRedisTemplate.opsForValue().set(lockKey, "another-holder", 30, TimeUnit.SECONDS);
                    return "{}";
                },
                5L,
                TimeUnit.MINUTES);

        assertEquals("{}", result);
        // 关键断言：不得删除他人持有的锁
        assertEquals("another-holder", stringRedisTemplate.opsForValue().get(lockKey));
    }

    @Test
    void shouldCacheNullValueAndAvoidRepeatedFallback() {
        String id = "null-" + System.nanoTime();
        String key = KEY_PREFIX + id;
        String lockKey = "lock:" + key;
        keysToDelete.add(key);
        keysToDelete.add(lockKey);
        AtomicInteger dbCalls = new AtomicInteger();

        String first = cacheClient.queryWithMutex(
                KEY_PREFIX, id, String.class, ignored -> {
                    dbCalls.incrementAndGet();
                    return null;
                }, 5L, TimeUnit.MINUTES);

        assertNull(first);
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(key)));
        assertNotNull(stringRedisTemplate.opsForValue().get(key));
        assertEquals("", stringRedisTemplate.opsForValue().get(key));

        String second = cacheClient.queryWithMutex(
                KEY_PREFIX, id, String.class, ignored -> {
                    dbCalls.incrementAndGet();
                    return null;
                }, 5L, TimeUnit.MINUTES);

        assertNull(second);
        assertEquals(1, dbCalls.get());
    }
}

package com.yanyuetong.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.yanyuetong.utils.RedisConstants.CACHE_NULL_TTL;
import static com.yanyuetong.utils.RedisConstants.LOCK_CACHE_KEY;

/**
 * 缓存查询工具。设备详情正式采用 {@link #queryWithMutex} 的互斥锁重建（方案 A）；
 * {@link #queryWithLogicalExpire} 为未启用的备选实现。
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private static final int MUTEX_MAX_RETRY = 20;
    private static final long MUTEX_RETRY_INTERVAL_MS = 50L;
    private static final long MUTEX_LOCK_TTL_SEC = 10L;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 将过期时刻放进 JSON，而非设置 Redis TTL；逻辑过期方案需要保留旧值供异步重建期间读取。
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1、先读 Redis；非空 JSON 直接反序列化返回，省去数据库查询。
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 2、空字符串表示此前已确认数据不存在；它与 Key 不存在时的 null 含义不同。
        if (json != null) {
            return null;
        }

        // 3、只有真正未命中才调用 dbFallback；该回调由业务方提供，本工具不绑定具体表。
        R r = dbFallback.apply(id);
        if (r == null) {
            // 数据库也没有则缓存短期空值，避免不存在的 ID 反复穿透到数据库。
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 4、查询到实体则写入带 TTL 的 JSON；此方案没有重建互斥，热点失效时可能并发回源。
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、读取预热的逻辑过期数据；未命中直接返回 null，此备选实现不负责首次回源。
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 2、拆出实体与逻辑过期时刻；尚未过期就立即返回。
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 3、已过期时尝试获取重建锁：抢到者提交异步回源，其他请求继续读旧值，减少同步等待。
        String lockKey = LOCK_CACHE_KEY + keyPrefix + id;
        String lockValue = UUID.randomUUID().toString(true);
        boolean isLock = tryLock(lockKey, lockValue);
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 4、工作线程用数据库结果替换缓存，并在 finally 释放自己的锁；失败则保留旧值并记录日志。
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    log.error("缓存逻辑过期重建失败, key={}", key, e);
                }finally {
                    unlock(lockKey, lockValue);
                }
            });
        }
        // 5、当前请求不等待重建，返回旧值；这是以短期陈旧数据换取响应速度，设备正式查询未使用此方案。
        return r;
    }

    /**
     * 用互斥锁限制热点缓存失效后的并发回源。
     *
     * @param keyPrefix 业务缓存前缀，与 id 组合定位数据
     * @param id 查询标识，同时传给数据库回调
     * @param type 缓存 JSON 要还原的类型
     * @param dbFallback 缓存未命中时执行的数据库查询
     * @param time 实体缓存有效时长，空值另用短 TTL
     * @param unit 有效时长的单位
     * @return 缓存或数据库中的实体，不存在则返回 null
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1、设备详情通过本方法查询。缓存 Key 与重建锁 Key 分开，锁不会覆盖实际 JSON 数据。
        String key = keyPrefix + id;
        String lockKey = LOCK_CACHE_KEY + keyPrefix + id;

        for (int attempt = 0; attempt < MUTEX_MAX_RETRY; attempt++) {
            // 2、每轮先读缓存：命中 JSON 返回实体，命中空字符串返回 null，只有 Key 缺失才争抢锁。
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }

            // 3、用本次唯一锁值执行 SET NX + TTL；未获锁则短暂等待并重读，等待次数有上限。
            String lockValue = UUID.randomUUID().toString(true);
            if (!tryLock(lockKey, lockValue)) {
                // 其他线程正在回源，短暂等待后重读缓存，避免竞争者同时压到数据库。
                sleepQuietly(MUTEX_RETRY_INTERVAL_MS);
                continue;
            }

            try {
                // 4、获锁后再读一次：等待抢锁期间前一个持有者可能已重建，双重检查避免重复查库。
                String cached = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(cached)) {
                    return JSONUtil.toBean(cached, type);
                }
                if (cached != null) {
                    return null;
                }
                // 5、仍未命中才回源；查不到写短 TTL 空值防穿透，查到则写入业务指定 TTL 的 JSON。
                R r = dbFallback.apply(id);
                if (r == null) {
                    // 短时缓存空值，阻断对不存在 ID 的高频穿透查询。
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                this.set(key, r, time, unit);
                return r;
            } finally {
                // 6、Lua 比对锁值后删除，避免旧持有者误删租期过后新持有者的锁。
                unlock(lockKey, lockValue);
            }
        }

        // 7、等待达到上限后直接查库，避免无限阻塞；此降级不写缓存，高并发时仍可能增加数据库压力。
        return dbFallback.apply(id);
    }

    private boolean tryLock(String key, String value) {
        // 原子设置锁与 10 秒 TTL，防止进程退出后永久占锁；固定 TTL 没有自动续期，慢回源可能超时。
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, MUTEX_LOCK_TTL_SEC, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key, String value) {
        // 比较锁值与删除必须由 Lua 原子完成，防止旧持有者误删 TTL 过期后的新锁。
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

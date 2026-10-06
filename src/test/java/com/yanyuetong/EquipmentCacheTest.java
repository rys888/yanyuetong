package com.yanyuetong;

import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.LaboratoryStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.mapper.EquipmentMapper;
import com.yanyuetong.mapper.LaboratoryMapper;
import com.yanyuetong.mapper.UserMapper;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.utils.UserHolder;
import com.yanyuetong.vo.EquipmentVO;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.annotation.Resource;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yanyuetong.utils.RedisConstants.CACHE_EQUIPMENT_KEY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(EquipmentCacheTest.MyBatisQueryCounterConfig.class)
class EquipmentCacheTest {

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private EquipmentMapper equipmentMapper;

    @Resource
    private LaboratoryMapper laboratoryMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private EquipmentSelectCounter equipmentSelectCounter;

    private User admin;
    private Laboratory laboratory;
    private Long equipmentId;
    private Long missingEquipmentId;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        String phone = "19"
                + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L))
                + "1";
        admin = new User()
                .setPhone(phone)
                .setNickName("缓存测试管理员")
                .setIcon("")
                .setRole(UserRole.LAB_ADMIN);
        assertEquals(1, userMapper.insert(admin));

        laboratory = new Laboratory()
                .setName("缓存测试实验室-" + System.nanoTime())
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("C101")
                .setDescription("设备缓存自动化测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(admin.getId());
        assertEquals(1, laboratoryMapper.insert(laboratory));

        UserDTO userDTO = new UserDTO();
        userDTO.setId(admin.getId());
        userDTO.setRole(UserRole.LAB_ADMIN);
        UserHolder.saveUser(userDTO);

        Equipment equipment = new Equipment()
                .setLaboratoryId(laboratory.getId())
                .setCategoryId(1L)
                .setName("缓存测试设备-" + System.nanoTime())
                .setDescription("设备详情缓存测试")
                .setLocation("A101-TEST")
                .setImage("/test/equipment.png")
                .setUsageRequirement("仅供自动化测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        equipmentId = equipmentService.saveEquipment(equipment);
        assertNotNull(equipmentId);
        assertTrue(equipmentId > 12L);

        missingEquipmentId = 9_000_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
        stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + equipmentId);
        stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + missingEquipmentId);
        equipmentSelectCounter.reset();
    }

    @AfterEach
    void cleanUp() {
        if (equipmentId != null) {
            stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + equipmentId);
            stringRedisTemplate.delete("lock:" + CACHE_EQUIPMENT_KEY + equipmentId);
            equipmentMapper.deleteById(equipmentId);
        }
        if (missingEquipmentId != null) {
            stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + missingEquipmentId);
            stringRedisTemplate.delete("lock:" + CACHE_EQUIPMENT_KEY + missingEquipmentId);
        }
        UserHolder.removeUser();
        if (laboratory != null && laboratory.getId() != null) {
            laboratoryMapper.deleteById(laboratory.getId());
        }
        if (admin != null && admin.getId() != null) {
            userMapper.deleteById(admin.getId());
        }
    }

    @Test
    void shouldUseCacheAfterFirstDatabaseQueryAndCacheMissingEquipment() {
        EquipmentVO first = equipmentService.queryEquipmentById(equipmentId);
        assertNotNull(first);
        assertEquals(1, equipmentSelectCounter.getCount());

        EquipmentVO second = equipmentService.queryEquipmentById(equipmentId);
        assertEquals(first, second);
        assertEquals(1, equipmentSelectCounter.getCount());

        EquipmentVO missing = equipmentService.queryEquipmentById(missingEquipmentId);
        assertNull(missing);
        assertEquals(2, equipmentSelectCounter.getCount());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_EQUIPMENT_KEY + missingEquipmentId)));
        assertEquals("", stringRedisTemplate.opsForValue().get(CACHE_EQUIPMENT_KEY + missingEquipmentId));
    }

    @Test
    void shouldPreserveEveryEquipmentFieldThroughHutoolJsonRoundTrip() {
        Object equipmentServiceTarget = AopTestUtils.getTargetObject(equipmentService);
        EquipmentVO fromDb = ReflectionTestUtils.invokeMethod(
                equipmentServiceTarget, "queryEquipmentByIdFromDb", equipmentId);
        assertNotNull(fromDb);

        equipmentService.queryEquipmentById(equipmentId);
        EquipmentVO fromCache = equipmentService.queryEquipmentById(equipmentId);

        assertNotNull(fromCache);
        assertAll(
                () -> assertEquals(fromDb.getId(), fromCache.getId()),
                () -> assertEquals(fromDb.getLaboratoryId(), fromCache.getLaboratoryId()),
                () -> assertEquals(fromDb.getCategoryId(), fromCache.getCategoryId()),
                () -> assertEquals(fromDb.getName(), fromCache.getName()),
                () -> assertEquals(fromDb.getDescription(), fromCache.getDescription()),
                () -> assertEquals(fromDb.getLocation(), fromCache.getLocation()),
                () -> assertEquals(fromDb.getImage(), fromCache.getImage()),
                () -> assertEquals(fromDb.getUsageRequirement(), fromCache.getUsageRequirement()),
                () -> assertNotNull(fromCache.getStatus()),
                () -> assertEquals(fromDb.getStatus(), fromCache.getStatus()),
                () -> assertNotNull(fromCache.getBookingEnabled()),
                () -> assertEquals(fromDb.getBookingEnabled(), fromCache.getBookingEnabled()),
                () -> assertNotNull(fromCache.getCreateTime()),
                () -> assertEquals(fromDb.getCreateTime(), fromCache.getCreateTime()),
                () -> assertNotNull(fromCache.getUpdateTime()),
                () -> assertEquals(fromDb.getUpdateTime(), fromCache.getUpdateTime()),
                () -> assertEquals(fromDb.getLaboratoryName(), fromCache.getLaboratoryName()),
                () -> assertEquals(fromDb.getCampus(), fromCache.getCampus()),
                () -> assertEquals(fromDb.getBuilding(), fromCache.getBuilding()),
                () -> assertEquals(fromDb.getRoom(), fromCache.getRoom()),
                () -> assertEquals(fromDb.getCategoryName(), fromCache.getCategoryName()));
    }

    @Test
    void shouldEvictCacheWhenEquipmentStatusChanges() {
        EquipmentVO cached = equipmentService.queryEquipmentById(equipmentId);
        assertEquals(EquipmentStatus.AVAILABLE, cached.getStatus());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_EQUIPMENT_KEY + equipmentId)));

        equipmentService.updateEquipmentStatus(equipmentId, EquipmentStatus.MAINTENANCE);

        assertTrue(Boolean.FALSE.equals(stringRedisTemplate.hasKey(CACHE_EQUIPMENT_KEY + equipmentId)));
        EquipmentVO updated = equipmentService.queryEquipmentById(equipmentId);
        assertEquals(EquipmentStatus.MAINTENANCE, updated.getStatus());
    }

    @Test
    void shouldEvictCacheForUpdateAndDeleteOperations() {
        equipmentService.queryEquipmentById(equipmentId);
        equipmentService.updateEquipment(new Equipment().setId(equipmentId).setName("更新后的测试设备"));
        assertTrue(Boolean.FALSE.equals(stringRedisTemplate.hasKey(CACHE_EQUIPMENT_KEY + equipmentId)));
        assertEquals("更新后的测试设备", equipmentService.queryEquipmentById(equipmentId).getName());

        equipmentService.deleteEquipment(equipmentId);
        assertTrue(Boolean.FALSE.equals(stringRedisTemplate.hasKey(CACHE_EQUIPMENT_KEY + equipmentId)));
        assertNull(equipmentService.queryEquipmentById(equipmentId));
    }

    @TestConfiguration
    static class MyBatisQueryCounterConfig {

        @Bean
        EquipmentSelectCounter equipmentSelectCounter() {
            return new EquipmentSelectCounter();
        }
    }

    @Intercepts(@Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class EquipmentSelectCounter implements Interceptor {

        private static final String SELECT_BY_ID =
                "com.yanyuetong.mapper.EquipmentMapper.selectById";

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (SELECT_BY_ID.equals(statement.getId())) {
                count.incrementAndGet();
            }
            return invocation.proceed();
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
        }

        int getCount() {
            return count.get();
        }

        void reset() {
            count.set(0);
        }
    }
}

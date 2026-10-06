package com.yanyuetong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyuetong.config.WebExceptionAdvice;
import com.yanyuetong.controller.LaboratoryController;
import com.yanyuetong.dto.Result;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.LaboratoryStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.exception.ForbiddenException;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.service.IUserService;
import com.yanyuetong.utils.UserHolder;
import com.yanyuetong.vo.LaboratoryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class LaboratoryOwnershipTest {

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private IUserService userService;

    @Resource
    private LaboratoryController laboratoryController;

    @Resource
    private ObjectMapper objectMapper;

    private User adminA;
    private User adminB;
    private Laboratory laboratoryA;
    private Laboratory laboratoryB;
    private Equipment equipmentA;
    private Equipment equipmentB;
    private Equipment rejectedEquipment;
    private Long additionallyCreatedEquipmentId;
    private Long additionallyCreatedLaboratoryId;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        adminA = createAdmin("18" + suffix + "1", "归属测试管理员A");
        adminB = createAdmin("18" + suffix + "2", "归属测试管理员B");

        laboratoryA = createLaboratory("归属测试实验室A-" + suffix, adminA.getId());
        laboratoryB = createLaboratory("归属测试实验室B-" + suffix, adminB.getId());
        long categoryId = 9_000_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
        equipmentA = createEquipment("归属测试设备A-" + suffix, laboratoryA.getId(), categoryId);
        equipmentB = createEquipment("归属测试设备B-" + suffix, laboratoryB.getId(), categoryId);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        if (additionallyCreatedEquipmentId != null) {
            equipmentService.removeById(additionallyCreatedEquipmentId);
        }
        if (equipmentA != null && equipmentA.getId() != null) {
            equipmentService.removeById(equipmentA.getId());
        }
        if (equipmentB != null && equipmentB.getId() != null) {
            equipmentService.removeById(equipmentB.getId());
        }
        if (rejectedEquipment != null && rejectedEquipment.getId() != null) {
            equipmentService.removeById(rejectedEquipment.getId());
        }
        if (additionallyCreatedLaboratoryId != null) {
            laboratoryService.removeById(additionallyCreatedLaboratoryId);
        }
        if (laboratoryA != null && laboratoryA.getId() != null) {
            laboratoryService.removeById(laboratoryA.getId());
        }
        if (laboratoryB != null && laboratoryB.getId() != null) {
            laboratoryService.removeById(laboratoryB.getId());
        }
        if (adminA != null && adminA.getId() != null) {
            userService.removeById(adminA.getId());
        }
        if (adminB != null && adminB.getId() != null) {
            userService.removeById(adminB.getId());
        }
    }

    @Test
    void shouldRejectUpdatingOtherAdministratorsEquipmentStatus() {
        loginAs(adminA);

        assertThrows(ForbiddenException.class,
                () -> equipmentService.updateEquipmentStatus(equipmentB.getId(), EquipmentStatus.DISABLED));
    }

    @Test
    void shouldRejectUpdatingOtherAdministratorsLaboratory() {
        loginAs(adminA);

        assertThrows(ForbiddenException.class,
                () -> laboratoryService.updateLaboratory(
                        new Laboratory().setId(laboratoryB.getId()).setName("越权修改")));
    }

    @Test
    void shouldRejectDeletingOtherAdministratorsLaboratory() {
        loginAs(adminA);

        assertThrows(ForbiddenException.class,
                () -> laboratoryService.deleteLaboratory(laboratoryB.getId()));
    }

    @Test
    void shouldRejectDeletingOwnedLaboratoryWithEquipmentDependency() {
        loginAs(adminA);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> laboratoryService.deleteLaboratory(laboratoryA.getId()));

        assertEquals("该实验室下仍有 1 台设备，请先转移或删除设备", exception.getMessage());
        assertNotNull(laboratoryService.getById(laboratoryA.getId()));
    }

    @Test
    void shouldDeleteOwnedLaboratoryWithoutEquipmentDependency() {
        Laboratory laboratory = createLaboratory(
                "无设备依赖删除测试-" + System.nanoTime(), adminA.getId());
        additionallyCreatedLaboratoryId = laboratory.getId();
        loginAs(adminA);

        assertDoesNotThrow(() -> laboratoryService.deleteLaboratory(laboratory.getId()));

        assertNull(laboratoryService.getById(laboratory.getId()));
    }

    @Test
    void shouldNotExposeAdminUserIdFromLaboratoryReadEndpoints() throws Exception {
        String campus = "VO测试校区-" + System.nanoTime();
        laboratoryA.setCampus(campus);
        assertTrue(laboratoryService.updateById(laboratoryA));
        Laboratory storedLaboratory = laboratoryService.getById(laboratoryA.getId());

        Result detailResult = laboratoryController.queryLaboratoryById(laboratoryA.getId());
        Result listResult = laboratoryController.queryLaboratoryList(campus, 1);
        List<?> records = (List<?>) listResult.getData();

        assertTrue(detailResult.getData() instanceof LaboratoryVO);
        assertEquals(1, records.size());
        assertTrue(records.get(0) instanceof LaboratoryVO);
        LaboratoryVO detail = (LaboratoryVO) detailResult.getData();
        LaboratoryVO listItem = (LaboratoryVO) records.get(0);
        assertEquals(storedLaboratory.getName(), detail.getName());
        assertEquals(storedLaboratory.getCampus(), detail.getCampus());
        assertEquals(storedLaboratory.getStatus(), detail.getStatus());
        assertNotNull(detail.getCreateTime());
        assertEquals(storedLaboratory.getName(), listItem.getName());
        assertEquals(storedLaboratory.getCampus(), listItem.getCampus());
        assertEquals(storedLaboratory.getStatus(), listItem.getStatus());
        assertNotNull(listItem.getCreateTime());
        assertFalse(objectMapper.writeValueAsString(detailResult.getData()).contains("\"adminUserId\""));
        assertFalse(objectMapper.writeValueAsString(records).contains("\"adminUserId\""));
        assertTrue(objectMapper.writeValueAsString(storedLaboratory)
                .contains("\"adminUserId\""));
    }

    @Test
    void shouldReturnBadRequestForUnreadableJsonBody() throws Exception {
        MockMvc mockMvc = exceptionAdviceMockMvc();

        mockMvc.perform(post("/exception-probe/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMsg").value("请求体格式错误"));
    }

    @Test
    void shouldReturnInternalServerErrorForUnexpectedRuntimeException() throws Exception {
        MockMvc mockMvc = exceptionAdviceMockMvc();

        mockMvc.perform(get("/exception-probe/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
    }

    @Test
    void shouldRejectSavingEquipmentToOtherAdministratorsLaboratory() {
        loginAs(adminA);
        rejectedEquipment = new Equipment()
                .setLaboratoryId(laboratoryB.getId())
                .setCategoryId(9_000_000_000L)
                .setName("越权新增设备")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);

        assertThrows(ForbiddenException.class, () -> equipmentService.saveEquipment(rejectedEquipment));
        assertNull(rejectedEquipment.getId());
    }

    @Test
    void shouldAllowSavingEquipmentToOwnedLaboratory() {
        loginAs(adminA);
        Equipment equipment = new Equipment()
                .setLaboratoryId(laboratoryA.getId())
                .setCategoryId(9_000_000_000L)
                .setName("负责人新增设备-" + System.nanoTime())
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);

        assertDoesNotThrow(() -> additionallyCreatedEquipmentId = equipmentService.saveEquipment(equipment));
        assertNotNull(additionallyCreatedEquipmentId);
        assertEquals(laboratoryA.getId(),
                equipmentService.getById(additionallyCreatedEquipmentId).getLaboratoryId());
    }

    @Test
    void shouldAllowUpdatingOwnedEquipmentStatus() {
        loginAs(adminA);

        assertDoesNotThrow(() -> equipmentService.updateEquipmentStatus(
                equipmentA.getId(), EquipmentStatus.MAINTENANCE));
        assertEquals(EquipmentStatus.MAINTENANCE,
                equipmentService.getById(equipmentA.getId()).getStatus());
    }

    @Test
    void shouldAllowUpdatingOwnedEquipment() {
        loginAs(adminA);
        String updatedName = "负责人修改设备-" + System.nanoTime();

        assertDoesNotThrow(() -> equipmentService.updateEquipment(
                new Equipment().setId(equipmentA.getId()).setName(updatedName)));
        assertEquals(updatedName, equipmentService.getById(equipmentA.getId()).getName());
    }

    @Test
    void shouldRejectUpdatingOtherAdministratorsEquipment() {
        loginAs(adminA);
        String originalName = equipmentB.getName();

        assertThrows(ForbiddenException.class, () -> equipmentService.updateEquipment(
                new Equipment().setId(equipmentB.getId()).setName("越权改设备")));
        assertEquals(originalName, equipmentService.getById(equipmentB.getId()).getName());
    }

    @Test
    void shouldRejectMovingEquipmentIntoOtherAdministratorsLaboratory() {
        loginAs(adminA);
        Equipment update = new Equipment()
                .setId(equipmentA.getId())
                .setLaboratoryId(laboratoryB.getId());

        assertThrows(ForbiddenException.class, () -> equipmentService.updateEquipment(update));
        assertEquals(laboratoryA.getId(), equipmentService.getById(equipmentA.getId()).getLaboratoryId());
    }

    @Test
    void shouldAllowDeletingOwnedEquipment() {
        loginAs(adminA);

        assertDoesNotThrow(() -> equipmentService.deleteEquipment(equipmentA.getId()));
        assertNull(equipmentService.getById(equipmentA.getId()));
    }

    @Test
    void shouldRejectManageabilityCheckWithoutLoggedInUser() {
        UserHolder.removeUser();

        assertThrows(ForbiddenException.class,
                () -> laboratoryService.assertManageable(laboratoryA.getId()));
    }

    @Test
    void shouldForceCreatorAsOwnerWhenSavingLaboratory() {
        loginAs(adminA);
        Laboratory request = new Laboratory()
                .setName("归属创建测试-" + System.nanoTime())
                .setCampus("测试校区")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminB.getId());

        additionallyCreatedLaboratoryId = laboratoryService.saveLaboratory(request);
        Laboratory saved = laboratoryService.getById(additionallyCreatedLaboratoryId);

        assertEquals(adminA.getId(), saved.getAdminUserId());
        assertNotEquals(adminB.getId(), saved.getAdminUserId());
    }

    @Test
    void shouldPreserveOwnerWhenUpdatingLaboratory() {
        loginAs(adminA);
        Laboratory request = new Laboratory()
                .setId(laboratoryA.getId())
                .setName("负责人不可转移-" + System.nanoTime())
                .setAdminUserId(adminB.getId());

        laboratoryService.updateLaboratory(request);
        Laboratory updated = laboratoryService.getById(laboratoryA.getId());

        assertEquals(adminA.getId(), updated.getAdminUserId());
        assertNotEquals(adminB.getId(), updated.getAdminUserId());
        assertEquals(request.getName(), updated.getName());
    }

    private User createAdmin(String phone, String nickName) {
        User user = new User()
                .setPhone(phone)
                .setNickName(nickName)
                .setIcon("")
                .setRole(UserRole.LAB_ADMIN);
        assertTrue(userService.save(user));
        return user;
    }

    private Laboratory createLaboratory(String name, Long adminUserId) {
        Laboratory laboratory = new Laboratory()
                .setName(name)
                .setCampus("测试校区")
                .setBuilding("测试楼")
                .setRoom("T101")
                .setDescription("归属自动化测试")
                .setStatus(LaboratoryStatus.OPEN)
                .setAdminUserId(adminUserId);
        assertTrue(laboratoryService.save(laboratory));
        return laboratory;
    }

    private Equipment createEquipment(String name, Long laboratoryId, Long categoryId) {
        Equipment equipment = new Equipment()
                .setLaboratoryId(laboratoryId)
                .setCategoryId(categoryId)
                .setName(name)
                .setDescription("归属自动化测试")
                .setLocation("T101-TEST")
                .setUsageRequirement("仅供自动化测试")
                .setStatus(EquipmentStatus.AVAILABLE)
                .setBookingEnabled(true);
        assertTrue(equipmentService.save(equipment));
        return equipment;
    }

    private void loginAs(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setRole(UserRole.LAB_ADMIN);
        UserHolder.saveUser(userDTO);
    }

    private MockMvc exceptionAdviceMockMvc() {
        return MockMvcBuilders.standaloneSetup(new ExceptionProbeController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @RestController
    static class ExceptionProbeController {

        @PostMapping(value = "/exception-probe/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Result parseJson(@RequestBody Result request) {
            return Result.ok(request);
        }

        @GetMapping("/exception-probe/runtime")
        public Result throwRuntimeException() {
            throw new IllegalStateException("探针异常");
        }
    }
}

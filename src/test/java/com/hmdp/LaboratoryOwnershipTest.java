package com.hmdp;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.Laboratory;
import com.hmdp.entity.User;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.LaboratoryStatus;
import com.hmdp.enums.UserRole;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class LaboratoryOwnershipTest {

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private IUserService userService;

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
}

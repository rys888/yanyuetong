package com.yanyuetong.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yanyuetong.entity.Laboratory;

public interface ILaboratoryService extends IService<Laboratory> {

    /**
     * 断言当前登录用户是目标实验室的负责人。
     * 失败与"实验室不存在"统一抛 ForbiddenException，故意不区分，避免向非负责人泄露资源是否存在。
     */
    void assertManageable(Long laboratoryId);

    /** 创建实验室；创建者自动成为负责人（忽略请求体里的 adminUserId） */
    Long saveLaboratory(Laboratory laboratory);

    /** 修改实验室；仅负责人可改，且不允许通过请求体转移负责人 */
    void updateLaboratory(Laboratory laboratory);

    /** 删除实验室；仅负责人可删 */
    void deleteLaboratory(Long id);
}

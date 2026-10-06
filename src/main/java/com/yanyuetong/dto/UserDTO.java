package com.yanyuetong.dto;

import com.yanyuetong.enums.UserRole;
import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    private UserRole role;
}

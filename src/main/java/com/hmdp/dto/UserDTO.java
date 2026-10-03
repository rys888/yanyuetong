package com.hmdp.dto;

import com.hmdp.enums.UserRole;
import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    private UserRole role;
}

package com.hmdp.dto;

import lombok.Data;

//“它是 User 对象的‘脱敏精简版’，专门用于在网络传输和 Session 中只保留 ID、昵称、头像等安全信息，隐藏掉密码等敏感字段。”
//“UserDTO 是 User 对象的安全精简版，屏蔽了密码等敏感字段，仅保留 ID、昵称和头像用于前端展示和 Session 存储。”
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}

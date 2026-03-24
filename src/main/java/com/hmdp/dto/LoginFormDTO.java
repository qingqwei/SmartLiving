package com.hmdp.dto;
//真正干活的地方。它继承了接口，把逻辑写死。yue

import lombok.Data;

@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}

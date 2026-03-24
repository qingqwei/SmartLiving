package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

// 基于 ThreadLocal 封装的工具类，用于在一次请求处理中保存和获取当前登录用户
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}

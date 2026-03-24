package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 正则校验工具类
 */
public class RegexUtils {
    /**
     * 校验手机号是否无效
     * @param phone 待校验手机号
     * @return true 表示无效，false 表示有效
     */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }

    /**
     * 校验邮箱格式是否无效
     * @param email 待校验邮箱
     * @return true 表示无效，false 表示有效
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 校验验证码格式是否无效
     * @param code 待校验验证码
     * @return true 表示无效，false 表示有效
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 统一校验字符串是否不符合正则格式
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}

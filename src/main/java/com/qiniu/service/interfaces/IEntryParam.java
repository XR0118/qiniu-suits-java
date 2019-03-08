package com.qiniu.service.interfaces;

import java.io.IOException;

public interface IEntryParam {

    /**
     * 获取属性值，判断是否存在相应的 key，要求不存在或 value 为空则抛出异常
     * @param key 属性名
     * @return 属性值字符
     * @throws IOException
     */
    String getValue(String key) throws IOException;

    /**
     * 获取属性值，不抛出异常，使用 default 值进行返回，要求原值为空时同样返回默认值
     * @param key
     * @param Default 默认返回值
     * @return 属性值字符
     */
    String getValue(String key, String Default);

    /**
     * 获取属性值，通过反射转换成指定类型
     * @param key
     * @param clazz 返回值类型 class
     * @param Default
     * @param <T> 范型
     * @return
     * @throws Exception
     */
    <T> T getValue(String key, Class<T> clazz, T Default) throws Exception;
}

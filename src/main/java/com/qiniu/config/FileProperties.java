package com.qiniu.config;

import com.qiniu.service.interfaces.IEntryParam;

import java.io.*;
import java.util.Properties;

public class FileProperties implements IEntryParam {

    private Properties properties;

    public FileProperties(String resourceName) throws IOException {
        InputStream inputStream = null;

        try {
            inputStream = new FileInputStream(resourceName);
            properties = new Properties();
            properties.load(new InputStreamReader(new BufferedInputStream(inputStream), "utf-8"));
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    inputStream = null;
                }
            }
        }
    }

    /**
     * 获取属性值，判断是否存在相应的 key，不存在或 value 为空则抛出异常
     * @param key 属性名
     * @return 属性值字符
     * @throws IOException
     */
    public String getValue(String key) throws IOException {
        if ("".equals(properties.getProperty(key, ""))) {
            throw new IOException("not set \"" + key + "\" param.");
        } else {
            return properties.getProperty(key);
        }
    }

    /**
     * 获取属性值，不抛出异常，使用 default 值进行返回
     * @param key
     * @param Default 默认返回值
     * @return 属性值字符
     */
    public String getValue(String key, String Default) {
        if ("".equals(properties.getProperty(key, ""))) return Default;
        return properties.getProperty(key, Default);
    }

    /**
     * 获取属性值，通过反射转换成指定类型
     * @param key
     * @param clazz 返回值类型 class
     * @param Default
     * @param <T> 范型
     * @return
     * @throws Exception
     */
    public <T> T getValue(String key, Class<T> clazz, T Default) throws Exception {
        if ("".equals(properties.getProperty(key, ""))) {
            return Default;
        } else {
            return (T) clazz.getMethod("valueOf", clazz.getClasses()).invoke(clazz, properties.getProperty(key));
        }
    }
}

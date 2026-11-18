package com.leyon.lumin.foundation.storage;

/**
 * 不可变正文对象存储端口（O24 / 03 §4.1 第 5 条）：正文按版本存入、不可覆盖；
 * 厂商经适配层隔离（境内 S3 兼容私有存储，05 §7——真实接入待供应商合同验收）。
 */
public interface ContentObjectStore {

    /**
     * 写入不可覆盖对象：对象键已存在时抛出异常（O24 版本不可变）。
     *
     * @param objectKey 服务端生成的键（如 {@code content/{versionId}/{sha256}.txt}）
     * @param content   对象字节（UTF-8 正文）
     */
    void putImmutable(String objectKey, byte[] content);

    /** 读取对象字节（摘要核对与受控读取使用）。 */
    byte[] read(String objectKey);
}

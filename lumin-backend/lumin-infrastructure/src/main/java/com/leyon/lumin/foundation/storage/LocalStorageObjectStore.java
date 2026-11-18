package com.leyon.lumin.foundation.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地文件系统适配器（**开发/测试骨架，如实登记**）：实现
 * {@link ContentObjectStore} 端口，写入 {@code lumin.storage.dir} 基目录。
 * 生产接入境内 S3 兼容私有适配器（05 §7：300 秒 PUT / hash 复验 / 安全检查
 * 真实验收），本类届时整体替换；不可覆盖语义两端一致。
 */
@Component
public class LocalStorageObjectStore implements ContentObjectStore {

    private final Path baseDir;

    public LocalStorageObjectStore(
            @Value("${lumin.storage.dir:${user.home}/.lumin-storage}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public void putImmutable(String objectKey, byte[] content) {
        Path path = resolve(objectKey);
        try {
            if (Files.exists(path)) {
                throw new IllegalStateException("对象已存在，不可覆盖（O24）：" + objectKey);
            }
            Files.createDirectories(path.getParent());
            Files.write(path, content);
            if (Files.size(path) != content.length) {
                throw new IllegalStateException("写入字节数与摘要不符：" + objectKey);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("对象写入失败：" + objectKey, e);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        try {
            return Files.readAllBytes(resolve(objectKey));
        } catch (IOException e) {
            throw new UncheckedIOException("对象读取失败：" + objectKey, e);
        }
    }

    /** 对象键由服务端生成；拒绝绝对路径与相对逃逸段，防目录穿越。 */
    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/")
                || objectKey.contains("..")) {
            throw new IllegalArgumentException("非法对象键：" + objectKey);
        }
        return baseDir.resolve(objectKey);
    }
}

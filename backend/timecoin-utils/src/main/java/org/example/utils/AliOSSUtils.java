package org.example.utils;

import org.springframework.stereotype.Component;

/**
 * OSS扩展占位。当前使用本地图片上传，不启用OSS。
 * 后续接入时通过环境变量注入ALIBABA_CLOUD_ACCESS_KEY_ID和ALIBABA_CLOUD_ACCESS_KEY_SECRET。
 */
@Component
public class AliOSSUtils {
}

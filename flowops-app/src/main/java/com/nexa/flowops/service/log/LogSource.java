package com.nexa.flowops.service.log;

import java.nio.file.Path;
import java.util.List;

/**
 * 日志数据源抽象，屏蔽底层存储差异（本地文件、远程存储等）
 */
public interface LogSource {

    /**
     * 列出指定服务、类型、日期下的日志文件名
     *
     * @param serviceId 服务 ID
     * @param type      日志类型（deploy / app）
     * @param date      日期目录名（yyyy-MM-dd）
     * @return 文件名列表（已排序）
     */
    List<String> listFiles(Long serviceId, String type, String date);

    /**
     * 读取日志文件内容（支持从末尾偏移读取，适用于增量加载）
     *
     * @param serviceId 服务 ID
     * @param type      日志类型（deploy / app）
     * @param date      日期目录名（yyyy-MM-dd）
     * @param filename  文件名
     * @param offset    从文件末尾往前偏移的字节数
     * @param limit     读取的最大字节数
     * @return 日志内容
     */
    String readContent(Long serviceId, String type, String date, String filename, long offset, long limit);

    /**
     * 列出指定服务、类型下有日志的日期目录
     *
     * @param serviceId 服务 ID
     * @param type      日志类型（deploy / app）
     * @return 日期列表（已排序，格式 yyyy-MM-dd）
     */
    List<String> listDates(Long serviceId, String type);

    /**
     * 解析日志文件的实际文件系统路径（用于 WebSocket 增量监看等场景）
     *
     * @param serviceId 服务 ID
     * @param type      日志类型（deploy / app）
     * @param date      日期目录名（yyyy-MM-dd）
     * @param filename  文件名
     * @return 文件系统路径
     */
    Path resolveLogPath(Long serviceId, String type, String date, String filename);
}

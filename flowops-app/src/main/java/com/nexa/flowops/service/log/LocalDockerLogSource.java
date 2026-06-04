package com.nexa.flowops.service.log;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地 Docker 部署日志数据源实现
 * 日志目录结构：{app.logs.path}/{projectId}/{serviceId}/{type}/{date}/
 */
@Component
public class LocalDockerLogSource implements LogSource {

    private static final Logger log = LoggerFactory.getLogger(LocalDockerLogSource.class);

    @Value("${app.logs.path}")
    private String logBasePath;

    private final DeployServiceMapper serviceMapper;

    public LocalDockerLogSource(DeployServiceMapper serviceMapper) {
        this.serviceMapper = serviceMapper;
    }

    @Override
    public List<String> listFiles(Long serviceId, String type, String date) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) return List.of();

        Path dir = buildLogDir(service, type).resolve(date);
        if (!Files.isDirectory(dir)) return List.of();

        List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("列出日志文件失败: {}", e.getMessage());
        }
        Collections.sort(files);
        return files;
    }

    @Override
    public String readContent(Long serviceId, String type, String date, String filename, long offset, long limit) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) return "服务不存在";

        Path dir = buildLogDir(service, type).resolve(date);
        Path filePath = dir.resolve(filename).normalize();

        // 路径遍历校验
        if (!filePath.startsWith(dir)) {
            return "非法文件路径";
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return "日志文件不存在";
        }

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            long fileLength = raf.length();
            // 从末尾往前偏移，offset 表示跳过末尾多少字节
            long startPos = Math.max(0, fileLength - offset - limit);
            long endPos = Math.max(0, fileLength - offset);
            if (startPos >= endPos) return "";

            raf.seek(startPos);
            int length = (int) (endPos - startPos);
            byte[] buffer = new byte[length];
            raf.readFully(buffer);
            return new String(buffer);
        } catch (IOException e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    @Override
    public List<String> listDates(Long serviceId, String type) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) return List.of();

        Path dir = buildLogDir(service, type);
        if (!Files.isDirectory(dir)) return List.of();

        List<String> dates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    dates.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("列出日志日期失败: {}", e.getMessage());
        }
        Collections.sort(dates);
        return dates;
    }

    private Path buildLogDir(DeployService service, String type) {
        return Path.of(logBasePath,
                String.valueOf(service.getProjectId()),
                String.valueOf(service.getId()),
                type);
    }
}

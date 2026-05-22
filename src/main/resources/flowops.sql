/*
 Navicat Premium Data Transfer

 Source Server         : 阿里云包年
 Source Server Type    : MySQL
 Source Server Version : 80024 (8.0.24)
 Source Host           : 121.40.154.188:3306
 Source Schema         : flowops

 Target Server Type    : MySQL
 Target Server Version : 80024 (8.0.24)
 File Encoding         : 65001

 Date: 18/05/2026 00:36:10
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for deploy_record
-- ----------------------------
DROP TABLE IF EXISTS `deploy_record`;
CREATE TABLE `deploy_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `service_id` bigint NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `log_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of deploy_record
-- ----------------------------
INSERT INTO `deploy_record` VALUES (14, 1, 'success', NULL, NULL, '2026-05-10 08:24:55');
INSERT INTO `deploy_record` VALUES (15, 1, 'success', NULL, NULL, '2026-05-10 08:35:47');
INSERT INTO `deploy_record` VALUES (16, 1, 'success', NULL, NULL, '2026-05-10 08:45:35');
INSERT INTO `deploy_record` VALUES (17, 1, 'success', NULL, NULL, '2026-05-10 08:50:06');
INSERT INTO `deploy_record` VALUES (18, 1, 'success', NULL, NULL, '2026-05-10 08:54:03');
INSERT INTO `deploy_record` VALUES (19, 1, 'success', NULL, NULL, '2026-05-10 09:26:18');
INSERT INTO `deploy_record` VALUES (20, 1, 'success', NULL, NULL, '2026-05-11 14:19:26');

-- ----------------------------
-- Table structure for deploy_service
-- ----------------------------
DROP TABLE IF EXISTS `deploy_service`;
CREATE TABLE `deploy_service`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `port` int NOT NULL,
  `volume_dir` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `service_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'backend',
  `service_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'stopped',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `name`(`name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of deploy_service
-- ----------------------------
INSERT INTO `deploy_service` VALUES (1, 'volleball', 8091, '/data/flowops/services/volleball', 'fullstack', '{\"backend\":{\"baseImage\":\"openjdk:17-jdk-slim\",\"containerPort\":8090,\"startupCommand\":\"java -jar /app/app.jar\",\"envVars\":{\"SPRING_PROFILES_ACTIVE\":\"prod\",\"FILE_BASE_URL\":\"http://121.40.154.188:8091/api/file\"},\"dataMount\":{\"containerPath\":\"/app/static\",\"hostDir\":\"./data\"}},\"frontend\":{\"baseImage\":\"nginx:alpine\",\"backendUrl\":null,\"proxyRules\":[{\"path\":\"/api/\",\"directives\":[{\"name\":\"proxy_pass\",\"value\":\"http://backend:8090/api/\"},{\"name\":\"proxy_set_header\",\"value\":\"Host $http_host\"},{\"name\":\"proxy_set_header\",\"value\":\"X-Real-IP $remote_addr\"},{\"name\":\"proxy_set_header\",\"value\":\"X-Forwarded-For $proxy_add_x_forwarded_for\"},{\"name\":\"proxy_set_header\",\"value\":\"X-Forwarded-Proto $scheme\"}]},{\"path\":\"/api/sse/\",\"directives\":[{\"name\":\"proxy_pass\",\"value\":\"http://backend:8090/api/sse/\"},{\"name\":\"proxy_set_header\",\"value\":\"Host $host\"},{\"name\":\"proxy_set_header\",\"value\":\"X-Real-IP $remote_addr\"},{\"name\":\"proxy_set_header\",\"value\":\"X-Forwarded-For $proxy_add_x_forwarded_for\"},{\"name\":\"proxy_set_header\",\"value\":\"X-Forwarded-Proto $scheme\"},{\"name\":\"proxy_buffering\",\"value\":\"off\"},{\"name\":\"proxy_cache\",\"value\":\"off\"},{\"name\":\"proxy_read_timeout\",\"value\":\"86400s\"},{\"name\":\"proxy_send_timeout\",\"value\":\"86400s\"}]}],\"customNginxConfig\":null}}', 'running', NULL, NULL);

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'user',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
-- 注意: 以下为开发环境默认账户，密码为明文，生产环境请修改密码并使用 BCrypt 加密
INSERT INTO `sys_user` VALUES (1, 'admin', 'admin123', 'admin', '2026-05-08 17:13:50');

SET FOREIGN_KEY_CHECKS = 1;

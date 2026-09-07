-- 纵横四海 - 建表脚本
-- 说明：MyBatis 不自动建表，换库/新环境时先执行本脚本。
--      原 JPA 阶段由 ddl-auto=update 生成，此文件与其结构保持一致。

CREATE DATABASE IF NOT EXISTS age_of_voyages
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE age_of_voyages;

CREATE TABLE IF NOT EXISTS players (
  client_id     VARCHAR(64)  NOT NULL COMMENT '前端生成的玩家标识（PK）',
  name          VARCHAR(64)  NULL     COMMENT '船长名',
  gold          INT          NOT NULL COMMENT '金币',
  port          VARCHAR(255) NULL     COMMENT '当前所在港口 id',
  cargo_cap     INT          NOT NULL COMMENT '船舱上限',
  cargo         TEXT         NULL     COMMENT '货舱 JSON，如 {"wine":5}，由 CargoTypeHandler 转换',
  traveling     BIT(1)       NOT NULL COMMENT '是否航行中',
  traveling_to  VARCHAR(255) NULL     COMMENT '航行目标港口 id',
  arrive_at     BIGINT       NOT NULL COMMENT '到达时间戳（毫秒）',
  PRIMARY KEY (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家存档';

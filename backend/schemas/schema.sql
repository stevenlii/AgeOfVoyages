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

-- 海上遭遇概率配置（一行一个事件）。改这里即可调概率/条件，游戏内即时生效，无需重启。
CREATE TABLE IF NOT EXISTS seafare_event (
  id            INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
  event_code    VARCHAR(32)  NOT NULL COMMENT '事件类型：bottle 漂流瓶 / ambient 海上见闻 / pirate 海盗 / lightning 雷击',
  event_name    VARCHAR(32)  NOT NULL COMMENT '事件名称（如 漂流瓶）',
  per_km        DOUBLE       NOT NULL COMMENT '每公里概率（乘本次推进公里≈本击触发概率）',
  sea_req       VARCHAR(16)  NOT NULL DEFAULT 'any' COMMENT '发生海域：ocean=仅大洋深处(海盗/雷击这类坏事) / coastal=仅近海 / any=不限',
  min_start_km  DOUBLE       NOT NULL DEFAULT 0 COMMENT '需航行多少公里后才允许发生（防开局即触发，如漂流瓶）',
  min_spacing_km DOUBLE      NOT NULL DEFAULT 0 COMMENT '同类事件两次的最小间隔公里',
  max_per_voyage INT         NOT NULL DEFAULT 0 COMMENT '一次航行的最多发生次数（0 = 不限）',
  min_voyage_km DOUBLE       NOT NULL DEFAULT 0 COMMENT '航线总里程需达到多少公里才允许发生（如雷击≥3000km）',
  remark        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_code (event_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='海上遭遇配置（一行一个事件）';

INSERT INTO seafare_event (event_code, event_name, per_km, sea_req, min_start_km, min_spacing_km, max_per_voyage, min_voyage_km, remark) VALUES
  ('bottle',    '漂流瓶',   0.0017, 'any',   100, 600, 5,  0,    '走出一段（100km）后才可能捞到；600km 内最多一次；一次航行最多 5 次'),
  ('ambient',   '海上见闻', 0.002,  'any',   0,   300, 10, 0,    '飞鱼/海豚/海鸟等小插曲；300km 内最多一次；一次航行最多 10 次'),
  ('pirate',    '海盗',     0.0015, 'ocean', 0,   1000, 2, 0,    '只在大洋深处（离任何港口≥150km）；1000km 内最多一次；一次航行最多 2 次'),
  ('lightning', '雷击',     0.001,  'ocean', 0,   150, 1,  3000, '雷雨天 + 大洋深处；航线≥3000km 才会遇到；一次航行只 1 次')
ON DUPLICATE KEY UPDATE id = id;

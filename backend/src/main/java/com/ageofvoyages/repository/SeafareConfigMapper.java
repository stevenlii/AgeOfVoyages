package com.ageofvoyages.repository;

import com.ageofvoyages.model.SeafareEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 海上遭遇配置数据访问（一行一个事件，key=event_code）。改动即时生效，无需重启。 */
@Mapper
public interface SeafareConfigMapper {

    @Select("SELECT id, event_code, event_name, per_km, sea_req, min_start_km, min_spacing_km, " +
            "max_per_voyage, min_voyage_km, remark FROM seafare_event ORDER BY id")
    List<SeafareEventEntity> findAll();
}
package com.ageofvoyages.repository;

import com.ageofvoyages.model.CargoTypeHandler;
import com.ageofvoyages.model.PlayerEntity;
import org.apache.ibatis.annotations.*;

/** 玩家数据访问（MyBatis）。players 表由 JPA 阶段已建好，这里直接复用其结构：
 *  client_id(VARCHAR PK) / name / gold / port / cargo_cap / cargo(TEXT) / traveling / traveling_to / arrive_at */
@Mapper
public interface PlayerMapper {

    @Select("SELECT client_id, name, gold, port, cargo_cap, cargo, traveling, traveling_to, arrive_at " +
            "FROM players WHERE client_id = #{clientId}")
    PlayerEntity findById(@Param("clientId") String clientId);

    @Insert("INSERT INTO players (client_id, name, gold, port, cargo_cap, cargo, traveling, traveling_to, arrive_at) " +
            "VALUES (#{clientId}, #{name}, #{gold}, #{port}, #{cargoCap}, " +
            "#{cargo, typeHandler=com.ageofvoyages.model.CargoTypeHandler}, " +
            "#{traveling}, #{travelingTo}, #{arriveAt})")
    void insert(PlayerEntity e);

    @Update("UPDATE players SET name=#{name}, gold=#{gold}, port=#{port}, cargo_cap=#{cargoCap}, " +
            "cargo=#{cargo, typeHandler=com.ageofvoyages.model.CargoTypeHandler}, " +
            "traveling=#{traveling}, traveling_to=#{travelingTo}, arrive_at=#{arriveAt} " +
            "WHERE client_id=#{clientId}")
    void update(PlayerEntity e);
}

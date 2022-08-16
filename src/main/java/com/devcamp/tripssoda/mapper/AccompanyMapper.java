package com.devcamp.tripssoda.mapper;

import com.devcamp.tripssoda.dto.AccompanyDto;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface AccompanyMapper {
    public int count() throws Exception;
    public int insert(AccompanyDto dto) throws Exception;
    public AccompanyDto select(Integer id) throws Exception;
    public int update(AccompanyDto dto) throws Exception;
    public int delete(Map map) throws Exception;
    public int increaseViewCnt(Integer id) throws Exception;
    public int deleteAll() throws Exception;
    public List<AccompanyDto> selectPage(Map map);
    public List<AccompanyDto> selectAll();
}

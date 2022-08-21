package com.devcamp.tripssoda.service;

import com.devcamp.tripssoda.dto.QuestionDto;
import com.devcamp.tripssoda.dto.SearchCondition;
import java.util.List;
import java.util.Map;
import java.util.List;

public interface QuestionService {
    List<QuestionDto> selectAllUserQuestion(Integer userId, SearchCondition sc);

    int selectAllUserQuestionCnt(Integer userId);

    int getCount() throws Exception;

    int write(QuestionDto dto) throws Exception;

    QuestionDto read(Integer id) throws Exception;

    int modify(QuestionDto dto) throws Exception;

    int remove(Integer id, Integer userId) throws Exception;

    List<QuestionDto> getPage(Map map) throws Exception;

    int modifyAnswerCnt(Integer questionId, Integer cnt) throws Exception;

}

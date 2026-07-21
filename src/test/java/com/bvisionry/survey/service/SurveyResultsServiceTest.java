package com.bvisionry.survey.service;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.entity.SurveyResponse;
import com.bvisionry.survey.repository.SurveyAnswerRepository;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurveyResultsServiceTest {

    @Mock SurveyService surveyService;
    @Mock SurveyResponseRepository responseRepository;
    @Mock SurveyAnswerRepository answerRepository;
    @Mock CountryCatalog countryCatalog;

    @InjectMocks SurveyResultsService service;

    @Test
    void deleteResponse_deletesWhenScopedToSurvey() {
        UUID surveyId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        SurveyResponse response = new SurveyResponse();
        when(responseRepository.findByIdAndSurveyId(responseId, surveyId))
                .thenReturn(Optional.of(response));

        service.deleteResponse(surveyId, responseId);

        verify(responseRepository).delete(response);
    }

    @Test
    void deleteResponse_notFoundInSurvey_throws() {
        UUID surveyId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        when(responseRepository.findByIdAndSurveyId(responseId, surveyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteResponse(surveyId, responseId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(responseRepository, never()).delete(any(SurveyResponse.class));
    }
}

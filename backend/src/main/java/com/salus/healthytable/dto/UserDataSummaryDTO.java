package com.salus.healthytable.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 사용자 한 명이 서비스에 남긴 데이터 건수 요약 DTO입니다.
 * 내 계정 화면에서 저장된 데이터 현황을 보여 줄 때 사용합니다(GET /api/users/me/data-summary).
 */
@Data
@Builder
public class UserDataSummaryDTO {
    private long healthProfiles;
    private long healthCheckups;
    private long fridgeItems;
    private long mealLogs;
    private long recommendations;
    private long activityLogs;
    private long communityPosts;
    private long comments;
    private long likes;
    private long recipeShares;
    private long payments;
    private long chatSessions;
    private long chatMessages;
}

package com.mychefai.healthytable.dto;

import lombok.Builder;
import lombok.Data;

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
}

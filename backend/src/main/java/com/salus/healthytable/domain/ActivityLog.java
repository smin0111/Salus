package com.salus.healthytable.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 사용자의 하루 활동 기록 엔티티입니다(activity_logs 테이블).
 *
 * (user_id, activity_date) 조합이 유일하므로 한 사용자는 하루에 한 행만 가집니다.
 * 관리자 대시보드의 DAU(일일 활동 사용자)와 AI 사용량 통계 계산에 사용합니다.
 */
@Entity
@Table(name = "activity_logs", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "activity_date" })
})
@Getter
@Setter
@NoArgsConstructor
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: 실제로 user 값을 사용할 때까지 User 조회 쿼리를 미룹니다(불필요한 조인 방지).
    // @JsonIgnore: 엔티티를 JSON으로 바꿀 때 User 전체가 응답에 섞여 나가지 않게 합니다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    // 그날 AI 채팅/레시피 생성 등 AI 기능을 한 번이라도 사용했는지 여부
    @Column(name = "has_ai_interaction")
    private Boolean hasAiInteraction = false;
}

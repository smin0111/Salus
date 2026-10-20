package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 외부 레시피 검색 결과 캐시 엔티티입니다(search_cache 테이블).
 *
 * found=false로 저장된 검색어는 "검색해도 근거를 못 찾은 검색어"(negative cache)입니다.
 * 일정 기간 동안 같은 검색어로 외부 API를 반복 호출하지 않게 해 줍니다.
 */
@Entity
@Table(name = "search_cache")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SearchCache {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String query;

    @Column(nullable = false)
    private boolean found;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

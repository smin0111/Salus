package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

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

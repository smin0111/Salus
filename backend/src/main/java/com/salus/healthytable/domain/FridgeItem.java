package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "fridge_items")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FridgeItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    private String name;
    private String quantity;
    private String category;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;
}

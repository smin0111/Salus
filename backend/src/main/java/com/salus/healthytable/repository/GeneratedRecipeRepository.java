package com.salus.healthytable.repository;

import com.salus.healthytable.domain.GeneratedRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GeneratedRecipeRepository extends JpaRepository<GeneratedRecipe, Long> {
    List<GeneratedRecipe> findByTitle(String title);
}

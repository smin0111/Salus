package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.GeneratedRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GeneratedRecipeRepository extends JpaRepository<GeneratedRecipe, Long> {
    List<GeneratedRecipe> findByTitle(String title);
}

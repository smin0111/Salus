ALTER TABLE recipes
  ADD COLUMN calories_per_serving INT NULL AFTER calories;

UPDATE recipes
SET calories_per_serving = calories
WHERE base_servings IS NOT NULL
  AND base_servings > 0
  AND calories IS NOT NULL;

ALTER TABLE generated_recipes
  ADD COLUMN servings INT NULL AFTER cooking_time,
  ADD COLUMN calories_per_serving INT NULL AFTER calories;

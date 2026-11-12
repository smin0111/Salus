CREATE TABLE medication_food_label_evidence (
    evidence_key VARCHAR(64) NOT NULL,
    product_name TEXT NOT NULL,
    ingredient_name TEXT NOT NULL,
    mfds_item_sequence VARCHAR(255) NOT NULL,
    rxcui VARCHAR(255) NOT NULL,
    food_concept TEXT NOT NULL,
    effect_type VARCHAR(64) NOT NULL,
    evidence_strength VARCHAR(64) NOT NULL,
    recommendation TEXT NOT NULL,
    label_excerpt TEXT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    source_title TEXT NOT NULL,
    source_url TEXT NOT NULL,
    source_effective_at DATETIME(6),
    source_fetched_at DATETIME(6) NOT NULL,
    PRIMARY KEY (evidence_key)
);

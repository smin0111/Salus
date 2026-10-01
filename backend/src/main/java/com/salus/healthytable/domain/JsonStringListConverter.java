package com.salus.healthytable.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.io.IOException;
import java.util.List;

/**
 * JPA 엔티티의 {@code List<String>} 필드를 DB의 JSON 문자열 컬럼으로 변환하는 컨버터입니다.
 *
 * 예) ["우유", "땅콩"] ⇄ "[\"우유\",\"땅콩\"]"
 * 엔티티 필드에 {@code @Convert(converter = JsonStringListConverter.class)}를 붙여 사용합니다.
 */
@Converter
public class JsonStringListConverter implements AttributeConverter<List<String>, String> {

    private final ObjectMapper mapper = new ObjectMapper();

    // 엔티티 → DB: 리스트를 JSON 문자열로 직렬화합니다.
    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting list to JSON", e);
        }
    }

    // DB → 엔티티: JSON 문자열을 다시 리스트로 역직렬화합니다.
    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            // 제네릭 타입(List<String>) 정보는 런타임에 사라지므로 TypeReference로 타입을 알려 줍니다.
            return mapper.readValue(dbData, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            throw new RuntimeException("Error converting JSON to list", e);
        }
    }
}

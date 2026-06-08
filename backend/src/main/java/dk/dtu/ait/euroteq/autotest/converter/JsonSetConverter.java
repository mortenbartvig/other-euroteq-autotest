package dk.dtu.ait.euroteq.autotest.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Set;

@Converter(autoApply = true)
public class JsonSetConverter implements AttributeConverter<Set<String>, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Set<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize set to JSON", e);
        }
    }

    @Override
    public Set<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return Set.of();
        }
        try {
            return mapper.readValue(dbData, new TypeReference<Set<String>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize JSON to set", e);
        }
    }
}

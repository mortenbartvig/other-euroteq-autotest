package dk.dtu.ait.euroteq.autotest.converter;

import dk.dtu.ait.euroteq.autotest.entity.AcademicLevel;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AcademicLevelAttributeConverter implements AttributeConverter<AcademicLevel, String> {

    @Override
    public String convertToDatabaseColumn(AcademicLevel attribute) {
        return attribute != null ? attribute.name().toLowerCase() : null;
    }

    @Override
    public AcademicLevel convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String normalized = dbData.trim().toLowerCase();
        switch (normalized) {
            case "bachelor":    return AcademicLevel.BACHELOR;
            case "master":      return AcademicLevel.MASTER;
            case "doctoral":
            case "phd":         return AcademicLevel.DOCTORAL;
            default:
                throw new IllegalArgumentException("Unknown AcademicLevel in DB: " + dbData);
        }
    }
}

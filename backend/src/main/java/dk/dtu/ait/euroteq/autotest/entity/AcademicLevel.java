package dk.dtu.ait.euroteq.autotest.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AcademicLevel {
    BACHELOR,
    MASTER,
    DOCTORAL;

    @JsonCreator
    public static AcademicLevel fromString(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase();
        switch (normalized) {
            case "bachelor":    return BACHELOR;
            case "master":      return MASTER;
            case "doctoral":
            case "phd":         return DOCTORAL;
            default:            throw new IllegalArgumentException("Unknown AcademicLevel: " + value);
        }
    }

    @JsonValue
    public String toValue() {
        return name().toLowerCase();
    }
}

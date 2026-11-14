package ai.voicecraft.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {
    @Override
    public String convertToDatabaseColumn(List<String> list) {
        return list == null ? "" : String.join("|", list);
    }

    @Override
    public List<String> convertToEntityAttribute(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split("\\|")).filter(x -> !x.isBlank()).toList();
    }
}

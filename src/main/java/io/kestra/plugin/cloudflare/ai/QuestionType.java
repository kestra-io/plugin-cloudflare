package io.kestra.plugin.cloudflare.ai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    title = "Question type",
    description = "`NOUL` returns the probability of yes, `CHOICE` picks one option, `SCORE` rates along ordered levels."
)
public enum QuestionType {
    NOUL,
    CHOICE,
    SCORE;

    // Cloudflare sends and expects the lowercase form, flows use the uppercase one.
    @JsonCreator
    public static QuestionType fromValue(String value) {
        if (value != null) {
            for (var type : values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Invalid question type '" + value + "'. Set 'type' to one of: NOUL, CHOICE, SCORE.");
    }

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

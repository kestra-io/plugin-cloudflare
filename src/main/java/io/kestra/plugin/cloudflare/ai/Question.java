package io.kestra.plugin.cloudflare.ai;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    title = "Question",
    description = "A typed question evaluated against the state."
)
public class Question {

    private static final int MIN_CHOICE_OPTIONS = 2;
    private static final int MAX_CHOICE_OPTIONS = 255;
    private static final int MIN_SCORE_LEVELS = 2;
    private static final int MAX_SCORE_LEVELS = 10;
    private static final Set<String> NOUL_CRITERIA_KEYS = Set.of("true", "false");

    @Schema(
        title = "Question type",
        description = "`NOUL` (yes/no), `CHOICE` (pick one option) or `SCORE` (rate along ordered levels)."
    )
    private QuestionType type;

    @Schema(
        title = "Instructions",
        description = "What the model should decide. A string, or an object or array holding the question in one field and the data it refers to in the others.",
        anyOf = { String.class, Map.class, List.class }
    )
    private Object instructions;

    @Schema(
        title = "Criteria",
        description = """
            Depends on the question type:
            `CHOICE` requires 2 to 255 options, either as a map of option name to description or as a list of option names when none needs a description. In a map, use an empty string for an option without a description: Kestra drops entries whose value is null.
            `SCORE` requires an ordered list of level descriptions, lowest first, with 2 to 10 levels.
            `NOUL` optionally takes a map with the keys `true` and `false` describing what a yes and a no mean.
            """,
        anyOf = { Map.class, List.class }
    )
    private Object criteria;

    void validate(String id) {
        var prefix = "Invalid question '" + id + "': ";
        if (type == null) {
            throw new IllegalArgumentException(prefix + "'type' is missing. Set it to one of: NOUL, CHOICE, SCORE.");
        }
        if (isEmpty(instructions)) {
            throw new IllegalArgumentException(prefix + "'instructions' is missing or empty. Set it to the question the model should answer.");
        }
        switch (type) {
            case NOUL -> validateNoulCriteria(prefix);
            case CHOICE -> validateChoiceCriteria(prefix);
            case SCORE -> validateScoreCriteria(prefix);
        }
    }

    Map<String, Object> toApiMap() {
        var api = new LinkedHashMap<String, Object>();
        api.put("type", type.apiValue());
        api.put("instructions", instructions);
        var apiCriteria = type == QuestionType.CHOICE ? choiceOptions() : criteria;
        if (apiCriteria != null) {
            api.put("criteria", apiCriteria);
        }
        return api;
    }

    // Cloudflare expects a map of option name to description, with null for "no description".
    private Map<String, Object> choiceOptions() {
        var options = new LinkedHashMap<String, Object>();
        if (criteria instanceof Map<?, ?> map) {
            map.forEach((name, description) -> options.put(String.valueOf(name), description instanceof String str && str.isBlank() ? null : description));
        } else if (criteria instanceof List<?> names) {
            names.forEach(name -> options.put(String.valueOf(name), null));
        }
        return options;
    }

    private void validateNoulCriteria(String prefix) {
        if (criteria == null) {
            return;
        }
        if (!(criteria instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(prefix + "'criteria' of a NOUL question must be a map with the keys 'true' and 'false', or be omitted.");
        }
        for (var key : map.keySet()) {
            if (!NOUL_CRITERIA_KEYS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(prefix + "'criteria' of a NOUL question only accepts the keys 'true' and 'false', got '" + key + "'.");
            }
        }
    }

    private void validateChoiceCriteria(String prefix) {
        Collection<?> names = switch (criteria) {
            case Map<?, ?> map -> map.keySet();
            case List<?> list -> list;
            case null, default -> throw new IllegalArgumentException(
                prefix + "'criteria' of a CHOICE question must be a map of option name to description, or a list of option names."
            );
        };
        for (var name : names) {
            if (!(name instanceof String str) || str.isBlank()) {
                throw new IllegalArgumentException(prefix + "option names in 'criteria' must be non-empty strings, got '" + name + "'.");
            }
        }
        var distinct = Set.copyOf(names).size();
        if (distinct != names.size()) {
            throw new IllegalArgumentException(prefix + "option names in 'criteria' must be unique.");
        }
        if (distinct < MIN_CHOICE_OPTIONS || distinct > MAX_CHOICE_OPTIONS) {
            throw new IllegalArgumentException(
                prefix + "a CHOICE question needs " + MIN_CHOICE_OPTIONS + " to " + MAX_CHOICE_OPTIONS + " options in 'criteria', got " + distinct
                    + ". An option whose description is null is dropped by Kestra: use an empty string instead."
            );
        }
    }

    private void validateScoreCriteria(String prefix) {
        if (!(criteria instanceof List<?> levels)) {
            throw new IllegalArgumentException(prefix + "'criteria' of a SCORE question must be an ordered list of level descriptions, lowest first.");
        }
        if (levels.size() < MIN_SCORE_LEVELS || levels.size() > MAX_SCORE_LEVELS) {
            throw new IllegalArgumentException(
                prefix + "a SCORE question needs " + MIN_SCORE_LEVELS + " to " + MAX_SCORE_LEVELS + " levels in 'criteria', got " + levels.size() + "."
            );
        }
    }

    private static boolean isEmpty(Object value) {
        return switch (value) {
            case null -> true;
            case String str -> str.isBlank();
            case Map<?, ?> map -> map.isEmpty();
            case Collection<?> collection -> collection.isEmpty();
            default -> false;
        };
    }
}

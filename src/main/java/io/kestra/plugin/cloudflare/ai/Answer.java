package io.kestra.plugin.cloudflare.ai;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Mirrors the three answer shapes of Cloudflare's schema-output.json for @cf/cloudflare/clef.
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    title = "Answer",
    description = "The answer to one question. Only the fields matching the question type are set."
)
public class Answer {

    @Schema(
        title = "Answer type",
        description = "Matches the question type: `NOUL`, `CHOICE` or `SCORE`."
    )
    private QuestionType type;

    @Schema(
        title = "Yes probability",
        description = "Only for `NOUL`: the probability that the answer is yes, from 0 (no) to 1 (yes)."
    )
    private Double noul;

    @Schema(
        title = "Chosen option",
        description = "Only for `CHOICE`: the option with the highest probability."
    )
    private String choice;

    @Schema(
        title = "Score",
        description = "Only for `SCORE`: the probability-weighted level, where 0 is the lowest level. It can land between two levels."
    )
    private Double score;

    @Schema(
        title = "Legend",
        description = "Only for `SCORE`: each level index mapped back to its description."
    )
    private Map<String, Object> legend;

    @Schema(
        title = "Probabilities",
        description = "Only for `CHOICE` and `SCORE`: the probability of each option or level. Values sum to 1."
    )
    private Map<String, Double> probabilities;

    @Schema(
        title = "Confidence",
        description = "Only for `CHOICE` and `SCORE`: how certain the model is, from 0 to 1, derived from the probabilities."
    )
    private Double confidence;
}

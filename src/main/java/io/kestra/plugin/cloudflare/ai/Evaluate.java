package io.kestra.plugin.cloudflare.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.cloudflare.AbstractCloudflareTask;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Evaluate content with Cloudflare Clef decision models",
    description = """
        Sends a state and a map of typed questions to a Clef decision model on Workers AI and returns one answer per question.
        Each answer carries calibrated probabilities instead of generated text, so a flow can route on a yes/no, a choice or a score without parsing LLM output.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Route a support request to a team",
            full = true,
            code = """
                id: cloudflare_triage_ticket
                namespace: company.team

                inputs:
                  - id: message
                    type: STRING

                tasks:
                  - id: classify
                    type: io.kestra.plugin.cloudflare.ai.Evaluate
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    model: CLEF_FLASH
                    state: "{{ inputs.message }}"
                    questions:
                      team:
                        type: CHOICE
                        instructions: Which team should handle this request?
                        criteria:
                          billing: Payments, invoices, and refunds
                          technical: Outages, errors, and configuration
                          sales: Plans and upgrades

                  - id: log_team
                    type: io.kestra.plugin.core.log.Log
                    message: "Route to {{ outputs.classify.answers.team.choice }}"
                """
        ),
        @Example(
            title = "Escalate only urgent and severe incidents",
            full = true,
            code = """
                id: cloudflare_incident_gate
                namespace: company.team

                inputs:
                  - id: report
                    type: STRING

                tasks:
                  - id: evaluate
                    type: io.kestra.plugin.cloudflare.ai.Evaluate
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    state: "{{ inputs.report }}"
                    questions:
                      urgent:
                        type: NOUL
                        instructions: Is this incident urgent?
                      severity:
                        type: SCORE
                        instructions: How severe is the customer impact?
                        criteria:
                          - No impact
                          - Minor
                          - Major
                          - Critical

                  - id: escalate
                    type: io.kestra.plugin.core.flow.If
                    condition: "{{ outputs.evaluate.answers.urgent.noul > 0.8 and outputs.evaluate.answers.severity.score >= 2 }}"
                    then:
                      - id: notify
                        type: io.kestra.plugin.core.log.Log
                        message: "Escalating incident"
                """
        ),
        @Example(
            title = "Evaluate every webhook payload as it arrives",
            full = true,
            code = """
                id: cloudflare_webhook_moderation
                namespace: company.team

                triggers:
                  - id: on_comment
                    type: io.kestra.plugin.core.trigger.Webhook
                    key: "{{ secret('WEBHOOK_KEY') }}"

                tasks:
                  - id: moderate
                    type: io.kestra.plugin.cloudflare.ai.Evaluate
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    state: "{{ trigger.body.comment }}"
                    questions:
                      abusive:
                        type: NOUL
                        instructions: Is this comment abusive?
                """
        )
    },
    metrics = {
        @Metric(
            name = "input.tokens",
            type = Counter.TYPE,
            unit = "tokens",
            description = "Number of input tokens Cloudflare reported for the request."
        ),
        @Metric(
            name = "output.tokens",
            type = Counter.TYPE,
            unit = "tokens",
            description = "Number of output tokens Cloudflare reported for the request."
        )
    }
)
public class Evaluate extends AbstractCloudflareTask implements RunnableTask<Evaluate.Output> {

    private static final int MAX_QUESTIONS = 64;
    private static final Pattern QUESTION_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    // Kestra's mappers drop nulls, but a CHOICE option without a description must be sent as null.
    private static final ObjectMapper REQUEST_MAPPER = MAPPER.copy().setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);

    // Fails on "[1] some text" instead of silently keeping only "[1]".
    private static final ObjectMapper TEMPLATE_MAPPER = MAPPER.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(
        title = "Model",
        description = "`CLEF` runs `@cf/cloudflare/clef`, `CLEF_FLASH` runs the smaller and faster `@cf/cloudflare/clef-flash`."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Model> model = Property.ofValue(Model.CLEF);

    @Schema(
        title = "State",
        description = "The content to evaluate: a string, or structured data (object or array) such as records, chat logs or application state. Write structured data as a YAML map or list: its keys are kept as written and each value is rendered on its own, as text. An expression such as `{{ trigger.body }}` always renders to text, even when the result is valid JSON.",
        anyOf = { String.class, Map.class, List.class }
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Object> state;

    @Schema(
        title = "Questions",
        description = "Typed questions keyed by an id you choose, 1 to 64 entries. Ids may use letters, digits, `_`, `.` and `-`, up to 100 characters. Answers come back under the same ids."
    )
    @NotNull
    @PluginProperty(group = "main", additionalProperties = Question.class)
    private Property<Map<String, Question>> questions;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        var logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rModel = runContext.render(model).as(Model.class).orElse(Model.CLEF);
        var rState = renderState(runContext);
        var rQuestions = runContext.render(questions).asMap(String.class, Question.class);

        if (rState == null) {
            throw new IllegalArgumentException("Invalid 'state': the content to evaluate is empty. Set it to a string, an object or an array.");
        }
        validateQuestions(rQuestions);

        var apiQuestions = new LinkedHashMap<String, Object>();
        rQuestions.forEach((id, question) -> apiQuestions.put(id, question.toApiMap()));

        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", rModel.apiValue);
        requestBody.put("state", rState);
        requestBody.put("questions", apiQuestions);

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + "/accounts/" + encodePathSegment(rAccountId) + "/ai/run/@cf/cloudflare/" + rModel.apiValue))
            .body(
                HttpRequest.StringRequestBody.builder()
                    .content(serialize(requestBody))
                    .contentType("application/json")
                    .charset(StandardCharsets.UTF_8)
                    .build()
            )
            .addHeader("Accept", "application/json");
        addAuthHeader(runContext, requestBuilder);

        logger.debug("Evaluating {} question(s) with model '{}'", rQuestions.size(), rModel.apiValue);

        HttpResponse<String> response;
        try {
            response = execute(runContext, requestBuilder.build());
        } catch (HttpClientResponseException e) {
            throw withHint(e);
        }

        var result = parse(response.getBody());
        var answers = result.answers();
        if (answers == null || answers.isEmpty()) {
            throw new IllegalStateException("Cloudflare returned no answers for model '" + rModel.apiValue + "'.");
        }
        var missing = rQuestions.keySet().stream()
            .filter(id -> answers.get(id) == null)
            .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Cloudflare returned no answer for question(s): " + missing + ".");
        }

        if (result.usage() != null) {
            runContext.metric(Counter.of("input.tokens", Objects.requireNonNullElse(result.usage().inputTokens(), 0L)));
            runContext.metric(Counter.of("output.tokens", Objects.requireNonNullElse(result.usage().outputTokens(), 0L)));
        }

        return Output.builder()
            .answers(answers)
            .model(result.model())
            .build();
    }

    private static void validateQuestions(Map<String, Question> rQuestions) {
        if (rQuestions.isEmpty()) {
            throw new IllegalArgumentException("Invalid 'questions': at least one question is required.");
        }
        if (rQuestions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException(
                "Invalid 'questions': got " + rQuestions.size() + " questions, Cloudflare accepts at most " + MAX_QUESTIONS + " per request."
            );
        }
        rQuestions.forEach((id, question) ->
        {
            if (id == null || !QUESTION_ID.matcher(id).matches()) {
                throw new IllegalArgumentException(
                    "Invalid question id '" + id + "': ids may only use letters, digits, '_', '.' and '-', up to 100 characters."
                );
            }
            if (question == null) {
                throw new IllegalArgumentException("Invalid question '" + id + "': the question is empty. Set at least 'type' and 'instructions'.");
            }
            question.validate(id);
        });
    }

    private static String serialize(Map<String, Object> requestBody) {
        try {
            return REQUEST_MAPPER.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize the evaluation request: 'state' and 'questions' must be valid JSON values", e);
        }
    }

    // The raw template tells a YAML map or list apart from an expression that renders to JSON text.
    private Object renderState(RunContext runContext) throws IllegalVariableEvaluationException {
        if (state == null) {
            return null;
        }
        var template = MAPPER.valueToTree(state).asText();
        var trimmed = template.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                var structure = renderLeaves(runContext, TEMPLATE_MAPPER.readValue(trimmed, Object.class));
                return isEmpty(structure) ? null : structure;
            } catch (JsonProcessingException ignored) {
                // Plain text such as "[urgent] site is down".
            }
        }
        var text = runContext.render(state).as(Object.class).map(String::valueOf).orElse(null);
        return text == null || text.isBlank() ? null : text;
    }

    // Only values are rendered, so inserted content stays inside its field and cannot add or replace keys.
    private static Object renderLeaves(RunContext runContext, Object node) throws IllegalVariableEvaluationException {
        switch (node) {
            case String str -> {
                return runContext.render(str);
            }
            case Map<?, ?> map -> {
                var rendered = new LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    rendered.put(String.valueOf(entry.getKey()), renderLeaves(runContext, entry.getValue()));
                }
                return rendered;
            }
            case List<?> list -> {
                var rendered = new ArrayList<Object>();
                for (var item : list) {
                    rendered.add(renderLeaves(runContext, item));
                }
                return rendered;
            }
            case null, default -> {
                return node;
            }
        }
    }

    private static boolean isEmpty(Object structure) {
        return structure instanceof Map<?, ?> map ? map.isEmpty() : structure instanceof List<?> list && list.isEmpty();
    }

    // Accepts the result with or without Cloudflare's {success, errors, result} envelope.
    private static EvaluateResult parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Cloudflare returned an empty response body. Retry the task; if it keeps happening, check the Workers AI status.");
        }
        try {
            var root = MAPPER.readTree(body);
            var success = root.get("success");
            if (success != null && success.isBoolean() && !success.asBoolean()) {
                throw new IllegalStateException("Cloudflare reported a failed evaluation: " + root.path("errors"));
            }
            JsonNode payload = root.path("result").isObject() ? root.get("result") : root;
            return MAPPER.treeToValue(payload, EvaluateResult.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse the Cloudflare Workers AI response", e);
        }
    }

    private static HttpClientResponseException withHint(HttpClientResponseException e) {
        if (e.getResponse() == null || e.getResponse().getStatus() == null) {
            return e;
        }
        var hint = switch (e.getResponse().getStatus().getCode()) {
            case 401 -> "Check that 'apiToken' holds a valid Cloudflare API token.";
            case 403 -> "Check that the API token has the Workers AI permission on the account set in 'accountId'.";
            case 400, 422 -> "Cloudflare rejected the request body. Check each question's 'type', 'instructions' and 'criteria'.";
            case 404 -> "Check that 'accountId' is correct and that Workers AI is enabled on the account.";
            default -> null;
        };
        if (hint == null) {
            return e;
        }
        return new HttpClientResponseException(e.getMessage() + ". " + hint, e.getResponse(), e);
    }

    public enum Model {
        CLEF("clef"),
        CLEF_FLASH("clef-flash");

        private final String apiValue;

        Model(String apiValue) {
            this.apiValue = apiValue;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvaluateResult(String model, Map<String, Answer> answers, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(
        @JsonProperty("input_tokens") Long inputTokens,
        @JsonProperty("output_tokens") Long outputTokens) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Answers",
            description = "One answer per question, keyed by the ids used in `questions`."
        )
        private final Map<String, Answer> answers;

        @Schema(
            title = "Model",
            description = "The model that performed the evaluation, as reported by Cloudflare."
        )
        private final String model;
    }
}

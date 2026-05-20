package io.kestra.plugin.cloudflare.workers;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest(startRunner = true)
class WorkersRunnerTest {

    @Test
    @Disabled("requires a live Cloudflare gateway worker reachable at gatewayUrl and a valid GATEWAY_TOKEN secret")
    @ExecuteFlow("sanity-checks/workers_dynamic_run.yaml")
    void dynamicRun(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @Test
    @Disabled("requires a live Cloudflare account id and a valid CLOUDFLARE_API_TOKEN secret")
    @ExecuteFlow("sanity-checks/workers_scripts_list.yaml")
    void scriptsList(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }
}

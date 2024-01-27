/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.integTest;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.opensearch.client.*;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.MLModel;
import org.opensearch.ml.common.MLTask;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.input.execute.agent.AgentMLInput;
import org.opensearch.ml.common.output.model.ModelTensor;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.output.model.ModelTensors;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;

import lombok.SneakyThrows;

public abstract class BaseAgentToolsIT extends OpenSearchSecureRestTestCase {
    public static final Gson gson = new Gson();
    private static final int MAX_TASK_RESULT_QUERY_TIME_IN_SECOND = 60 * 5;
    private static final int DEFAULT_TASK_RESULT_QUERY_INTERVAL_IN_MILLISECOND = 1000;

    /**
     * Update cluster settings to run ml models
     */
    @Before
    public void updateClusterSettings() {
        updateClusterSettings("plugins.ml_commons.only_run_on_ml_node", false);
        // default threshold for native circuit breaker is 90, it may be not enough on test runner machine
        updateClusterSettings("plugins.ml_commons.native_memory_threshold", 100);
        updateClusterSettings("plugins.ml_commons.allow_registering_model_via_url", true);
    }

    @SneakyThrows
    protected void updateClusterSettings(String settingKey, Object value) {
        XContentBuilder builder = XContentFactory
            .jsonBuilder()
            .startObject()
            .startObject("persistent")
            .field(settingKey, value)
            .endObject()
            .endObject();
        Response response = makeRequest(
            client(),
            "PUT",
            "_cluster/settings",
            null,
            builder.toString(),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, ""))
        );

        assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
    }

    @SneakyThrows
    private Map<String, Object> parseResponseToMap(Response response) {
        Map<String, Object> responseInMap = XContentHelper
            .convertToMap(XContentType.JSON.xContent(), EntityUtils.toString(response.getEntity()), false);
        return responseInMap;
    }

    @SneakyThrows
    private Object parseFieldFromResponse(Response response, String field) {
        assertNotNull(field);
        Map map = parseResponseToMap(response);
        Object result = map.get(field);
        assertNotNull(result);
        return result;
    }

    protected String createConnector(String requestBody) {
        Response response = makeRequest(client(), "POST", "/_plugins/_ml/connectors/_create", null, requestBody, null);
        assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        return parseFieldFromResponse(response, MLModel.CONNECTOR_ID_FIELD).toString();
    }

    protected String registerModel(String requestBody) {
        Response response = makeRequest(client(), "POST", "/_plugins/_ml/models/_register", null, requestBody, null);
        assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        return parseFieldFromResponse(response, MLTask.TASK_ID_FIELD).toString();
    }

    protected String deployModel(String modelId) {
        Response response = makeRequest(client(), "POST", "/_plugins/_ml/models/" + modelId + "/_deploy", null, (String) null, null);
        assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        return parseFieldFromResponse(response, MLTask.TASK_ID_FIELD).toString();
    }

    protected String indexMonitor(String monitorAsJsonString) {
        Response response = makeRequest(client(), "POST", "_plugins/_alerting/monitors", null, monitorAsJsonString, null);

        assertEquals(RestStatus.CREATED, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        return parseFieldFromResponse(response, "_id").toString();
    }

    @SneakyThrows
    protected Map<String, Object> waitTaskComplete(String taskId) {
        for (int i = 0; i < MAX_TASK_RESULT_QUERY_TIME_IN_SECOND; i++) {
            Response response = makeRequest(client(), "GET", "/_plugins/_ml/tasks/" + taskId, null, (String) null, null);
            assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
            Map<String, Object> responseInMap = parseResponseToMap(response);
            String state = responseInMap.get(MLTask.STATE_FIELD).toString();
            if (state.equals(MLTaskState.COMPLETED.toString())) {
                return responseInMap;
            }
            if (state.equals(MLTaskState.FAILED.toString())
                || state.equals(MLTaskState.CANCELLED.toString())
                || state.equals(MLTaskState.COMPLETED_WITH_ERROR.toString())) {
                fail("The task failed with state " + state);
            }
            Thread.sleep(DEFAULT_TASK_RESULT_QUERY_INTERVAL_IN_MILLISECOND);
        }
        fail("The task failed to complete after " + MAX_TASK_RESULT_QUERY_TIME_IN_SECOND + " seconds.");
        return null;
    }

    // Register the model then deploy it. Returns the model_id until the model is deployed
    protected String registerModelThenDeploy(String requestBody) {
        String registerModelTaskId = registerModel(requestBody);
        Map<String, Object> registerTaskResponseInMap = waitTaskComplete(registerModelTaskId);
        String modelId = registerTaskResponseInMap.get(MLTask.MODEL_ID_FIELD).toString();
        String deployModelTaskId = deployModel(modelId);
        waitTaskComplete(deployModelTaskId);
        return modelId;
    }

    protected void createIndexWithConfiguration(String indexName, String indexConfiguration) throws Exception {
        Response response = makeRequest(client(), "PUT", indexName, null, indexConfiguration, null);
        Map<String, Object> responseInMap = parseResponseToMap(response);
        assertEquals("true", responseInMap.get("acknowledged").toString());
        assertEquals(indexName, responseInMap.get("index").toString());
    }

    // Similar to deleteExternalIndices, but including indices with "." prefix vs. excluding them
    protected void deleteSystemIndices() throws IOException {
        final Response response = client().performRequest(new Request("GET", "/_cat/indices?format=json" + "&expand_wildcards=all"));
        try (
            final XContentParser parser = JsonXContent.jsonXContent
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    response.getEntity().getContent()
                )
        ) {
            final XContentParser.Token token = parser.nextToken();
            final List<Map<String, Object>> parserList;
            if (token == XContentParser.Token.START_ARRAY) {
                parserList = parser.listOrderedMap().stream().map(obj -> (Map<String, Object>) obj).collect(Collectors.toList());
            } else {
                parserList = Collections.singletonList(parser.mapOrdered());
            }

            final List<String> externalIndices = parserList
                .stream()
                .map(index -> (String) index.get("index"))
                .filter(indexName -> indexName != null)
                .filter(indexName -> indexName.startsWith("."))
                .collect(Collectors.toList());

            for (final String indexName : externalIndices) {
                adminClient().performRequest(new Request("DELETE", "/" + indexName));
            }
        }
    }

    @SneakyThrows
    protected void addDocToIndex(String indexName, String docId, List<String> fieldNames, List<Object> fieldContents) {
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (int i = 0; i < fieldNames.size(); i++) {
            builder.field(fieldNames.get(i), fieldContents.get(i));
        }
        builder.endObject();
        Response response = makeRequest(
            client(),
            "POST",
            "/" + indexName + "/_doc/" + docId + "?refresh=true",
            null,
            builder.toString(),
            null
        );
        assertEquals(RestStatus.CREATED, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
    }

    public String createAgent(String requestBody) {
        Response response = makeRequest(client(), "POST", "/_plugins/_ml/agents/_register", null, requestBody, null);
        assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        return parseFieldFromResponse(response, AgentMLInput.AGENT_ID_FIELD).toString();
    }

    private String parseStringResponseFromExecuteAgentResponse(Response response) {
        Map responseInMap = parseResponseToMap(response);
        Optional<String> optionalResult = Optional
            .ofNullable(responseInMap)
            .map(m -> (List) m.get(ModelTensorOutput.INFERENCE_RESULT_FIELD))
            .map(l -> (Map) l.get(0))
            .map(m -> (List) m.get(ModelTensors.OUTPUT_FIELD))
            .map(l -> (Map) l.get(0))
            .map(m -> (String) (m.get(ModelTensor.RESULT_FIELD)));
        return optionalResult.get();
    }

    // execute the agent, and return the String response from the json structure
    // {"inference_results": [{"output": [{"name": "response","result": "the result to return."}]}]}
    public String executeAgent(String agentId, String requestBody) {
        Response response = makeRequest(client(), "POST", "/_plugins/_ml/agents/" + agentId + "/_execute", null, requestBody, null);
        return parseStringResponseFromExecuteAgentResponse(response);
    }

    public static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        String jsonEntity,
        List<Header> headers
    ) {
        HttpEntity httpEntity = StringUtils.isBlank(jsonEntity) ? null : new StringEntity(jsonEntity, ContentType.APPLICATION_JSON);
        return makeRequest(client, method, endpoint, params, httpEntity, headers);
    }

    public static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        HttpEntity entity,
        List<Header> headers
    ) {
        return makeRequest(client, method, endpoint, params, entity, headers, false);
    }

    @SneakyThrows
    public static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        HttpEntity entity,
        List<Header> headers,
        boolean strictDeprecationMode
    ) {
        Request request = new Request(method, endpoint);

        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        if (headers != null) {
            headers.forEach(header -> options.addHeader(header.getName(), header.getValue()));
        }
        options.setWarningsHandler(strictDeprecationMode ? WarningsHandler.STRICT : WarningsHandler.PERMISSIVE);
        request.setOptions(options.build());

        if (params != null) {
            params.forEach(request::addParameter);
        }
        if (entity != null) {
            request.setEntity(entity);
        }
        return client.performRequest(request);
    }
}

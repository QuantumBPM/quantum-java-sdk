package com.quantumbpm.client.bpmn;

import com.quantumbpm.client.generated.ApiException;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.BpmnInstanceChildrenResponse;
import com.quantumbpm.client.generated.model.BpmnInstancePaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnInstanceState;
import com.quantumbpm.client.generated.model.BpmnProcessSummaryPaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnProcessVersionPaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnResourceDetail;
import com.quantumbpm.client.generated.model.BpmnResourcePaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnResourceSummaryPaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnUserTaskPaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnValidateResponse;
import com.quantumbpm.client.generated.model.CorrelationKeys;
import com.quantumbpm.client.generated.model.CreateBpmnResourceRequest;
import com.quantumbpm.client.generated.model.GetBpmnInstanceVariables200Response;
import com.quantumbpm.client.generated.model.PublishBpmnMessageRequest;
import com.quantumbpm.client.generated.model.PublishBpmnSignalRequest;
import com.quantumbpm.client.generated.model.StartBpmnInstanceRequest;
import com.quantumbpm.client.generated.model.ThrowBpmnUserTaskErrorRequest;
import com.quantumbpm.client.generated.model.UpdateBpmnInstanceVariablesRequest;
import com.quantumbpm.client.generated.model.UpdateUserTaskAssignmentRequest;
import com.quantumbpm.client.generated.model.UserTask;
import com.quantumbpm.client.generated.model.ValidateBpmnResourceRequest;
import com.quantumbpm.client.variables.Vars;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Wraps the BPMN engine endpoints - resources, instances, messaging, user
 * tasks, processes - for a single project.
 */
public class BpmnClient {

    private final DefaultApi defaultApi;
    private final BpmnApi bpmnApi;
    private final UUID projectId;

    public BpmnClient(DefaultApi defaultApi, BpmnApi bpmnApi, UUID projectId) {
        this.defaultApi = defaultApi;
        this.bpmnApi = bpmnApi;
        this.projectId = projectId;
    }

    public UUID projectId() {
        return projectId;
    }

    // ------------------------------------------------------------ Resources

    public BpmnResourceDetail createResource(String name, String xml) throws ApiException {
        CreateBpmnResourceRequest body = new CreateBpmnResourceRequest();
        body.setName(name);
        body.setXml(xml);
        return bpmnApi.createBpmnResource(projectId, body);
    }

    public BpmnResourceDetail updateResource(UUID resourceId, String name, String xml) throws ApiException {
        CreateBpmnResourceRequest body = new CreateBpmnResourceRequest();
        body.setName(name);
        body.setXml(xml);
        return bpmnApi.updateBpmnResource(projectId, resourceId, body);
    }

    public void deleteResource(UUID resourceId) throws ApiException {
        bpmnApi.deleteBpmnResource(projectId, resourceId);
    }

    public BpmnResourceDetail getResource(UUID resourceId) throws ApiException {
        return bpmnApi.getBpmnResource(projectId, resourceId);
    }

    public void deployResource(UUID resourceId) throws ApiException {
        bpmnApi.deployBpmnResource(projectId, resourceId);
    }

    public BpmnValidateResponse validateXml(String xml) throws ApiException {
        ValidateBpmnResourceRequest body = new ValidateBpmnResourceRequest();
        body.setXml(xml);
        return bpmnApi.validateBpmnXml(projectId, body);
    }

    public BpmnResourcePaginatedResponse listResources(int page, int pageSize) throws ApiException {
        return bpmnApi.listBpmnResources(projectId, page, pageSize);
    }

    public BpmnResourceSummaryPaginatedResponse listLatestResources(int page, int pageSize) throws ApiException {
        return bpmnApi.listLatestBpmnResources(projectId, page, pageSize);
    }

    public BpmnResourcePaginatedResponse listResourceVersions(String definitionsId, int page, int pageSize) throws ApiException {
        return bpmnApi.listBpmnResourcesByDefinitionsID(projectId, definitionsId, page, pageSize);
    }

    // ------------------------------------------------------------ Instances

    /** Launch a new BPMN process instance and return the workflow ID. */
    public String startInstance(UUID processDefinitionId, Vars vars) throws ApiException {
        return startInstance(processDefinitionId, vars, null);
    }

    /**
     * Launch a new BPMN process instance, stamping it with a caller-supplied
     * {@code businessId} correlation key (order number, ticket ID, etc.).
     * The key is inherited by every child instance, external job, user task,
     * and DMN execution emitted by the resulting process.
     */
    public String startInstance(UUID processDefinitionId, Vars vars, String businessId) throws ApiException {
        StartBpmnInstanceRequest body = new StartBpmnInstanceRequest();
        body.setProcessDefinitionID(processDefinitionId);
        body.setVariables(vars.toWireMap());
        body.setBusinessId(businessId);
        var response = defaultApi.startBpmnInstance(projectId, body);
        if (response == null || response.getWorkflowID() == null) {
            throw new IllegalStateException("bpmn: startInstance returned no workflowID");
        }
        return response.getWorkflowID();
    }

    public BpmnInstanceState getInstance(String workflowId) throws ApiException {
        return defaultApi.getBpmnInstance(projectId, workflowId);
    }

    public void cancelInstance(String workflowId) throws ApiException {
        defaultApi.cancelBpmnInstance(projectId, workflowId);
    }

    public BpmnInstancePaginatedResponse listInstances(
            UUID definitionId,
            String status,
            Boolean hasIncident,
            Boolean suspended,
            OffsetDateTime createdAfter,
            Integer page,
            Integer pageSize
    ) throws ApiException {
        return listInstances(definitionId, status, hasIncident, suspended, createdAfter, null, page, pageSize);
    }

    /** List instances filtered by a caller-supplied businessId correlation key. */
    public BpmnInstancePaginatedResponse listInstances(
            UUID definitionId,
            String status,
            Boolean hasIncident,
            Boolean suspended,
            OffsetDateTime createdAfter,
            String businessId,
            Integer page,
            Integer pageSize
    ) throws ApiException {
        return defaultApi.listBpmnInstances(
                projectId, definitionId, status, hasIncident, suspended, createdAfter, businessId, page, pageSize);
    }

    public BpmnInstanceChildrenResponse getInstanceChildren(String workflowId) throws ApiException {
        return bpmnApi.getBpmnInstanceChildren(projectId, workflowId);
    }

    public Vars getInstanceVariables(String workflowId) throws ApiException {
        GetBpmnInstanceVariables200Response response = bpmnApi.getBpmnInstanceVariables(projectId, workflowId);
        if (response == null || response.getVariables() == null) {
            return new Vars();
        }
        return Vars.fromWireMap(response.getVariables());
    }

    public void updateInstanceVariables(String workflowId, Vars vars) throws ApiException {
        UpdateBpmnInstanceVariablesRequest body = new UpdateBpmnInstanceVariablesRequest();
        Map<String, Object> wire = vars.toWireMap();
        body.setVariables(wire == null ? Map.of() : wire);
        bpmnApi.updateBpmnInstanceVariables(projectId, workflowId, body);
    }

    public void resolveIncident(String workflowId, String incidentId, Vars vars) throws ApiException {
        GetBpmnInstanceVariables200Response body = new GetBpmnInstanceVariables200Response();
        if (vars != null) body.setVariables(vars.toWireMap());
        bpmnApi.resolveBpmnIncident(projectId, workflowId, incidentId, body);
    }

    // ------------------------------------------------------------ Messaging

    public void publishMessage(String name, Vars vars, CorrelationKeys correlationKeys, String ttl) throws ApiException {
        PublishBpmnMessageRequest body = new PublishBpmnMessageRequest();
        body.setMessageName(name);
        body.setCorrelationKeys(correlationKeys);
        body.setTtl(ttl);
        if (vars != null) body.setVariables(vars.toWireMap());
        bpmnApi.publishBpmnMessage(projectId, body);
    }

    public void publishMessage(String name, Vars vars) throws ApiException {
        publishMessage(name, vars, null, null);
    }

    public void publishSignal(String name, Vars vars, String ttl) throws ApiException {
        PublishBpmnSignalRequest body = new PublishBpmnSignalRequest();
        body.setSignalName(name);
        body.setTtl(ttl);
        if (vars != null) body.setVariables(vars.toWireMap());
        bpmnApi.publishBpmnSignal(projectId, body);
    }

    public void publishSignal(String name, Vars vars) throws ApiException {
        publishSignal(name, vars, null);
    }

    // ----------------------------------------------------------- User tasks

    public BpmnUserTaskPaginatedResponse listUserTasks(
            String workflowId,
            String status,
            String assignee,
            String candidateUser,
            String candidateGroup,
            Integer page,
            Integer pageSize) throws ApiException {
        return listUserTasks(workflowId, status, assignee, candidateUser, candidateGroup, null, page, pageSize);
    }

    /** List user tasks filtered by a caller-supplied businessId correlation key. */
    public BpmnUserTaskPaginatedResponse listUserTasks(
            String workflowId,
            String status,
            String assignee,
            String candidateUser,
            String candidateGroup,
            String businessId,
            Integer page,
            Integer pageSize) throws ApiException {
        return bpmnApi.listBpmnUserTasks(projectId, workflowId, status, assignee, candidateUser, candidateGroup, businessId, page, pageSize);
    }

    public BpmnUserTaskPaginatedResponse listUserTasksForCaller(int page, int pageSize) throws ApiException {
        return bpmnApi.listBpmnUserTasksForCaller(projectId, page, pageSize);
    }

    public UserTask getUserTask(String executionKey) throws ApiException {
        return bpmnApi.getBpmnUserTask(projectId, executionKey);
    }

    public UserTask updateUserTaskAssignment(String executionKey, UpdateUserTaskAssignmentRequest body) throws ApiException {
        return bpmnApi.updateBpmnUserTaskAssignment(projectId, executionKey, body);
    }

    public void completeUserTask(String executionKey, Vars vars) throws ApiException {
        GetBpmnInstanceVariables200Response body = new GetBpmnInstanceVariables200Response();
        if (vars != null) body.setVariables(vars.toWireMap());
        bpmnApi.completeBpmnUserTask(projectId, executionKey, body);
    }

    public void throwUserTaskError(String executionKey, String errorCode, Vars vars) throws ApiException {
        ThrowBpmnUserTaskErrorRequest body = new ThrowBpmnUserTaskErrorRequest();
        body.setErrorCode(errorCode);
        if (vars != null) body.setVariables(vars.toWireMap());
        bpmnApi.throwBpmnUserTaskError(projectId, executionKey, body);
    }

    // ------------------------------------------------------------ Processes

    public BpmnProcessSummaryPaginatedResponse listProcesses(Integer page, Integer pageSize, String search, OffsetDateTime createdAfter) throws ApiException {
        return listProcesses(page, pageSize, search, createdAfter, null);
    }

    /** {@code suspended} filters by paused versions: true keeps processes with at least one, false keeps those with none, null means no filter. */
    public BpmnProcessSummaryPaginatedResponse listProcesses(Integer page, Integer pageSize, String search, OffsetDateTime createdAfter, Boolean suspended) throws ApiException {
        return bpmnApi.listBpmnProcesses(projectId, page, pageSize, search, createdAfter, suspended);
    }

    public BpmnProcessVersionPaginatedResponse listProcessVersions(String processId, Integer page, Integer pageSize, OffsetDateTime createdAfter) throws ApiException {
        return listProcessVersions(processId, page, pageSize, createdAfter, null);
    }

    /** {@code suspended} filters by definition-scope suspension: true keeps paused versions, false keeps active ones, null means no filter. */
    public BpmnProcessVersionPaginatedResponse listProcessVersions(String processId, Integer page, Integer pageSize, OffsetDateTime createdAfter, Boolean suspended) throws ApiException {
        return bpmnApi.listBpmnProcessVersions(projectId, processId, page, pageSize, createdAfter, suspended);
    }
}

package com.quantumbpm.client.bpmn;

import com.quantumbpm.client.generated.ApiException;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.BpmnActivityState;
import com.quantumbpm.client.generated.model.BpmnHistoryPage;
import com.quantumbpm.client.generated.model.BpmnInstancePaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnInstanceState;
import com.quantumbpm.client.generated.model.BpmnProcessSummaryPaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnProcessVersion;
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

    /**
     * Launch a new instance of the latest version of a deployed BPMN process,
     * by its process ID, and return the instance ID.
     */
    public String startInstance(String processId, Vars vars) throws ApiException {
        return startInstance(processId, null, vars, null);
    }

    /**
     * Launch a new instance of the latest version, stamping it with a
     * caller-supplied {@code businessId} correlation key (order number,
     * ticket ID, etc.). The key is inherited by every child instance, external
     * job, user task, and DMN execution emitted by the resulting process.
     */
    public String startInstance(String processId, Vars vars, String businessId) throws ApiException {
        return startInstance(processId, null, vars, businessId);
    }

    /** Launch a new instance of {@code version}, the latest when null. */
    public String startInstance(String processId, Integer version, Vars vars, String businessId) throws ApiException {
        StartBpmnInstanceRequest body = new StartBpmnInstanceRequest();
        body.setProcessID(processId);
        body.setVersion(version);
        body.setVariables(vars.toWireMap());
        body.setBusinessId(businessId);
        var response = defaultApi.startBpmnInstance(projectId, body);
        if (response == null || response.getInstanceID() == null) {
            throw new IllegalStateException("bpmn: startInstance returned no instanceID");
        }
        return response.getInstanceID();
    }

    public BpmnInstanceState getInstance(String instanceId) throws ApiException {
        return defaultApi.getBpmnInstance(projectId, instanceId);
    }

    public void cancelInstance(String instanceId) throws ApiException {
        defaultApi.cancelBpmnInstance(projectId, instanceId);
    }

    /**
     * List a page of instances, newest first, of one process when
     * {@code processId} is set, and of one of its versions when
     * {@code version} is set too. Pass the page's
     * {@code getPagination().getNextCursor()} as {@code cursor} for the next
     * one. It is null on the last page.
     */
    public BpmnInstancePaginatedResponse listInstances(
            String processId,
            Integer version,
            String status,
            Boolean hasIncident,
            Boolean suspended,
            OffsetDateTime createdAfter,
            String cursor,
            Integer limit
    ) throws ApiException {
        return listInstances(processId, version, status, hasIncident, suspended, createdAfter, null, null, cursor, limit);
    }

    /**
     * List instances filtered by a caller-supplied businessId correlation
     * key. {@code draft} true keeps only test runs, false leaves them out.
     */
    public BpmnInstancePaginatedResponse listInstances(
            String processId,
            Integer version,
            String status,
            Boolean hasIncident,
            Boolean suspended,
            OffsetDateTime createdAfter,
            String businessId,
            Boolean draft,
            String cursor,
            Integer limit
    ) throws ApiException {
        return defaultApi.listBpmnInstances(
                projectId, status, hasIncident, suspended, createdAfter, businessId, processId, version, draft, cursor, limit);
    }

    /**
     * List a page of the instances this instance spawned via CallActivity,
     * newest first. Pass the page's {@code getPagination().getNextCursor()} as
     * {@code cursor} for the next one. It is null on the last page.
     */
    public BpmnInstancePaginatedResponse getInstanceChildren(String instanceId, String cursor, Integer limit) throws ApiException {
        return bpmnApi.getBpmnInstanceChildren(projectId, instanceId, cursor, limit);
    }

    /**
     * One page of the instance's history, oldest entry first. {@code fromSeq}
     * reads from that seq on, {@code beforeSeq} reads the newest entries
     * before it, and with both null the page holds the newest entries.
     */
    public BpmnHistoryPage getInstanceHistory(String instanceId, Long fromSeq, Long beforeSeq, Integer limit) throws ApiException {
        return bpmnApi.getBpmnInstanceHistory(projectId, instanceId, fromSeq, beforeSeq, limit);
    }

    /** One history entry by its seq, with its inputs and outputs. */
    public BpmnActivityState getInstanceHistoryEntry(String instanceId, long seq) throws ApiException {
        return bpmnApi.getBpmnInstanceHistoryEntry(projectId, instanceId, seq);
    }

    public Vars getInstanceVariables(String instanceId) throws ApiException {
        GetBpmnInstanceVariables200Response response = bpmnApi.getBpmnInstanceVariables(projectId, instanceId);
        if (response == null || response.getVariables() == null) {
            return new Vars();
        }
        return Vars.fromWireMap(response.getVariables());
    }

    public void updateInstanceVariables(String instanceId, Vars vars) throws ApiException {
        UpdateBpmnInstanceVariablesRequest body = new UpdateBpmnInstanceVariablesRequest();
        Map<String, Object> wire = vars.toWireMap();
        body.setVariables(wire == null ? Map.of() : wire);
        bpmnApi.updateBpmnInstanceVariables(projectId, instanceId, body);
    }

    public void resolveIncident(String instanceId, String incidentId, Vars vars) throws ApiException {
        GetBpmnInstanceVariables200Response body = new GetBpmnInstanceVariables200Response();
        if (vars != null) body.setVariables(vars.toWireMap());
        bpmnApi.resolveBpmnIncident(projectId, instanceId, incidentId, body);
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

    /**
     * List a page of user tasks. Pass the page's
     * {@code getPagination().getNextCursor()} as {@code cursor} for the next
     * one. It is null on the last page.
     */
    public BpmnUserTaskPaginatedResponse listUserTasks(
            String instanceId,
            String status,
            String assignee,
            String candidateUser,
            String candidateGroup,
            String cursor,
            Integer limit) throws ApiException {
        return listUserTasks(instanceId, status, assignee, candidateUser, candidateGroup, null, cursor, limit);
    }

    /** List user tasks filtered by a caller-supplied businessId correlation key. */
    public BpmnUserTaskPaginatedResponse listUserTasks(
            String instanceId,
            String status,
            String assignee,
            String candidateUser,
            String candidateGroup,
            String businessId,
            String cursor,
            Integer limit) throws ApiException {
        return listUserTasks(instanceId, status, assignee, candidateUser, candidateGroup, businessId, null, null, null, cursor, limit);
    }

    /**
     * List user tasks due from {@code dueAfter} (included) to {@code dueBefore}
     * (excluded). {@code sort} is created (the default), dueDate or priority.
     */
    public BpmnUserTaskPaginatedResponse listUserTasks(
            String instanceId,
            String status,
            String assignee,
            String candidateUser,
            String candidateGroup,
            String businessId,
            OffsetDateTime dueAfter,
            OffsetDateTime dueBefore,
            String sort,
            String cursor,
            Integer limit) throws ApiException {
        return bpmnApi.listBpmnUserTasks(projectId, instanceId, status, assignee, candidateUser, candidateGroup, businessId, dueAfter, dueBefore, sort, cursor, limit);
    }

    /** List a page of the user tasks the caller may claim or has claimed. */
    public BpmnUserTaskPaginatedResponse listUserTasksForCaller(String cursor, Integer limit) throws ApiException {
        return listUserTasksForCaller(null, null, null, cursor, limit);
    }

    /** List the caller's user tasks with the due range and sort of {@code listUserTasks}. */
    public BpmnUserTaskPaginatedResponse listUserTasksForCaller(
            OffsetDateTime dueAfter,
            OffsetDateTime dueBefore,
            String sort,
            String cursor,
            Integer limit) throws ApiException {
        return bpmnApi.listBpmnUserTasksForCaller(projectId, dueAfter, dueBefore, sort, cursor, limit);
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

    public BpmnProcessVersion getProcessVersion(String processId, int version, OffsetDateTime createdAfter) throws ApiException {
        return bpmnApi.getBpmnProcessVersion(projectId, processId, version, createdAfter);
    }
}

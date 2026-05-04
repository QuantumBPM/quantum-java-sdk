package com.quantumbpm.client.dmn;

import com.quantumbpm.client.generated.ApiException;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.BatchEvaluateDesignRequest;
import com.quantumbpm.client.generated.model.BatchEvaluationResponse;
import com.quantumbpm.client.generated.model.EvaluateDesignRequest;
import com.quantumbpm.client.generated.model.EvaluateStoredRequest;
import com.quantumbpm.client.generated.model.EvaluationResult;
import com.quantumbpm.client.variables.Vars;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Evaluates DMN definitions in a single project. */
public class DmnClient {

    private final DefaultApi api;
    private final UUID projectId;

    public DmnClient(DefaultApi api, UUID projectId) {
        this.api = api;
        this.projectId = projectId;
    }

    /**
     * Run a stored DMN definition identified by its DMN XML
     * {@code <definitions id="…">} value. Stable across versions, addressable
     * from the BPMN model.
     */
    public Map<String, EvaluationResult> evaluate(String definitionsId, Vars vars, EvaluateOption... options) throws ApiException {
        EvaluateOptions opts = EvaluateOptions.from(options);
        EvaluateStoredRequest body = new EvaluateStoredRequest();
        body.setContext(vars.toFeelContext());
        body.setVersion(opts.version);
        body.setDecisions(opts.decisions);
        body.setDecisionServices(opts.decisionServices);
        return api.evaluateByDefinitionsID(projectId, definitionsId, body, opts.version);
    }

    /**
     * Run a stored DMN definition addressed by its platform UUID. Prefer
     * {@link #evaluate} (by definitions id) for normal use.
     */
    public Map<String, EvaluationResult> evaluateById(UUID definitionId, Vars vars, EvaluateOption... options) throws ApiException {
        EvaluateOptions opts = EvaluateOptions.from(options);
        EvaluateStoredRequest body = new EvaluateStoredRequest();
        body.setContext(vars.toFeelContext());
        body.setVersion(opts.version);
        body.setDecisions(opts.decisions);
        body.setDecisionServices(opts.decisionServices);
        return api.evaluateStored(projectId, definitionId, body);
    }

    /** Run ad-hoc DMN XML against an input context. The XML is not stored. */
    public Map<String, EvaluationResult> evaluateDesign(String xml, Vars vars, DesignOption... options) throws ApiException {
        DesignOptions opts = DesignOptions.from(options);
        EvaluateDesignRequest body = new EvaluateDesignRequest();
        body.setXml(xml);
        body.setContext(vars.toFeelContext());
        body.setDecisions(opts.decisions);
        body.setDecisionServices(opts.decisionServices);
        body.setAdditionalXMLs(opts.additionalXmls);
        return api.evaluateDesign(body);
    }

    /** Evaluate the same XML against many input rows in one request. */
    public BatchEvaluationResponse evaluateDesignBatch(String xml, List<Vars> rows) throws ApiException {
        BatchEvaluateDesignRequest body = new BatchEvaluateDesignRequest();
        body.setXml(xml);
        body.setInputs(rows.stream().map(Vars::toMap).toList());
        return api.evaluateDesignBatch(body);
    }

    // -------------------- options --------------------

    @FunctionalInterface
    public interface EvaluateOption {
        void apply(EvaluateOptions opts);
    }

    @FunctionalInterface
    public interface DesignOption {
        void apply(DesignOptions opts);
    }

    public static final class EvaluateOptions {
        Integer version;
        List<String> decisions;
        List<String> decisionServices;

        static EvaluateOptions from(EvaluateOption[] options) {
            EvaluateOptions o = new EvaluateOptions();
            for (EvaluateOption opt : options) opt.apply(o);
            return o;
        }
    }

    public static final class DesignOptions {
        List<String> decisions;
        List<String> decisionServices;
        List<String> additionalXmls;

        static DesignOptions from(DesignOption[] options) {
            DesignOptions o = new DesignOptions();
            for (DesignOption opt : options) opt.apply(o);
            return o;
        }
    }

    /** Pin the evaluation to a specific version. */
    public static EvaluateOption withVersion(int version) {
        return o -> o.version = version;
    }

    /** Restrict evaluation to the named decisions. */
    public static EvaluateOption withDecisions(String... names) {
        return o -> o.decisions = List.of(names);
    }

    /** Restrict evaluation to the named decision services. */
    public static EvaluateOption withDecisionServices(String... names) {
        return o -> o.decisionServices = List.of(names);
    }

    /** Restrict design evaluation to the named decisions. */
    public static DesignOption withDesignDecisions(String... names) {
        return o -> o.decisions = List.of(names);
    }

    /** Restrict design evaluation to the named decision services. */
    public static DesignOption withDesignDecisionServices(String... names) {
        return o -> o.decisionServices = List.of(names);
    }

    /** Additional DMN XML documents whose decisions can be imported. */
    public static DesignOption withAdditionalXmls(String... xmls) {
        return o -> o.additionalXmls = List.of(xmls);
    }
}

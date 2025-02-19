package org.bf2.cos.fleetshard.operator.connector;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.bf2.cos.fleetshard.api.DeploymentSpecAware;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.api.ManagedConnectorConditions;
import org.bf2.cos.fleetshard.api.ManagedConnectorOperator;
import org.bf2.cos.fleetshard.api.ManagedConnectorSpec;
import org.bf2.cos.fleetshard.api.ManagedConnectorStatus;
import org.bf2.cos.fleetshard.api.Operator;
import org.bf2.cos.fleetshard.api.OperatorBuilder;
import org.bf2.cos.fleetshard.operator.FleetShardOperatorConfig;
import org.bf2.cos.fleetshard.operator.client.FleetShardClient;
import org.bf2.cos.fleetshard.operator.operand.OperandController;
import org.bf2.cos.fleetshard.operator.operand.OperandControllerMetricsWrapper;
import org.bf2.cos.fleetshard.operator.operand.OperandResourceWatcher;
import org.bf2.cos.fleetshard.support.client.EventClient;
import org.bf2.cos.fleetshard.support.exceptions.WrappedRuntimeException;
import org.bf2.cos.fleetshard.support.metrics.MetricsRecorder;
import org.bf2.cos.fleetshard.support.resources.ConfigMaps;
import org.bf2.cos.fleetshard.support.resources.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_DELETED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_READY;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_STOPPED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_UNASSIGNED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_DELETED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_DE_PROVISIONING;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_FAILED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_PROVISIONING;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_STOPPED;
import static org.bf2.cos.fleetshard.api.ManagedConnectorConditions.hasCondition;
import static org.bf2.cos.fleetshard.api.ManagedConnectorConditions.setCondition;
import static org.bf2.cos.fleetshard.support.OperatorSelectorUtil.available;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_CLUSTER_ID;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_CONNECTOR_ID;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_CONNECTOR_OPERATOR;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_CONNECTOR_TYPE_ID;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_DEPLOYMENT_ID;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_DEPLOYMENT_RESOURCE_VERSION;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_COMPONENT;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_CREATED_BY;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_INSTANCE;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_MANAGED_BY;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_NAME;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_PART_OF;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_KUBERNETES_VERSION;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_OPERATOR_OWNER;
import static org.bf2.cos.fleetshard.support.resources.Resources.LABEL_OPERATOR_TYPE;
import static org.bf2.cos.fleetshard.support.resources.Resources.copyAnnotation;
import static org.bf2.cos.fleetshard.support.resources.Resources.copyLabel;

@ControllerConfiguration(
    name = "connector",
    generationAwareEventProcessing = false)
public class ConnectorController implements Reconciler<ManagedConnector>, EventSourceInitializer<ManagedConnector> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorController.class);

    @Inject
    ManagedConnectorOperator managedConnectorOperator;
    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    FleetShardClient fleetShard;
    @Inject
    OperandController wrappedOperandController;
    @Inject
    EventClient eventClient;

    @Inject
    MeterRegistry registry;
    @Inject
    FleetShardOperatorConfig config;

    private OperandController operandController;
    private List<Tag> tags;

    @PostConstruct
    protected void setUp() {
        this.tags = List.of(
            Tag.of("cos.operator.id", managedConnectorOperator.getMetadata().getName()),
            Tag.of("cos.operator.type", managedConnectorOperator.getSpec().getType()),
            Tag.of("cos.operator.version", managedConnectorOperator.getSpec().getVersion()));

        if (config.metrics().connectorOperand().enabled()) {
            operandController = new OperandControllerMetricsWrapper(
                wrappedOperandController,
                MetricsRecorder.of(registry, config.metrics().baseName() + ".controller.event.operators.operand", tags));
        } else {
            operandController = wrappedOperandController;
        }
    }

    @Override
    public HashMap<String, EventSource> prepareEventSources(EventSourceContext context) {
        final HashMap<String, EventSource> eventSources = new HashMap<>();

        eventSources.put(
            "_secrets",
            new ConnectorSecretEventSource(
                kubernetesClient,
                managedConnectorOperator,
                MetricsRecorder.of(registry, config.metrics().baseName() + ".controller.event.secrets", tags)));

        eventSources.put(
            "_operators",
            new ConnectorOperatorEventSource(
                kubernetesClient,
                managedConnectorOperator,
                fleetShard.getNamespace(),
                MetricsRecorder.of(registry, config.metrics().baseName() + ".controller.event.operators", tags)));

        for (ResourceDefinitionContext res : operandController.getResourceTypes()) {
            final String id = res.getGroup() + "-" + res.getVersion() + "-" + res.getKind();

            eventSources.put(
                id,
                new OperandResourceWatcher(
                    kubernetesClient,
                    managedConnectorOperator,
                    res,
                    MetricsRecorder.of(registry, id, tags)));
        }

        return eventSources;
    }

    @Override
    public UpdateControl<ManagedConnector> reconcile(
        ManagedConnector connector,
        Context<ManagedConnector> context) {

        LOGGER.info("Reconcile {}:{}:{}@{} (phase={})",
            connector.getApiVersion(),
            connector.getKind(),
            connector.getMetadata().getName(),
            connector.getMetadata().getNamespace(),
            connector.getStatus().getPhase());

        final boolean selected = selected(connector);
        final boolean assigned = assigned(connector);

        final UpdateControl<ManagedConnector> answer;

        if (!selected && !assigned) {
            // not selected, nor assigned: this connector is managed by another operator
            LOGGER.debug("Connector {}/{} is not managed by this operator (assigned={}, operating={}). "
                + "This operator={}. Connector requires: {}.",
                connector.getMetadata().getNamespace(),
                connector.getMetadata().getName(),
                connector.getSpec().getOperatorSelector().getId(),
                connector.getStatus().getConnectorStatus().getAssignedOperator().getId(),
                managedConnectorOperator.getMetadata().getName(),
                connector.getSpec().getOperatorSelector().getId());

            answer = UpdateControl.noUpdate();
        } else if (!selected) {
            // not selected, but assigned: this connector needs to be handed to another operator
            LOGGER.debug("Connector {}/{} not selected but assigned: this connector needs to be handed to {} operator.",
                connector.getMetadata().getNamespace(),
                connector.getMetadata().getName(),
                connector.getSpec().getOperatorSelector().getId());

            if (!ManagedConnectorStatus.PhaseType.Error.equals(connector.getStatus().getPhase())
                && !ManagedConnectorStatus.PhaseType.Transferring.equals(connector.getStatus().getPhase())
                && !ManagedConnectorStatus.PhaseType.Transferred.equals(connector.getStatus().getPhase())) {
                // the connector needs to be transferred to another operator
                LOGGER.debug("Connector {}/{} needs to be transferred to {} operator.",
                    connector.getMetadata().getNamespace(),
                    connector.getMetadata().getName(),
                    connector.getSpec().getOperatorSelector().getId());

                connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Transferring);
                answer = UpdateControl.updateStatus(connector);
            } else {
                // the connector is transferring to another operator, just reconcile and wait.
                LOGGER.debug("Connector {}/{}, is transferring to {} operator, reconcile and wait.",
                    connector.getMetadata().getNamespace(),
                    connector.getMetadata().getName(),
                    connector.getSpec().getOperatorSelector().getId());

                answer = reconcile(connector);
            }
        } else if (!assigned) {
            // not assigned, but selected: this connector is being transferred to this operator.
            LOGGER.debug("Connector {}/{} not assigned, but selected.",
                connector.getMetadata().getNamespace(),
                connector.getMetadata().getName());

            if (connector.getStatus().getConnectorStatus().getAssignedOperator().getId() == null) {
                // this operator can start to manage this connector.
                LOGGER.debug("Connector {}/{} has just being assigned to this operator ({}), starting to manage it.",
                    connector.getMetadata().getNamespace(),
                    connector.getMetadata().getName(),
                    connector.getSpec().getOperatorSelector().getId());
                answer = reconcile(connector);
            } else {
                // transferring to this operator is still in progress, let's wait.
                LOGGER.debug("Skip connector: waiting for connector {}/{} to be transferred from operator: {}.",
                    connector.getMetadata().getNamespace(),
                    connector.getMetadata().getName(),
                    connector.getStatus().getConnectorStatus().getAssignedOperator().getId());

                answer = UpdateControl.noUpdate();
            }
        } else {
            // connector is assigned to this operator, reconcile it.
            LOGGER.debug("Connector: {}/{} is managed by this operator (assigned={}, operating={}).",
                connector.getMetadata().getNamespace(),
                connector.getMetadata().getName(),
                connector.getSpec().getOperatorSelector().getId(),
                connector.getStatus().getConnectorStatus().getAssignedOperator().getId());

            answer = reconcile(connector);
        }

        return answer;
    }

    private UpdateControl<ManagedConnector> reconcile(ManagedConnector resource) {
        if (resource.getStatus().getPhase() == null) {
            resource.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Initialization);
            resource.getStatus().getConnectorStatus().setPhase(STATE_PROVISIONING);
            resource.getStatus().getConnectorStatus().setConditions(Collections.emptyList());
        }

        return measure(
            config.metrics().baseName()
                + ".controller.connectors.reconcile."
                + resource.getStatus().getPhase().name().toLowerCase(Locale.US),
            resource,
            connector -> {
                switch (connector.getStatus().getPhase()) {
                    case Initialization:
                        return handleInitialization(connector);
                    case Augmentation:
                        return handleAugmentation(connector);
                    case Monitor:
                        return validate(connector, this::handleMonitor);
                    case Deleting:
                        return handleDeleting(connector);
                    case Deleted:
                        return validate(connector, this::handleDeleted);
                    case Stopping:
                        return handleStopping(connector);
                    case Stopped:
                        return validate(connector, this::handleStopped);
                    case Transferring:
                        return handleTransferring(connector);
                    case Transferred:
                        return handleTransferred(connector);
                    case Error:
                        return validate(connector, this::handleError);
                    default:
                        throw new UnsupportedOperationException(
                            "Unsupported phase: " + connector.getStatus().getPhase());
                }
            });
    }

    private UpdateControl<ManagedConnector> measure(
        String id,
        ManagedConnector connector,
        Function<ManagedConnector, UpdateControl<ManagedConnector>> action) {

        Counter.builder(id + ".count")
            .tags(tags)
            .tag("cos.connector.id", connector.getSpec().getConnectorId())
            .tag("cos.deployment.id", connector.getSpec().getDeploymentId())
            .tag("cos.deployment.resync", Boolean.toString(isResync(connector)))
            .register(registry)
            .increment();

        try {
            return Timer.builder(id + ".time")
                .tags(tags)
                .tag("cos.connector.id", connector.getSpec().getConnectorId())
                .tag("cos.deployment.id", connector.getSpec().getDeploymentId())
                .tag("cos.deployment.resync", Boolean.toString(isResync(connector)))
                .publishPercentiles(0.3, 0.5, 0.95)
                .publishPercentileHistogram()
                .register(registry)
                .recordCallable(() -> action.apply(connector));
        } catch (Exception e) {
            throw new WrappedRuntimeException(
                "Failure recording method execution (id: " + id + ")",
                e);
        }
    };

    // **************************************************
    //
    // Handlers
    //
    // **************************************************

    private UpdateControl<ManagedConnector> handleInitialization(ManagedConnector connector) {
        ManagedConnectorConditions.clearConditions(connector);

        setCondition(connector, ManagedConnectorConditions.Type.Initialization, true);
        setCondition(connector, ManagedConnectorConditions.Type.Ready, false, "Initialization");

        switch (connector.getSpec().getDeployment().getDesiredState()) {
            case DESIRED_STATE_UNASSIGNED:
            case DESIRED_STATE_DELETED: {
                setCondition(
                    connector,
                    ManagedConnectorConditions.Type.Deleting,
                    ManagedConnectorConditions.Status.True,
                    "Deleting");

                connector.getStatus().setDeployment(connector.getSpec().getDeployment());
                connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Deleting);
                connector.getStatus().getConnectorStatus().setPhase(STATE_DE_PROVISIONING);
                connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());
                break;
            }
            case DESIRED_STATE_STOPPED: {
                setCondition(
                    connector,
                    ManagedConnectorConditions.Type.Stopping,
                    ManagedConnectorConditions.Status.True,
                    "Stopping");

                connector.getStatus().setDeployment(connector.getSpec().getDeployment());
                connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Stopping);
                connector.getStatus().getConnectorStatus().setPhase(STATE_DE_PROVISIONING);
                connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());
                break;
            }
            case DESIRED_STATE_READY: {
                connector.getStatus().getConnectorStatus().setAssignedOperator(
                    new OperatorBuilder()
                        .withType(managedConnectorOperator.getSpec().getType())
                        .withId(managedConnectorOperator.getMetadata().getName())
                        .withVersion(managedConnectorOperator.getSpec().getVersion())
                        .build());

                setCondition(connector, ManagedConnectorConditions.Type.Augmentation, true);
                setCondition(connector, ManagedConnectorConditions.Type.Ready, false);

                connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Augmentation);
                connector.getStatus().getConnectorStatus().setPhase(STATE_PROVISIONING);
                connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

                break;
            }
            default:
                throw new IllegalStateException(
                    "Unknown desired state: " + connector.getSpec().getDeployment().getDesiredState());
        }

        return UpdateControl.updateStatus(connector);
    }

    private UpdateControl<ManagedConnector> handleAugmentation(ManagedConnector connector) {
        if (connector.getSpec().getDeployment().getSecret() == null) {
            LOGGER.info("Secret for deployment not defines");
            return UpdateControl.noUpdate();
        }

        Secret secret = kubernetesClient.secrets()
            .inNamespace(connector.getMetadata().getNamespace())
            .withName(connector.getSpec().getDeployment().getSecret())
            .get();

        if (secret == null) {
            boolean retry = hasCondition(
                connector,
                ManagedConnectorConditions.Type.Augmentation,
                ManagedConnectorConditions.Status.False,
                "SecretNotFound");

            if (!retry) {
                LOGGER.debug(
                    "Unable to find secret with name: {}", connector.getSpec().getDeployment().getSecret());

                setCondition(
                    connector,
                    ManagedConnectorConditions.Type.Augmentation,
                    ManagedConnectorConditions.Status.False,
                    "SecretNotFound",
                    "Unable to find secret with name: " + connector.getSpec().getDeployment().getSecret());
                setCondition(
                    connector,
                    ManagedConnectorConditions.Type.Ready,
                    ManagedConnectorConditions.Status.False,
                    "AugmentationError",
                    "AugmentationError");

                return UpdateControl.updateStatus(connector);
            } else {
                return UpdateControl.<ManagedConnector> noUpdate().rescheduleAfter(1500, TimeUnit.MILLISECONDS);
            }
        } else {
            final String connectorUow = connector.getSpec().getDeployment().getUnitOfWork();
            final String secretUow = secret.getMetadata().getLabels().get(Resources.LABEL_UOW);

            if (!Objects.equals(connectorUow, secretUow)) {
                boolean retry = hasCondition(
                    connector,
                    ManagedConnectorConditions.Type.Augmentation,
                    ManagedConnectorConditions.Status.False,
                    "SecretUoWMismatch");

                if (!retry) {
                    LOGGER.debug(
                        "Secret and Connector UoW mismatch (connector: {}, secret: {})", connectorUow, secretUow);

                    setCondition(
                        connector,
                        ManagedConnectorConditions.Type.Augmentation,
                        ManagedConnectorConditions.Status.False,
                        "SecretUoWMismatch",
                        "Secret and Connector UoW mismatch (connector: " + connectorUow + ", secret: " + secretUow + ")");
                    setCondition(
                        connector,
                        ManagedConnectorConditions.Type.Ready,
                        ManagedConnectorConditions.Status.False,
                        "AugmentationError",
                        "AugmentationError");

                    return UpdateControl.updateStatus(connector);
                } else {
                    return UpdateControl.<ManagedConnector> noUpdate().rescheduleAfter(1500, TimeUnit.MILLISECONDS);
                }
            }
        }

        ConfigMap configMap = kubernetesClient.configMaps()
            .inNamespace(connector.getMetadata().getNamespace())
            .withName(ConfigMaps.generateConnectorConfigMapId(connector.getSpec().getDeploymentId()))
            .get();

        if (configMap == null) {
            LOGGER.info(
                "Configmap not found (cluster_id: {}, namespace_id: {}, connector_id: {}, deployment_id: {}), creating a new one",
                connector.getSpec().getClusterId(),
                connector.getMetadata().getNamespace(),
                connector.getSpec().getConnectorId(),
                connector.getSpec().getDeploymentId());

            configMap = new ConfigMap();
            configMap.setMetadata(new ObjectMeta());
            configMap.getMetadata().setNamespace(connector.getMetadata().getNamespace());
            configMap.getMetadata().setName(ConfigMaps.generateConnectorConfigMapId(connector.getSpec().getDeploymentId()));

            Resources.setLabels(
                configMap,
                LABEL_CLUSTER_ID, connector.getSpec().getClusterId(),
                LABEL_CONNECTOR_ID, connector.getSpec().getConnectorId(),
                LABEL_DEPLOYMENT_ID, connector.getSpec().getDeploymentId(),
                LABEL_OPERATOR_TYPE, connector.getMetadata().getLabels().get(LABEL_OPERATOR_TYPE));

            Resources.setOwnerReferences(
                configMap,
                connector);

            this.kubernetesClient.configMaps()
                .inNamespace(configMap.getMetadata().getNamespace())
                .withName(configMap.getMetadata().getName())
                .create(configMap);
        }

        List<HasMetadata> resources;

        try {
            resources = operandController.reify(connector, secret, configMap);
        } catch (Exception e) {
            LOGGER.warn("Error reifying deployment {}", connector.getSpec().getDeploymentId(), e);

            setCondition(
                connector,
                ManagedConnectorConditions.Type.Augmentation,
                ManagedConnectorConditions.Status.False,
                "ReifyFailed",
                e instanceof WrappedRuntimeException ? e.getCause().getMessage() : e.getMessage());
            setCondition(
                connector,
                ManagedConnectorConditions.Type.Stopping,
                ManagedConnectorConditions.Status.True,
                "Stopping",
                "Stopping");

            connector.getStatus().setDeployment(connector.getSpec().getDeployment());
            connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Stopping);

            connector.getStatus().getConnectorStatus().setPhase(STATE_FAILED);
            connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

            return UpdateControl.updateStatus(connector);
        }

        for (var resource : resources) {
            if (resource.getMetadata().getLabels() == null) {
                resource.getMetadata().setLabels(new HashMap<>());
            }
            if (resource.getMetadata().getAnnotations() == null) {
                resource.getMetadata().setAnnotations(new HashMap<>());
            }

            ManagedConnectorSpec spec = connector.getSpec();
            final String rv = String.valueOf(spec.getDeployment().getDeploymentResourceVersion());

            final Map<String, String> labels = KubernetesResourceUtil.getOrCreateLabels(resource);
            labels.put(LABEL_CONNECTOR_OPERATOR, connector.getStatus().getConnectorStatus().getAssignedOperator().getId());
            labels.put(LABEL_CONNECTOR_ID, spec.getConnectorId());
            labels.put(LABEL_CONNECTOR_TYPE_ID, spec.getDeployment().getConnectorTypeId());
            labels.put(LABEL_DEPLOYMENT_ID, spec.getDeploymentId());
            labels.put(LABEL_CLUSTER_ID, spec.getClusterId());
            labels.put(LABEL_OPERATOR_TYPE, managedConnectorOperator.getSpec().getType());
            labels.put(LABEL_OPERATOR_OWNER, managedConnectorOperator.getMetadata().getName());
            labels.put(LABEL_DEPLOYMENT_RESOURCE_VERSION, rv);

            // Kubernetes recommended labels
            labels.put(LABEL_KUBERNETES_NAME, spec.getConnectorId());
            labels.put(LABEL_KUBERNETES_INSTANCE, spec.getDeploymentId());
            labels.put(LABEL_KUBERNETES_VERSION, rv);
            labels.put(LABEL_KUBERNETES_COMPONENT, Resources.COMPONENT_CONNECTOR);
            labels.put(LABEL_KUBERNETES_PART_OF, spec.getClusterId());
            labels.put(LABEL_KUBERNETES_MANAGED_BY, managedConnectorOperator.getMetadata().getName());
            labels.put(LABEL_KUBERNETES_CREATED_BY, managedConnectorOperator.getMetadata().getName());

            config.connectors().targetLabels().ifPresent(items -> {
                for (String item : items) {
                    copyLabel(item, connector, resource);
                }
            });
            config.connectors().targetAnnotations().ifPresent(items -> {
                for (String item : items) {
                    copyAnnotation(item, connector, resource);
                }
            });

            resource.getMetadata().setOwnerReferences(List.of(
                new OwnerReferenceBuilder()
                    .withApiVersion(connector.getApiVersion())
                    .withKind(connector.getKind())
                    .withName(connector.getMetadata().getName())
                    .withUid(connector.getMetadata().getUid())
                    .withAdditionalProperties(Map.of("namespace", connector.getMetadata().getNamespace()))
                    .withBlockOwnerDeletion(true)
                    .build()));

            var result = kubernetesClient.resource(resource)
                .inNamespace(connector.getMetadata().getNamespace())
                .createOrReplace();

            LOGGER.debug("Resource {}:{}:{}@{} updated/created",
                result.getApiVersion(),
                result.getKind(),
                result.getMetadata().getName(),
                result.getMetadata().getNamespace());
        }

        connector.getStatus().setDeployment(connector.getSpec().getDeployment());
        connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Monitor);
        connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

        setCondition(connector, ManagedConnectorConditions.Type.Resync, false);
        setCondition(connector, ManagedConnectorConditions.Type.Monitor, true);
        setCondition(connector, ManagedConnectorConditions.Type.Ready, true);
        setCondition(connector, ManagedConnectorConditions.Type.Augmentation, true);

        return UpdateControl.updateStatus(connector);
    }

    private UpdateControl<ManagedConnector> handleMonitor(ManagedConnector connector) {
        operandController.status(connector);

        //
        // Search for newly installed ManagedOperators
        //
        final List<Operator> operators = fleetShard.lookupOperators();
        final Operator assignedOperator = connector.getStatus().getConnectorStatus().getAssignedOperator();
        final Operator availableOperator = connector.getStatus().getConnectorStatus().getAvailableOperator();
        final Optional<Operator> selected = available(connector.getSpec().getOperatorSelector(), operators);

        if (selected.isPresent()) {
            Operator selectedInstance = selected.get();

            // if the selected operator does match the operator preciously selected
            if (!Objects.equals(selectedInstance, availableOperator) && !Objects.equals(selectedInstance, assignedOperator)) {
                // and it is not the currently assigned one
                LOGGER.info("deployment (upd): {} -> from:{}, to: {}",
                    connector.getSpec().getDeployment(),
                    assignedOperator,
                    selectedInstance);

                // then we can signal that an upgrade is possible
                connector.getStatus().getConnectorStatus().setAvailableOperator(selectedInstance);
            }
        } else {
            connector.getStatus().getConnectorStatus().setAvailableOperator(new Operator());
        }

        return UpdateControl.updateStatus(connector);
    }

    private UpdateControl<ManagedConnector> handleDeleting(ManagedConnector connector) {
        if (operandController.delete(connector)) {
            connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Deleted);
            connector.getStatus().getConnectorStatus().setPhase(STATE_DELETED);
            connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

            setCondition(
                connector,
                ManagedConnectorConditions.Type.Deleting,
                ManagedConnectorConditions.Status.False,
                "Deleted");
            setCondition(
                connector,
                ManagedConnectorConditions.Type.Deleted,
                ManagedConnectorConditions.Status.True,
                "Deleted");

            LOGGER.info("Connector {} deleted, move to phase: {}",
                connector.getMetadata().getName(),
                connector.getStatus().getPhase());

            return UpdateControl.updateStatus(connector);
        }

        return UpdateControl.<ManagedConnector> noUpdate().rescheduleAfter(1500, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private UpdateControl<ManagedConnector> handleDeleted(ManagedConnector connector) {
        return UpdateControl.noUpdate();
    }

    private UpdateControl<ManagedConnector> handleStopping(ManagedConnector connector) {
        if (operandController.stop(connector)) {
            connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Stopped);
            connector.getStatus().getConnectorStatus().setPhase(STATE_STOPPED);
            connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

            setCondition(
                connector,
                ManagedConnectorConditions.Type.Stopping,
                ManagedConnectorConditions.Status.False,
                "Stopped");
            setCondition(
                connector,
                ManagedConnectorConditions.Type.Stop,
                ManagedConnectorConditions.Status.True,
                "Stopped");

            LOGGER.info("Connector {} stopped, move to phase: {}",
                connector.getMetadata().getName(),
                connector.getStatus().getPhase());

            return UpdateControl.updateStatus(connector);
        }

        return UpdateControl.<ManagedConnector> noUpdate().rescheduleAfter(1500, TimeUnit.MILLISECONDS);
    }

    private UpdateControl<ManagedConnector> handleStopped(ManagedConnector connector) {
        // if the reify process fails, the safest option is to stop the connector and
        // report the failure.
        if (isReifyFailed(connector)) {
            connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Error);
            connector.getStatus().getConnectorStatus().setPhase(STATE_FAILED);
            connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

            return UpdateControl.updateStatus(connector);
        }

        return UpdateControl.noUpdate();
    }

    @SuppressWarnings({ "PMD.UnusedFormalParameter" })
    private UpdateControl<ManagedConnector> handleError(ManagedConnector connector) {
        return UpdateControl.noUpdate();
    }

    private UpdateControl<ManagedConnector> handleTransferring(ManagedConnector connector) {
        if (operandController.stop(connector)) {
            connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Transferred);
            connector.getStatus().getConnectorStatus().setPhase(STATE_STOPPED);
            connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());

            LOGGER.info("Connector {} transferred, move to phase: {}",
                connector.getMetadata().getName(),
                connector.getStatus().getPhase());

            return UpdateControl.updateStatus(connector);
        }

        return UpdateControl.<ManagedConnector> noUpdate().rescheduleAfter(1500, TimeUnit.MILLISECONDS);
    }

    private UpdateControl<ManagedConnector> handleTransferred(ManagedConnector connector) {
        LOGGER.info("Connector {} complete, it can now be handled by another operator.",
            connector.getMetadata().getName());

        connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Initialization);
        connector.getStatus().getConnectorStatus().setAssignedOperator(null);
        connector.getStatus().getConnectorStatus().setAvailableOperator(null);

        return UpdateControl.updateStatus(connector);
    }

    // **************************************************
    //
    // Helpers
    //
    // **************************************************

    private UpdateControl<ManagedConnector> validate(
        ManagedConnector connector,
        Function<ManagedConnector, UpdateControl<ManagedConnector>> okAction) {

        if (!Objects.equals(connector.getSpec().getDeployment(), connector.getStatus().getDeployment())) {
            JsonNode specNode = Serialization.jsonMapper().valueToTree(connector.getSpec().getDeployment());
            JsonNode statusNode = Serialization.jsonMapper().valueToTree(connector.getStatus().getDeployment());
            JsonNode diff = JsonDiff.asJson(statusNode, specNode);

            if (diff.isArray() && diff.size() == 1 && diff.get(0).at("/path").asText().equals("/unitOfWork")) {
                final Long specResourceVersion = getDeploymentResourceVersion(connector.getSpec());
                final Long statResourceVersion = getDeploymentResourceVersion(connector.getStatus());

                if (specResourceVersion != null && specResourceVersion.equals(statResourceVersion)) {
                    //
                    // In case of re-sink, the diff should looks like:
                    //
                    //   [{
                    //      "op": "replace",
                    //      "path": "/unitOfWork",
                    //      "value": "61ba142d2a83cb1d0cf3dff2"
                    //   }]
                    //
                    // if the only changed element is unitOfWork then this reconciliation loop was triggered
                    // by a re-sink process: to be on the safe side, the Augmentation step is re-executed
                    // as if it were a new connector so operand's CRs are re-generated.
                    //

                    setCondition(
                        connector,
                        ManagedConnectorConditions.Type.Resync,
                        ManagedConnectorConditions.Status.True,
                        "Resync");
                }
            }

            if (isResync(connector)) {
                switch (connector.getSpec().getDeployment().getDesiredState()) {
                    case DESIRED_STATE_STOPPED:
                        setCondition(
                            connector,
                            ManagedConnectorConditions.Type.Stopping,
                            ManagedConnectorConditions.Status.True,
                            "Stopping");

                        connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Stopping);
                        connector.getStatus().setDeployment(connector.getSpec().getDeployment());
                        break;
                    case DESIRED_STATE_UNASSIGNED:
                    case DESIRED_STATE_DELETED:
                        setCondition(
                            connector,
                            ManagedConnectorConditions.Type.Deleting,
                            ManagedConnectorConditions.Status.True,
                            "Deleting");

                        connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Deleting);
                        connector.getStatus().setDeployment(connector.getSpec().getDeployment());
                        break;
                    case DESIRED_STATE_READY:
                        setCondition(connector, ManagedConnectorConditions.Type.Augmentation, true, "Resync");
                        setCondition(connector, ManagedConnectorConditions.Type.Ready, false, "Resync");

                        //
                        // If the managed connector is performing a resync, then we don't change the status
                        // of the connector on the control plane as this is a technical phase. We can then
                        // jump straight to the Augmentation phase
                        //
                        connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Augmentation);
                        break;
                    default:
                        throw new IllegalStateException(
                            "Unknown desired state: " + connector.getSpec().getDeployment().getDesiredState());
                }
            } else {
                connector.getStatus().setPhase(ManagedConnectorStatus.PhaseType.Initialization);
                connector.getStatus().getConnectorStatus().setPhase(STATE_PROVISIONING);
                connector.getStatus().getConnectorStatus().setConditions(Collections.emptyList());
            }

            LOGGER.info("Drift detected on connector deployment {}: {} -> move to phase: {}",
                connector.getSpec().getDeploymentId(),
                diff,
                connector.getStatus().getPhase());

            return UpdateControl.updateStatus(connector);
        }

        return okAction.apply(connector);
    }

    private boolean selected(ManagedConnector connector) {
        return connector.getSpec().getOperatorSelector() != null
            && Objects.equals(
                managedConnectorOperator.getMetadata().getName(),
                connector.getSpec().getOperatorSelector().getId());
    }

    private boolean assigned(ManagedConnector connector) {
        return connector.getStatus().getConnectorStatus().getAssignedOperator() != null
            && Objects.equals(
                managedConnectorOperator.getMetadata().getName(),
                connector.getStatus().getConnectorStatus().getAssignedOperator().getId());
    }

    private Long getDeploymentResourceVersion(DeploymentSpecAware spec) {
        if (spec == null) {
            return null;
        }
        if (spec.getDeployment() == null) {
            return null;
        }

        return spec.getDeployment().getDeploymentResourceVersion();
    }

    private boolean isResync(ManagedConnector connector) {
        return hasCondition(
            connector,
            ManagedConnectorConditions.Type.Resync,
            ManagedConnectorConditions.Status.True,
            "Resync");
    }

    private boolean isReifyFailed(ManagedConnector connector) {
        return hasCondition(
            connector,
            ManagedConnectorConditions.Type.Augmentation,
            ManagedConnectorConditions.Status.False,
            "ReifyFailed");
    }
}

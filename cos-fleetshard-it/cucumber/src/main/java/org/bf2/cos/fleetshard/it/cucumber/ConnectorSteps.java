package org.bf2.cos.fleetshard.it.cucumber;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import javax.inject.Inject;

import org.bf2.cos.fleetshard.api.DeploymentSpecBuilder;
import org.bf2.cos.fleetshard.api.KafkaSpecBuilder;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.api.ManagedConnectorBuilder;
import org.bf2.cos.fleetshard.api.ManagedConnectorSpecBuilder;
import org.bf2.cos.fleetshard.api.ManagedConnectorStatus;
import org.bf2.cos.fleetshard.api.Operator;
import org.bf2.cos.fleetshard.api.OperatorSelectorBuilder;
import org.bf2.cos.fleetshard.support.json.JacksonUtil;
import org.bf2.cos.fleetshard.support.resources.ConfigMaps;
import org.bf2.cos.fleetshard.support.resources.Connectors;
import org.bf2.cos.fleetshard.support.resources.Resources;
import org.bf2.cos.fleetshard.support.resources.Secrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.fabric8.kubernetes.client.utils.Serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bf2.cos.fleetshard.it.cucumber.ConnectorContext.COS_KAFKA_BOOTSTRAP;
import static org.bf2.cos.fleetshard.it.cucumber.ConnectorContext.COS_KAFKA_CLIENT_ID;
import static org.bf2.cos.fleetshard.it.cucumber.ConnectorContext.COS_KAFKA_CLIENT_SECRET;
import static org.bf2.cos.fleetshard.it.cucumber.support.StepsSupport.PARSER;
import static org.bf2.cos.fleetshard.support.resources.Resources.uid;

public class ConnectorSteps {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorSteps.class);
    private static final String SCHEMA_REGISTRY_URL = "https://bu98.serviceregistry.rhcloud.com/t/51eba005-daft-punk-afe1-b2178bcb523d/apis/registry/v2";
    private static final String SCHEMA_REGISTRY_ID = "9bsv0s0k8lng031se9q0";
    private static final String KAFKA_URL = "kafka.acme.com:443";

    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    Awaiter awaiter;
    @Inject
    ConnectorContext ctx;

    @Before
    public void init() {
        clear();

        kubernetesClient.resources(ManagedConnector.class)
            .inNamespace(ctx.connectorsNamespace())
            .watch(new Watcher<>() {
                @Override
                public void eventReceived(Action action, ManagedConnector connector) {
                    switch (action) {
                        case ADDED:
                        case MODIFIED:
                            ctx.history().add(connector);
                            break;
                        default:
                            break;
                    }
                }

                @Override
                public void onClose(WatcherException e) {
                }
            });
    }

    @After
    public void cleanup(Scenario scenario) {
        if (scenario.isFailed()) {
            dump(scenario);
        }

        clear();
    }

    void dump(Scenario scenario) {
        scenario.log("============================================");

        if (ctx.connector() != null) {
            ManagedConnector connector = connector();

            if (connector != null) {
                scenario.log("Connector:\n" + JacksonUtil.asPrettyPrintedYaml(connector));
            }
        }

        if (ctx.secret() != null) {
            Secret secret = kubernetesClient.resources(Secret.class)
                .inNamespace(ctx.secret().getMetadata().getNamespace())
                .withName(ctx.secret().getMetadata().getName())
                .get();

            if (secret != null) {
                scenario.log("Secret:\n" + JacksonUtil.asPrettyPrintedYaml(secret));
            }
        }

        ConfigMap configMap = getConfigMapFilter().get();
        if (configMap != null) {
            scenario.log("ConfigMap:\n" + JacksonUtil.asPrettyPrintedYaml(configMap));
        }

        scenario.log("============================================");
    }

    void clear() {
        if (ctx.connector() != null) {
            LOGGER.info("Deleting connector: {} in namespace {}",
                ctx.connector().getMetadata().getName(),
                ctx.connector().getMetadata().getNamespace());

            connectorResource().delete();
        }

        if (ctx.secret() != null) {
            LOGGER.info("Deleting secret: {} in namespace {}",
                ctx.secret().getMetadata().getName(),
                ctx.secret().getMetadata().getNamespace());

            kubernetesClient.secrets()
                .inNamespace(ctx.secret().getMetadata().getNamespace())
                .withName(ctx.secret().getMetadata().getName())
                .delete();
        }

        ctx.clear();
    }

    @Given("^a Connector with:$")
    public void a_connector(Map<String, String> options) {
        Map<String, String> entry = ctx.resolvePlaceholders(options);

        final Long drv = Long.parseLong(entry.getOrDefault(ConnectorContext.COS_DEPLOYMENT_RESOURCE_VERSION, "1"));
        final Long crv = Long.parseLong(entry.getOrDefault(ConnectorContext.COS_CONNECTOR_RESOURCE_VERSION, "1"));
        final String connectorId = entry.getOrDefault(ConnectorContext.COS_CONNECTOR_ID, uid());
        final String deploymentId = entry.getOrDefault(ConnectorContext.COS_DEPLOYMENT_ID, uid());
        final String clusterId = ctx.clusterId();

        var connector = new ManagedConnectorBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .addToLabels(Resources.LABEL_CLUSTER_ID, clusterId)
                .addToLabels(Resources.LABEL_CONNECTOR_ID, connectorId)
                .addToLabels(Resources.LABEL_DEPLOYMENT_ID, deploymentId)
                .addToLabels(Resources.LABEL_OPERATOR_TYPE, entry.get(ConnectorContext.OPERATOR_TYPE))
                .withName(Connectors.generateConnectorId(deploymentId))
                .build())
            .withSpec(new ManagedConnectorSpecBuilder()
                .withClusterId(clusterId)
                .withConnectorId(connectorId)
                .withDeploymentId(deploymentId)
                .withDeployment(new DeploymentSpecBuilder()
                    .withConnectorResourceVersion(crv)
                    .withConnectorTypeId(entry.get(ConnectorContext.CONNECTOR_TYPE_ID))
                    .withDeploymentResourceVersion(drv)
                    .withNewSchemaRegistry(SCHEMA_REGISTRY_ID, SCHEMA_REGISTRY_URL)
                    .withKafka(
                        new KafkaSpecBuilder().withUrl(entry.getOrDefault(COS_KAFKA_BOOTSTRAP, KAFKA_URL))
                            .build())
                    .withDesiredState(entry.get(ConnectorContext.DESIRED_STATE))
                    .withSecret(Connectors.generateConnectorId(deploymentId) + "-" + drv)
                    .build())
                .withOperatorSelector(new OperatorSelectorBuilder()
                    .withId(entry.get(ConnectorContext.OPERATOR_ID))
                    .withType(entry.get(ConnectorContext.OPERATOR_TYPE))
                    .withVersion(entry.get(ConnectorContext.OPERATOR_VERSION))
                    .build())
                .build())
            .build();

        var secret = new SecretBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .addToLabels(Resources.LABEL_OPERATOR_TYPE, entry.get(ConnectorContext.OPERATOR_TYPE))
                .withName(connector.getMetadata().getName()
                    + "-"
                    + connector.getSpec().getDeployment().getDeploymentResourceVersion())
                .build())
            .withData(new HashMap<>())
            .addToData(
                Secrets.SECRET_ENTRY_SERVICE_ACCOUNT,
                Secrets.toBase64(Serialization.asJson(
                    Serialization.jsonMapper().createObjectNode()
                        .put("client_id",
                            entry.getOrDefault(
                                COS_KAFKA_CLIENT_ID,
                                ctx.getPlaceholderValue(COS_KAFKA_CLIENT_ID)))
                        .put("client_secret",
                            Secrets.toBase64(entry.getOrDefault(
                                COS_KAFKA_CLIENT_SECRET,
                                uid()))))))
            .build();

        ctx.connector(connector);
        ctx.secret(secret);
    }

    @And("set connector annotation {string} to {string}")
    public void set_connector_annotation(String key, String val) {
        KubernetesResourceUtil.getOrCreateAnnotations(ctx.connector()).put(key, val);
    }

    @And("set connector label {string} to {string}")
    public void set_connector_label(String key, String val) {
        KubernetesResourceUtil.getOrCreateLabels(ctx.connector()).put(key, val);
    }

    @And("with connector spec:")
    public void with_connector_spec(Map<String, String> entry) {
        Secrets.set(ctx.secret(), Secrets.SECRET_ENTRY_CONNECTOR, entry);
    }

    @And("with shard meta:")
    public void with_shard_meta(String payload) {
        Secrets.set(ctx.secret(), Secrets.SECRET_ENTRY_META, payload);
    }

    @And("with secret data:")
    public void with_secret_data(Map<String, String> content) {
        content.forEach((k, v) -> Secrets.set(ctx.secret(), k, v));
    }

    @And("with secret key {string} and value {string}")
    public void with_secret(String key, String value) {
        Secrets.set(ctx.secret(), key, value);
    }

    @When("^deploy$")
    public void deploy() {
        final String uow = uid();
        deploy_connector_with_uow(uow);
        deploy_secret_with_uow(uow);
    }

    @When("^deploy with secret data:")
    public void deployWithSecretData(Map<String, String> content) {
        final String uow = uid();

        deploy_connector_with_uow(uow);
        deploy_secret_with_uow(uow, content);
    }

    @When("^deploy secret$")
    public void deploy_secret() {
        deploy_secret_with_uow(uid());
    }

    @When("^deploy connector$")
    public void deploy_connector() {
        deploy_connector_with_uow(uid());
    }

    @When("^set configmap to:")
    public void change_configmap(Map<String, String> contents) {
        var sb = new StringBuilder();
        contents.forEach((k, v) -> {
            sb.append("\t");
            sb.append(k);
            sb.append("=");
            sb.append(v);
            sb.append("\n");
        });

        getConfigMapFilter().accept(configMap -> {
            configMap.setData(Map.of("override.properties", sb.toString()));
        });
    }

    @Then("the connector exists")
    public void connector_is_created() {
        until(() -> connector() != null);
    }

    @Then("the connector secret exists")
    public void secret_is_created() {
        until(() -> {
            var res = kubernetesClient.resources(Secret.class)
                .inNamespace(ctx.secret().getMetadata().getNamespace())
                .withName(ctx.secret().getMetadata().getName())
                .get();

            return res != null;
        });
    }

    @Then("the connector configmap exists with labels:")
    public void configmap_is_created(Map<String, String> labels) {

        until(() -> {
            var res = getConfigMapFilter().get();

            if (res == null) {
                return false;
            } else {
                if (labels == null) {
                    return true;
                }

                return labels.entrySet().stream().allMatch(
                    e -> e.getValue().equals(res.getMetadata().getLabels().get(e.getKey())));
            }
        });
    }

    @Then("the connector configmap exists with:")
    public void configmap_exists_with(Map<String, String> contents) {
        until(() -> {
            var res = getConfigMapFilter().get();

            if (res == null) {
                return false;
            } else {
                if (contents == null) {
                    return true;
                }

                return contents.entrySet().stream().allMatch(e -> e.getValue().equals(res.getData().get(e.getKey())));
            }
        });
    }

    @Then("the connector configmap does not exists")
    public void configmap_does_not_exists() {
        until(() -> {
            var res = getConfigMapFilter().get();

            return res == null;
        });
    }

    @And("the connector secret does not exists")
    public void secret_does_not_exists() {
        until(() -> {
            var res = kubernetesClient.resources(Secret.class)
                .inNamespace(ctx.secret().getMetadata().getNamespace())
                .withName(ctx.secret().getMetadata().getName())
                .get();

            return res == null;
        });
    }

    @When("the connector desired status is set to {string}")
    public void connector_desired_state_set_to(String status) {
        ctx.connector(
            connectorResource().accept(c -> c.getSpec().getDeployment().setDesiredState(status)));
    }

    @Then("the connector is in phase {string}")
    public void connector_is_in_phase(String phase) {
        untilConnector(c -> Objects.equals(
            ManagedConnectorStatus.PhaseType.valueOf(phase),
            c.getStatus().getPhase()));
    }

    @And("the connector operand status is in phase {string}")
    public void connector_operand_status_is_in_phase(String phase) {
        untilConnector(c -> Objects.equals(phase, c.getStatus().getConnectorStatus().getPhase()));
    }

    @Then("the deployment is in phase {string}")
    public void deployment_is_in_phase(String phase) {
        untilConnector(c -> Objects.equals(phase, c.getStatus().getConnectorStatus().getPhase()));
    }

    @When("the connector path {string} is set to json:")
    public void connector_pointer(String path, String payload) {
        ctx.connector(
            connectorResource().edit(res -> {
                JsonNode replacement = Serialization.unmarshal(payload, JsonNode.class);
                JsonNode replaced = PARSER.parse(Serialization.asJson(res)).set(path, replacement).json();

                return JacksonUtil.treeToValue(replaced, ManagedConnector.class);
            }));
    }

    @When("the connector path {string} is set to {string}")
    public void connector_pointer_set_to_string(String path, String value) {
        ctx.connector(
            connectorResource().edit(res -> {
                JsonNode replaced = PARSER.parse(Serialization.asJson(res)).set(path, value).json();

                return JacksonUtil.treeToValue(replaced, ManagedConnector.class);
            }));
    }

    @When("the connector path {string} is set to {int}")
    public void connector_pointer_set_to_int(String path, int value) {
        ctx.connector(
            connectorResource().edit(res -> {
                JsonNode replaced = PARSER.parse(Serialization.asJson(res)).set(path, value).json();

                return JacksonUtil.treeToValue(replaced, ManagedConnector.class);
            }));
    }

    @When("the connector secret has labels:")
    public void connector_secret_has_labels(Map<String, String> entry) {
        ctx.secret(
            kubernetesClient.resources(Secret.class)
                .inNamespace(ctx.secret().getMetadata().getNamespace())
                .withName(ctx.secret().getMetadata().getName())
                .edit(res -> {
                    entry.forEach((k, v) -> Resources.setLabel(res, k, ctx.resolvePlaceholders(v)));
                    return res;
                }));
    }

    @Then("the connector's assignedOperator exists with:")
    public void connector_assignedOperator_exists_with(Map<String, String> expected) {
        untilConnector(c -> {
            Operator op = c.getStatus().getConnectorStatus().getAssignedOperator();
            return expected.get("operator.id").equals(op.getId())
                && expected.get("operator.type").equals(op.getType())
                && expected.get("operator.version").equals(op.getVersion());
        });
    }

    @And("the connector's assignedOperator does not exist")
    public void connector_assignedOperator_not_exists() {
        untilConnector(c -> {
            Operator op = c.getStatus().getConnectorStatus().getAssignedOperator();
            return op != null
                && op.getId() == null
                && op.getType() == null
                && op.getVersion() == null;
        });
    }

    @Then("the connector's availableOperator exists with:")
    public void connector_availableOperator_exists_with(Map<String, String> expected) {
        Operator op = connector().getStatus().getConnectorStatus().getAvailableOperator();
        assertThat(op).isNotNull();
        assertThat(op.getId()).isEqualTo(expected.get("operator.id"));
        assertThat(op.getType()).isEqualTo(expected.get("operator.type"));
        assertThat(op.getVersion()).isEqualTo(expected.get("operator.version"));
    }

    @Then("the connector's availableOperator does not exist")
    public void connector_availableOperator_not_exists() {
        Operator op = connector().getStatus().getConnectorStatus().getAvailableOperator();
        assertThat(op).isNotNull();
        assertThat(op.getId()).isEqualTo(null);
        assertThat(op.getType()).isEqualTo(null);
        assertThat(op.getVersion()).isEqualTo(null);
    }

    @Then("the connector operatorSelector id is {string}")
    public void connector_operator_selector_id(String selectorId) {
        untilConnector(c -> selectorId.equals(c.getSpec().getOperatorSelector().getId()));
    }

    @Then("the connector has conditions:")
    public void connector_has_conditions(DataTable table) {
        final List<Map<String, String>> rows = table.asMaps(String.class, String.class);
        final ManagedConnector connector = connector();

        assertThat(rows).allMatch(row -> hasCondition(connector, row));
    }

    @Then("wait till the connector has entry in history with conditions:")
    public void connector_has_conditions_in_history(DataTable table) {
        final List<Map<String, String>> rows = table.asMaps(String.class, String.class);

        awaiter.until(() -> ctx.history().stream().anyMatch(
            connector -> rows.stream().allMatch(row -> hasCondition(connector, row))));
    }

    @Then("wait till the connector has entry in history with phase {string} and conditions:")
    public void connector_has_conditions_in_history(String phase, DataTable table) {
        final List<Map<String, String>> rows = table.asMaps(String.class, String.class);

        awaiter.until(() -> ctx.history().stream()
            .filter(c -> Objects.equals(
                c.getMetadata().getName(),
                ctx.connector().getMetadata().getName()))
            .filter(c -> Objects.equals(
                ManagedConnectorStatus.PhaseType.valueOf(phase),
                c.getStatus().getPhase()))
            .filter(c -> Objects.equals(
                c.getMetadata().getLabels().get(Resources.LABEL_UOW),
                ctx.connector().getMetadata().getLabels().get(Resources.LABEL_UOW)))
            .anyMatch(c -> rows.stream().allMatch(row -> hasCondition(c, row))));
    }

    private boolean hasCondition(ManagedConnector connector, Map<String, String> row) {
        return connector.getStatus().getConditions().stream().anyMatch(c -> Objects.equals(c.getType(), row.get("type"))
            && Objects.equals(c.getStatus(), row.get("status"))
            && Objects.equals(c.getReason(), row.get("reason"))
            && (row.get("message") == null || Objects.equals(c.getMessage(), row.get("message"))));
    }

    private void until(Callable<Boolean> conditionEvaluator) {
        awaiter.until(conditionEvaluator);
    }

    private Resource<ManagedConnector> connectorResource() {
        return kubernetesClient.resources(ManagedConnector.class)
            .inNamespace(ctx.connector().getMetadata().getNamespace())
            .withName(ctx.connector().getMetadata().getName());
    }

    private ManagedConnector connector() {
        return connectorResource().get();
    }

    private void untilConnector(Predicate<ManagedConnector> predicate) {
        awaiter.until(() -> {
            var res = connector();

            if (res == null) {
                return false;
            }

            return predicate.test(res);
        });
    }

    public void deploy_secret_with_uow(String uow) {
        deploy_secret_with_uow(uow, null);
    }

    public void deploy_secret_with_uow(String uow, Map<String, String> content) {
        Secret res = kubernetesClient.resources(Secret.class)
            .inNamespace(ctx.connectorsNamespace())
            .withName(ctx.secret().getMetadata().getName())
            .get();

        if (res != null) {
            ctx.secret(res);
        }

        if (content != null) {
            with_secret_data(content);
        }

        Resources.setAnnotation(
            ctx.secret(),
            Resources.ANNOTATION_UPDATED_TIMESTAMP,
            ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        Resources.setLabel(
            ctx.secret(),
            Resources.LABEL_UOW,
            uow);

        ctx.secret(
            kubernetesClient.resources(Secret.class)
                .inNamespace(ctx.connectorsNamespace())
                .createOrReplace(ctx.secret()));
    }

    public void deploy_connector_with_uow(String uow) {
        ManagedConnector res = kubernetesClient.resources(ManagedConnector.class)
            .inNamespace(ctx.connectorsNamespace())
            .withName(ctx.connector().getMetadata().getName())
            .get();

        if (res != null) {
            ctx.connector(res);
        }

        Resources.setAnnotation(
            ctx.connector(),
            Resources.ANNOTATION_UPDATED_TIMESTAMP,
            ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        Resources.setLabel(
            ctx.connector(),
            Resources.LABEL_UOW,
            uow);

        ctx.connector().getSpec().getDeployment().setUnitOfWork(uow);

        ctx.connector(
            kubernetesClient.resources(ManagedConnector.class)
                .inNamespace(ctx.connectorsNamespace())
                .createOrReplace(ctx.connector()));
    }

    private Resource<ConfigMap> getConfigMapFilter() {
        return kubernetesClient.resources(ConfigMap.class)
            .inNamespace(ctx.connector().getMetadata().getNamespace())
            .withName(ConfigMaps.generateConnectorConfigMapId(ctx.connector().getSpec().getDeploymentId()));
    }

}

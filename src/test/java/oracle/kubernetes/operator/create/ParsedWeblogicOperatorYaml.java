// Copyright 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
package oracle.kubernetes.operator.create;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.kubernetes.client.models.ExtensionsV1beta1Deployment;
import io.kubernetes.client.models.V1ConfigMap;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1Service;
import io.kubernetes.client.models.V1ServiceSpec;
import io.kubernetes.client.models.V1ServicePort;

import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Parses a generated weblogic-operator.yaml file into a set of typed k8s java objects
 */
public class ParsedWeblogicOperatorYaml {

  public V1ConfigMap operatorConfigMap;
  public V1Secret operatorSecrets;
  public ExtensionsV1beta1Deployment operatorDeployment;
  public V1Service externalOperatorService;
  public V1Service internalOperatorService;

  public ParsedWeblogicOperatorYaml(Path yamlPath, CreateOperatorInputs inputs) throws Exception {
    ParsedKubernetesYaml parsed = new ParsedKubernetesYaml(yamlPath);
    operatorConfigMap = parsed.getConfigMap("operator-config-map");
    operatorSecrets = parsed.getSecret("operator-secrets");
    operatorDeployment = parsed.getDeployment("weblogic-operator");
    internalOperatorService = parsed.getService("internal-weblogic-operator-service");
    externalOperatorService = parsed.getService("external-weblogic-operator-service");
  }

  private static final String API_V1 = "v1";
  private static final String SERVICE_ACCOUNT = "serviceaccount";
  private static final String TARGET_NAMESPACES = "targetNamespaces";
  private static final String EXTERNAL_OPERATOR_CERT = "externalOperatorCert";
  private static final String INTERNAL_OPERATOR_CERT = "internalOperatorCert";
  private static final String EXTERNAL_OPERATOR_KEY = "externalOperatorKey";
  private static final String INTERNAL_OPERATOR_KEY = "internalOperatorKey";

  public void assertThatOperatorConfigMapIsCorrect(CreateOperatorInputs inputs, String externalOperatorCertWant) throws Exception {
    /* Expected yaml:
      apiVersion: v1
      kind: ConfigMap
      metadata:
        name: operator-config-map
        namespace: inputs.getNamespace()
      data:
        serviceaccount: inputs.getServiceAccount()
        targetNamespaces: inputs.getTargetNamespaces()
        externalOperatorCert: TBD
        internalOperatorCert: TBD
    */
    V1ConfigMap configMap = operatorConfigMap;
    assertThat(configMap, notNullValue());
    assertThat(configMap.getKind(), equalTo("ConfigMap"));
    assertThat(configMap.getApiVersion(), equalTo(API_V1));
    assertThat_MetadataMatches(configMap.getMetadata(), "operator-config-map", inputs.getNamespace());
    Map<String,String> data = configMap.getData();
    assertThat(data, notNullValue());
    assertThat(data.keySet(), containsInAnyOrder(SERVICE_ACCOUNT, TARGET_NAMESPACES, EXTERNAL_OPERATOR_CERT, INTERNAL_OPERATOR_CERT));
    assertThat(data, hasEntry(SERVICE_ACCOUNT, inputs.getServiceAccount()));
    assertThat(data, hasEntry(TARGET_NAMESPACES, inputs.getTargetNamespaces()));
    assertThat(data, hasEntry(EXTERNAL_OPERATOR_CERT, externalOperatorCertWant)); // TBD - how do we handle generated?
    assertThat(data.get(INTERNAL_OPERATOR_CERT), notNullValue()); // TBD - generated - if we mock the generation, then we might be able to test for a specific string
  }

  public void assertThatOperatorSecretsAreCorrect(CreateOperatorInputs inputs, String externalOperatorKeyWant) throws Exception {
    /* Expected yaml:
      apiVersion: v1
      kind: Secret
      metadata:
        name: operator-secrets
        namespace: inputs.getNamespace()
      type: Opaque
      data:
        externalOperatorKey: TBD
        internalOperatorKey: TBD
    */
    V1Secret secret = operatorSecrets;
    assertThat(secret, notNullValue());
    assertThat(secret.getKind(), equalTo("Secret"));
    assertThat(secret.getApiVersion(), equalTo(API_V1));
    assertThat_MetadataMatches(secret.getMetadata(), "operator-secrets", inputs.getNamespace());
    Map<String,byte[]> data = secret.getData();
    assertThat(data, notNullValue());
    assertThat(data.keySet(), containsInAnyOrder(EXTERNAL_OPERATOR_KEY, INTERNAL_OPERATOR_KEY));
    byte[] externalOperatorKeyBytes = data.get(EXTERNAL_OPERATOR_KEY);
    assertThat(externalOperatorKeyBytes, notNullValue());
    String externalOperatorKeyHave = Base64.encodeBase64String(externalOperatorKeyBytes);
    assertThat(externalOperatorKeyWant, equalTo(externalOperatorKeyHave)); // TBD - how do we test generated?
    assertThat(data.get(INTERNAL_OPERATOR_KEY), notNullValue()); // TBD - generated - if we mock the generation, then we might be able to test for a specific string
  }

  public void assertThatExternalOperatorServiceIsCorrect(CreateOperatorInputs inputs, boolean debuggingEnabled, boolean externalRestEnabled) throws Exception {
    /* Expected yaml:
      // if debugging enabled or external rest enabled:
      apiVersion: v1
      kind: Service
      metadata:
        name: external-weblogic-operator-service
        namespace: inputs.getNamespace()
      spec:
        type: NodePort
        selector:
          app: weblogic-operator
        ports:
          // if external rest enabled:
          - port: 8081
            nodePort: inputs.getExternalRestHttpsPort()
            name: rest-https
          // if debugging enabled:
          - port: inputs.getInternalDebugHttpPort()
            nodePort: inputs.getExternalDebugHttpPort()
            name: debug
    */
    V1Service service = externalOperatorService;
    if (!debuggingEnabled && !externalRestEnabled) {
      assertThat(service, nullValue());
      return;
    }
    List<V1ServicePort> ports =
      assertThatServiceExistsThenReturnPorts(service, "external-weblogic-operator-service", inputs.getNamespace(), "NodePort");
    int nextPortIndex = 0;
    if (externalRestEnabled) {
      assertThatNodePortMatches(ports, nextPortIndex++, "rest-https", "8081", inputs.getExternalRestHttpsPort());
    }
    if (debuggingEnabled) {
      assertThatNodePortMatches(ports, nextPortIndex++, "debug", inputs.getInternalDebugHttpPort(), inputs.getExternalDebugHttpPort());
    }
    assertThat(ports.size(), is(nextPortIndex));
  }

  public void assertThatNodePortMatches(List<V1ServicePort> ports, int nextPortIndex, String name, String port, String nodePort) {
    assertThat(ports.size(), greaterThan(nextPortIndex));
    V1ServicePort p = ports.get(nextPortIndex);
    assertThat(p, notNullValue());
    assertThat(p.getName(), equalTo(name));
    // k8s port numbers are ints, while our testing inputs class uses strings so that it can test non-int values.
    // therefore convert the k8s port numbers to strings so that we can compare them to the ones in the inputs class
    assertThat("" + p.getPort(), equalTo(port));
    assertThat("" + p.getNodePort(), equalTo(nodePort));
  }

  public List<V1ServicePort> assertThatServiceExistsThenReturnPorts(V1Service service, String name, String namespace, String type) {
    assertThat(service, notNullValue());
    assertThat(service.getKind(), equalTo("Service"));
    assertThat(service.getApiVersion(), equalTo(API_V1));
    assertThat_MetadataMatches(service.getMetadata(), name, namespace);
    V1ServiceSpec spec = service.getSpec();
    assertThat(spec, notNullValue());
    assertThat(spec.getType(), equalTo(type));
    Map<String,String> selector = spec.getSelector();
    assertThat(selector, notNullValue());
    assertThat(selector, hasEntry("app", "weblogic-operator")); // TBD - take as input args?
    List<V1ServicePort> ports = spec.getPorts();
    assertThat(ports, notNullValue());
    return ports;
  }

  public void assertThat_MetadataMatches(V1ObjectMeta metadata, String name, String namespace) {
    assertThat(metadata, notNullValue());
    assertThat(metadata.getName(), equalTo(name));
    assertThat(metadata.getNamespace(), equalTo(namespace));
  }
}

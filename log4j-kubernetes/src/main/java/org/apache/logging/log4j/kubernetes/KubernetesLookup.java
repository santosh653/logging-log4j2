/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.kubernetes;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.lookup.AbstractLookup;
import org.apache.logging.log4j.core.lookup.StrLookup;
import org.apache.logging.log4j.plugins.Plugin;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.LoaderUtil;
import org.apache.logging.log4j.util.Strings;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;


/**
 * Retrieve various Kubernetes attributes. Supported keys are:
 *  accountName, containerId, containerName, clusterName, host, hostIp, labels, labels.app,
 *  labels.podTemplateHash, masterUrl, namespaceId, namespaceName, podId, podIp, podName,
 *  imageId, imageName.
 */
@Plugin(name = "k8s", category = StrLookup.CATEGORY)
public class KubernetesLookup extends AbstractLookup {

    private static final Logger LOGGER = StatusLogger.getLogger();
    private static final String HOSTNAME = "HOSTNAME";
    private static final String SPRING_ENVIRONMENT_KEY = "SpringEnvironment";

    private static volatile KubernetesInfo kubernetesInfo;
    private static Lock initLock = new ReentrantLock();
    private static boolean isSpringIncluded =
            LoaderUtil.isClassAvailable("org.apache.logging.log4j.spring.cloud.config.client.SpringEnvironmentHolder");

    private boolean initialize() {
        if (kubernetesInfo == null || (isSpringIncluded && !kubernetesInfo.isSpringActive)) {
            initLock.lock();
            boolean isSpringActive = isSpringActive();
            if (kubernetesInfo == null || (!kubernetesInfo.isSpringActive && isSpringActive)) {
                try {
                    KubernetesClient client = new KubernetesClientBuilder().createClient();
                    if (client != null) {
                        KubernetesInfo info = new KubernetesInfo();
                        info.isSpringActive = isSpringActive;
                        info.hostName = getHostname();
                        Pod pod = getCurrentPod(info.hostName, client);
                        if (pod != null) {
                            info.app = pod.getMetadata().getLabels().get("app");
                            final String app = info.app != null ? info.app : "";
                            info.podTemplateHash = pod.getMetadata().getLabels().get("pod-template-hash");
                            info.accountName = pod.getSpec().getServiceAccountName();
                            info.clusterName = pod.getMetadata().getClusterName();
                            info.hostIp = pod.getStatus().getHostIP();
                            info.labels = pod.getMetadata().getLabels();
                            info.podId = pod.getMetadata().getUid();
                            info.podIp = pod.getStatus().getPodIP();
                            info.podName = pod.getMetadata().getName();
                            Container container = pod.getSpec().getContainers().stream()
                                    .filter(c -> c.getName().equals(app)).findFirst().orElse(null);
                            if (container != null) {
                                info.containerName = container.getName();
                                info.imageName = container.getImage();
                            }
                            info.masterUrl = client.getMasterUrl();
                            info.namespace = pod.getMetadata().getNamespace();
                            Namespace namespace = client.namespaces().withName(info.namespace).get();
                            if (namespace != null) {
                                info.namespaceId = namespace.getMetadata().getUid();
                            }
                            ContainerStatus containerStatus = pod.getStatus().getContainerStatuses().stream()
                                    .filter(cs -> cs.getName().equals(app)).findFirst().orElse(null);
                            if (containerStatus != null) {
                                info.containerId = containerStatus.getContainerID();
                                info.imageId = containerStatus.getImageID();
                            }
                            kubernetesInfo = info;
                        }
                    }
                } finally {
                    initLock.unlock();
                }
            }
        }
        return kubernetesInfo != null;
    }

    @Override
    public String lookup(LogEvent event, String key) {
        if (!initialize()) {
            return null;
        }
        switch (key) {
            case "accountName": {
                return kubernetesInfo.accountName;
            }
            case "containerId": {
                return kubernetesInfo.containerId;
            }
            case "containerName": {
                return kubernetesInfo.containerName;
            }
            case "clusterName": {
                return kubernetesInfo.clusterName;
            }
            case "host": {
                return kubernetesInfo.hostName;
            }
            case "hostIp": {
                return kubernetesInfo.hostIp;
            }
            case "labels": {
                return kubernetesInfo.labels.toString();
            }
            case "labels.app": {
                return kubernetesInfo.app;
            }
            case "labels.podTemplateHash": {
                return kubernetesInfo.podTemplateHash;
            }
            case "masterUrl": {
                return kubernetesInfo.masterUrl.toString();
            }
            case "namespaceId": {
                return kubernetesInfo.namespaceId;
            }
            case "namespaceName": {
                return kubernetesInfo.namespace;
            }
            case "podId": {
                return kubernetesInfo.podId;
            }
            case "podIp": {
                return kubernetesInfo.podIp;
            }
            case "podName": {
                return kubernetesInfo.podName;
            }
            case "imageId": {
                return kubernetesInfo.imageId;
            }
            case "imageName": {
                return kubernetesInfo.imageName;
            }
            default:
                return null;
        }
    }

    private String getHostname() {
        return System.getenv(HOSTNAME);
    }

    private Pod getCurrentPod(String hostName, KubernetesClient kubernetesClient) {
        try {
            if (isServiceAccount() && Strings.isNotBlank(hostName)) {
                return kubernetesClient.pods().withName(hostName).get();
            }
        } catch (Throwable t) {
            LOGGER.debug("Unable to locate pod with name {}.", hostName);
        }
        return null;
    }

    private boolean isServiceAccount() {
        return Paths.get(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH).toFile().exists()
                && Paths.get(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH).toFile().exists();
    }

    private boolean isSpringActive() {
        return isSpringIncluded && LogManager.getFactory() != null
            && LogManager.getFactory().hasContext(KubernetesLookup.class.getName(), null, false)
            && LogManager.getContext(false).getObject(SPRING_ENVIRONMENT_KEY) != null;
    }

    private static class KubernetesInfo {
        boolean isSpringActive;
        String accountName;
        String app;
        String clusterName;
        String containerId;
        String containerName;
        String hostName;
        String hostIp;
        String imageId;
        String imageName;
        Map<String, String> labels;
        URL masterUrl;
        String namespace;
        String namespaceId;
        String podId;
        String podIp;
        String podName;
        String podTemplateHash;
    }
}

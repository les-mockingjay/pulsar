/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.broker.admin;

import static com.google.common.base.Preconditions.checkArgument;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;

import org.apache.bookkeeper.util.ZkUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.yahoo.pulsar.broker.cache.LocalZooKeeperCacheService;
import com.yahoo.pulsar.broker.web.PulsarWebResource;
import com.yahoo.pulsar.broker.web.RestException;
import com.yahoo.pulsar.common.naming.NamespaceBundle;
import com.yahoo.pulsar.common.naming.NamespaceBundleFactory;
import com.yahoo.pulsar.common.naming.NamespaceBundles;
import com.yahoo.pulsar.common.naming.NamespaceName;
import com.yahoo.pulsar.common.policies.data.BundlesData;
import com.yahoo.pulsar.common.policies.data.ClusterData;
import com.yahoo.pulsar.common.policies.data.Policies;
import com.yahoo.pulsar.common.policies.data.PropertyAdmin;
import com.yahoo.pulsar.common.policies.impl.NamespaceIsolationPolicies;
import com.yahoo.pulsar.common.util.ObjectMapperFactory;
import com.yahoo.pulsar.zookeeper.ZooKeeperCache;
import com.yahoo.pulsar.zookeeper.ZooKeeperChildrenCache;
import com.yahoo.pulsar.zookeeper.ZooKeeperDataCache;

public abstract class AdminResource extends PulsarWebResource {
    private static final Logger log = LoggerFactory.getLogger(AdminResource.class);
    private static final String POLICIES_READONLY_FLAG_PATH = "/admin/flags/policies-readonly";
    public static final String LOAD_SHEDDING_UNLOAD_DISABLED_FLAG_PATH = "/admin/flags/load-shedding-unload-disabled";

    protected ZooKeeper globalZk() {
        return pulsar().getGlobalZkCache().getZooKeeper();
    }

    protected ZooKeeperCache globalZkCache() {
        return pulsar().getGlobalZkCache();
    }

    protected ZooKeeper localZk() {
        return pulsar().getZkClient();
    }

    protected ZooKeeperCache localZkCache() {
        return pulsar().getLocalZkCache();
    }

    protected LocalZooKeeperCacheService localCacheService() {
        return pulsar().getLocalZkCacheService();
    }

    protected void zkCreate(String path, byte[] content) throws Exception {
        globalZk().create(path, content, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    protected void zkCreateOptimistic(String path, byte[] content) throws Exception {
        ZkUtils.createFullPathOptimistic(globalZk(), path, content, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    /**
     * Get the domain of the destination (whether it's queue or topic)
     */
    protected String domain() {
        if (uri.getPath().startsWith("queues/")) {
            return "queue";
        } else if (uri.getPath().startsWith("topics/")) {
            return "topic";
        } else if (uri.getPath().startsWith("persistent/")) {
            return "persistent";
        } else {
            throw new RestException(Status.INTERNAL_SERVER_ERROR, "domain() invoked from wrong resource");
        }
    }

    // This is a stub method for Mockito
    @Override
    protected void validateSuperUserAccess() {
        super.validateSuperUserAccess();
    }

    // This is a stub method for Mockito
    @Override
    protected void validateAdminAccessOnProperty(String property) {
        super.validateAdminAccessOnProperty(property);
    }

    // This is a stub method for Mockito
    @Override
    protected void validateNamespaceOwnershipWithBundles(String property, String cluster, String namespace,
            boolean authoritative, boolean readOnly, BundlesData bundleData) {
        super.validateNamespaceOwnershipWithBundles(property, cluster, namespace, authoritative, readOnly, bundleData);
    }

    // This is a stub method for Mockito
    @Override
    protected void validateBundleOwnership(String property, String cluster, String namespace, boolean authoritative,
            boolean readOnly, NamespaceBundle bundle) {
        super.validateBundleOwnership(property, cluster, namespace, authoritative, readOnly, bundle);
    }

    // This is a stub method for Mockito
    @Override
    protected boolean isLeaderBroker() {
        return super.isLeaderBroker();
    }

    /**
     * Checks whether the broker is allowed to do read-write operations based on the existence of a node in global
     * zookeeper.
     *
     * @throws WebApplicationException
     *             if broker has a read only access if broker is not connected to the global zookeeper
     */
    public void validatePoliciesReadOnlyAccess() {
        boolean arePoliciesReadOnly = true;

        try {
            arePoliciesReadOnly = globalZkCache().exists(POLICIES_READONLY_FLAG_PATH);
        } catch (Exception e) {
            log.warn("Unable to fetch contents of [{}] from global zookeeper", POLICIES_READONLY_FLAG_PATH, e);
            throw new RestException(e);
        }

        if (arePoliciesReadOnly) {
            log.debug("Policies are read-only. Broker cannot do read-write operations");
            throw new RestException(Status.FORBIDDEN, "Broker is forbidden to do read-write operations");
        } else {
            // Make sure the broker is connected to the global zookeeper before writing. If not, throw an exception.
            if (globalZkCache().getZooKeeper().getState() != States.CONNECTED) {
                log.debug("Broker is not connected to the global zookeeper");
                throw new RestException(Status.PRECONDITION_FAILED,
                        "Broker needs to be connected to global zookeeper before making a read-write operation");
            } else {
                // Do nothing, just log the message.
                log.debug("Broker is allowed to make read-write operations");
            }
        }
    }

    /**
     * Get the list of namespaces (on every cluster) for a given property
     *
     * @param property
     *            the property name
     * @return the list of namespaces
     */
    protected List<String> getListOfNamespaces(String property) throws Exception {
        List<String> namespaces = Lists.newArrayList();
        // First get the list of cluster nodes
        log.info("Children of {} : {}", path("policies", property),
                globalZk().getChildren(path("policies", property), null));

        for (String cluster : globalZk().getChildren(path("policies", property), false)) {
            // Then get the list of namespaces
            try {
                for (String namespace : globalZk().getChildren(path("policies", property, cluster), false)) {
                    namespaces.add(String.format("%s/%s/%s", property, cluster, namespace));
                }
            } catch (KeeperException.NoNodeException e) {
                // A cluster was deleted between the 2 getChildren() calls, ignoring
            }
        }

        namespaces.sort(null);
        return namespaces;
    }

    /**
     * Redirect the call to the specified broker
     *
     * @param broker
     *            Broker name
     * @throws MalformedURLException
     *             In case the redirect happens
     */
    protected void validateBrokerName(String broker) throws MalformedURLException {
        String brokerUrl = String.format("http://%s", broker);
        if (!pulsar().getWebServiceAddress().equals(brokerUrl)) {
            String[] parts = broker.split(":");
            checkArgument(parts.length == 2);
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            URI redirect = UriBuilder.fromUri(uri.getRequestUri()).host(host).port(port).build();
            log.debug("[{}] Redirecting the rest call to {}: broker={}", clientAppId(), redirect, broker);
            throw new WebApplicationException(Response.temporaryRedirect(redirect).build());
        }
    }

    protected Policies getNamespacePolicies(String property, String cluster, String namespace) {
        try {
            Policies policies = policiesCache().get(AdminResource.path("policies", property, cluster, namespace))
                    .orElseThrow(() -> new RestException(Status.NOT_FOUND, "Namespace does not exist"));
            // fetch bundles from LocalZK-policies
            NamespaceBundles bundles = pulsar().getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(new NamespaceName(property, cluster, namespace));
            BundlesData bundleData = NamespaceBundleFactory.getBundlesData(bundles);
            policies.bundles = bundleData != null ? bundleData : policies.bundles;
            return policies;
        } catch (RestException re) {
            throw re;
        } catch (Exception e) {
            log.error("[{}] Failed to get namespace policies {}/{}/{}", clientAppId(), property, cluster, namespace, e);
            throw new RestException(e);
        }
    }

    public ObjectMapper jsonMapper() {
        return ObjectMapperFactory.getThreadLocal();
    }

    ZooKeeperDataCache<PropertyAdmin> propertiesCache() {
        return pulsar().getConfigurationCache().propertiesCache();
    }

    ZooKeeperDataCache<Policies> policiesCache() {
        return pulsar().getConfigurationCache().policiesCache();
    }

    ZooKeeperDataCache<ClusterData> clustersCache() {
        return pulsar().getConfigurationCache().clustersCache();
    }

    ZooKeeperChildrenCache managedLedgerListCache() {
        return pulsar().getLocalZkCacheService().managedLedgerListCache();
    }

    Set<String> clusters() {
        try {
            return pulsar().getConfigurationCache().clustersListCache().get();
        } catch (Exception e) {
            throw new RestException(e);
        }
    }

    ZooKeeperChildrenCache clustersListCache() {
        return pulsar().getConfigurationCache().clustersListCache();
    }

    protected void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    protected ZooKeeperDataCache<NamespaceIsolationPolicies> namespaceIsolationPoliciesCache() {
        return pulsar().getConfigurationCache().namespaceIsolationPoliciesCache();
    }

}

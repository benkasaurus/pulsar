/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker.rest.api;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.common.functions.WorkerInfo;
import org.apache.pulsar.common.io.ConnectorDefinition;
import org.apache.pulsar.common.policies.data.ErrorData;
import org.apache.pulsar.common.policies.data.FunctionStats;
import org.apache.pulsar.common.policies.data.WorkerFunctionInstanceStats;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.utils.FunctionCommon;
import org.apache.pulsar.functions.worker.FunctionRuntimeInfo;
import org.apache.pulsar.functions.worker.FunctionRuntimeManager;
import org.apache.pulsar.functions.worker.MembershipManager;
import org.apache.pulsar.functions.worker.WorkerUtils;
import org.apache.pulsar.functions.worker.WorkerService;
import org.apache.pulsar.functions.worker.rest.RestException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.functions.worker.rest.RestUtils.throwUnavailableException;

@Slf4j
public class WorkerImpl {

    private final Supplier<WorkerService> workerServiceSupplier;

    public WorkerImpl(Supplier<WorkerService> workerServiceSupplier) {
        this.workerServiceSupplier = workerServiceSupplier;
    }

    private WorkerService worker() {
        try {
            return checkNotNull(workerServiceSupplier.get());
        } catch (Throwable t) {
            log.info("Failed to get worker service", t);
            throw t;
        }
    }

    private boolean isWorkerServiceAvailable() {
        WorkerService workerService = workerServiceSupplier.get();
        if (workerService == null) {
            return false;
        }
        if (!workerService.isInitialized()) {
            return false;
        }
        return true;
    }

    public List<WorkerInfo> getCluster() {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }
        List<WorkerInfo> workers = worker().getMembershipManager().getCurrentMembership();
        return workers;
    }

    public WorkerInfo getClusterLeader() {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }
        MembershipManager membershipManager = worker().getMembershipManager();
        WorkerInfo leader = membershipManager.getLeader();

        if (leader == null) {
            throw new RestException(Status.INTERNAL_SERVER_ERROR, "Leader cannot be determined");
        }

        return leader;
    }

    public Map<String, Collection<String>> getAssignments() {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        FunctionRuntimeManager functionRuntimeManager = worker().getFunctionRuntimeManager();
        Map<String, Map<String, Function.Assignment>> assignments = functionRuntimeManager.getCurrentAssignments();
        Map<String, Collection<String>> ret = new HashMap<>();
        for (Map.Entry<String, Map<String, Function.Assignment>> entry : assignments.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().keySet());
        }
        return ret;
    }

    public boolean isSuperUser(final String clientRole) {
        return clientRole != null && worker().getWorkerConfig().getSuperUserRoles().contains(clientRole);
    }

    public List<org.apache.pulsar.common.stats.Metrics> getWorkerMetrics(final String clientRole) {
        if (worker().getWorkerConfig().isAuthorizationEnabled() && !isSuperUser(clientRole)) {
            log.error("Client [{}] is not admin and authorized to get function-stats", clientRole);
            throw new WebApplicationException(Response.status(Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorData(clientRole + " is not authorize to get metrics")).build());
        }
        return getWorkerMetrics();
    }

    private List<org.apache.pulsar.common.stats.Metrics> getWorkerMetrics() {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }
        return worker().getMetricsGenerator().generate();
    }

    public List<WorkerFunctionInstanceStats> getFunctionsMetrics(String clientRole) throws IOException {

        if (worker().getWorkerConfig().isAuthorizationEnabled() && !isSuperUser(clientRole)) {
            log.error("Client [{}] is not admin and authorized to get function-stats", clientRole);
            throw new RestException(Status.UNAUTHORIZED, "client is not authorize to perform operation");
        }
        return getFunctionsMetrics();
    }

    private List<WorkerFunctionInstanceStats> getFunctionsMetrics() throws IOException {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        WorkerService workerService = worker();
        Map<String, FunctionRuntimeInfo> functionRuntimes = workerService.getFunctionRuntimeManager()
                .getFunctionRuntimeInfos();

        List<WorkerFunctionInstanceStats> metricsList = new ArrayList<>(functionRuntimes.size());

        for (Map.Entry<String, FunctionRuntimeInfo> entry : functionRuntimes.entrySet()) {
            String fullyQualifiedInstanceName = entry.getKey();
            FunctionRuntimeInfo functionRuntimeInfo = entry.getValue();

            if (workerService.getFunctionRuntimeManager().getRuntimeFactory().externallyManaged()) {
                Function.FunctionDetails functionDetails = functionRuntimeInfo.getFunctionInstance().getFunctionMetaData().getFunctionDetails();
                int parallelism = functionDetails.getParallelism();
                for (int i = 0; i < parallelism; ++i) {
                    FunctionStats.FunctionInstanceStats functionInstanceStats =
                            WorkerUtils.getFunctionInstanceStats(fullyQualifiedInstanceName, functionRuntimeInfo, i);
                    WorkerFunctionInstanceStats workerFunctionInstanceStats = new WorkerFunctionInstanceStats();
                    workerFunctionInstanceStats.setName(FunctionCommon.getFullyQualifiedInstanceId(
                            functionDetails.getTenant(), functionDetails.getNamespace(), functionDetails.getName(), i
                    ));
                    workerFunctionInstanceStats.setMetrics(functionInstanceStats.getMetrics());
                    metricsList.add(workerFunctionInstanceStats);
                }
            } else {
                FunctionStats.FunctionInstanceStats functionInstanceStats =
                        WorkerUtils.getFunctionInstanceStats(fullyQualifiedInstanceName, functionRuntimeInfo,
                                functionRuntimeInfo.getFunctionInstance().getInstanceId());
                WorkerFunctionInstanceStats workerFunctionInstanceStats = new WorkerFunctionInstanceStats();
                workerFunctionInstanceStats.setName(fullyQualifiedInstanceName);
                workerFunctionInstanceStats.setMetrics(functionInstanceStats.getMetrics());
                metricsList.add(workerFunctionInstanceStats);
            }
        }
        return metricsList;
    }

    public List<ConnectorDefinition> getListOfConnectors() {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        return this.worker().getConnectorsManager().getConnectors();
    }
}

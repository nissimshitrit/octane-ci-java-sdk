/*
 *     Copyright 2017 Hewlett-Packard Development Company, L.P.
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.hp.octane.integrations.services.tasking;

import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.api.TasksProcessor;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.connectivity.HttpMethod;
import com.hp.octane.integrations.dto.connectivity.OctaneResponse;
import com.hp.octane.integrations.dto.connectivity.OctaneResultAbridged;
import com.hp.octane.integrations.dto.connectivity.OctaneTaskAbridged;
import com.hp.octane.integrations.dto.executor.CredentialsInfo;
import com.hp.octane.integrations.dto.executor.DiscoveryInfo;
import com.hp.octane.integrations.dto.executor.TestConnectivityInfo;
import com.hp.octane.integrations.dto.executor.TestSuiteExecutionInfo;
import com.hp.octane.integrations.dto.general.CIJobsList;
import com.hp.octane.integrations.dto.general.CIPluginSDKInfo;
import com.hp.octane.integrations.dto.general.CIProviderSummaryInfo;
import com.hp.octane.integrations.dto.pipelines.PipelineNode;
import com.hp.octane.integrations.dto.snapshots.SnapshotNode;
import com.hp.octane.integrations.exceptions.ConfigurationException;
import com.hp.octane.integrations.exceptions.PermissionException;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tasks routing service handles ALM Octane tasks, both coming from abridged logic as well as plugin's REST call delegation
 */

public final class TasksProcessorImpl extends OctaneSDK.SDKServiceBase implements TasksProcessor {
	private static final Logger logger = LogManager.getLogger(TasksProcessorImpl.class);
	private static final DTOFactory dtoFactory = DTOFactory.getInstance();
	private static final String NGA_API = "nga/api/v1";
	private static final String STATUS = "status";
	private static final String SUSPEND_STATUS = "suspend_status";
	private static final String JOBS = "jobs";
	private static final String RUN = "run";
	private static final String BUILDS = "builds";
	private static final String LATEST = "latest";
	private static final String EXECUTOR = "executor";
	private static final String INIT = "init";
	private static final String SUITE_RUN = "suite_run";
	private static final String TEST_CONN = "test_conn";
	private static final String CREDENTIALS_UPSERT = "credentials_upsert";

	public TasksProcessorImpl(Object internalUsageValidator) {
		super(internalUsageValidator);
	}

	public OctaneResultAbridged execute(OctaneTaskAbridged task) {
		if (task == null) {
			throw new IllegalArgumentException("task MUST NOT be null");
		}
		if (task.getUrl() == null || task.getUrl().isEmpty()) {
			throw new IllegalArgumentException("task 'URL' MUST NOT be null nor empty");
		}
		if (!task.getUrl().contains(NGA_API)) {
			throw new IllegalArgumentException("task 'URL' expected to contain '" + NGA_API + "'; wrong handler call?");
		}
		logger.info("processing task '" + task.getId() + "': " + task.getMethod() + " " + task.getUrl());

		OctaneResultAbridged result = DTOFactory.getInstance().newDTO(OctaneResultAbridged.class);
		result.setId(task.getId());
		result.setStatus(200);
		result.setHeaders(new HashMap<String, String>());
		String[] path = pathTokenizer(task.getUrl());
		try {
			if (path.length == 1 && STATUS.equals(path[0])) {
				executeStatusRequest(result);
			} else if (path.length == 1 && SUSPEND_STATUS.equals(path[0])) {
				suspendCiEvents(result, task.getBody());
			} else if (path[0].startsWith(JOBS)) {
				if (path.length == 1) {
					executeJobsListRequest(result, !path[0].contains("parameters=false"));
				} else if (path.length == 2) {
					executePipelineRequest(result, path[1]);
				} else if (path.length == 3 && RUN.equals(path[2])) {
					executePipelineRunRequest(result, path[1], task.getBody());
				} else if (path.length == 4 && BUILDS.equals(path[2])) {
					//TODO: in the future should take the last parameter from the request
					boolean subTree = false;
					if (LATEST.equals(path[3])) {
						executeLatestSnapshotRequest(result, path[1], subTree);
					} else {
						executeSnapshotByNumberRequest(result, path[1], path[3], subTree);
					}
				} else {
					result.setStatus(404);
				}

			} else if (EXECUTOR.equalsIgnoreCase(path[0])) {
				if (HttpMethod.POST.equals(task.getMethod()) && path.length == 2) {
					if (INIT.equalsIgnoreCase(path[1])) {
						DiscoveryInfo discoveryInfo = dtoFactory.dtoFromJson(task.getBody(), DiscoveryInfo.class);
						pluginServices.runTestDiscovery(discoveryInfo);
						result.setStatus(200);
					} else if (SUITE_RUN.equalsIgnoreCase(path[1])) {
						TestSuiteExecutionInfo testSuiteExecutionInfo = dtoFactory.dtoFromJson(task.getBody(), TestSuiteExecutionInfo.class);
						pluginServices.runTestSuiteExecution(testSuiteExecutionInfo);
						result.setStatus(200);
					} else if (TEST_CONN.equalsIgnoreCase(path[1])) {
						TestConnectivityInfo testConnectivityInfo = dtoFactory.dtoFromJson(task.getBody(), TestConnectivityInfo.class);
						OctaneResponse connTestResult = pluginServices.checkRepositoryConnectivity(testConnectivityInfo);
						result.setStatus(connTestResult.getStatus());
						result.setBody(connTestResult.getBody());
					} else if (CREDENTIALS_UPSERT.equalsIgnoreCase(path[1])) {
						CredentialsInfo credentialsInfo = dtoFactory.dtoFromJson(task.getBody(), CredentialsInfo.class);
						executeUpsertCredentials(result, credentialsInfo);

					} else {
						result.setStatus(404);
					}
				} else if (HttpMethod.DELETE.equals(task.getMethod()) && path.length == 2) {
					String id = path[1];
					pluginServices.deleteExecutor(id);
				}

			} else {
				result.setStatus(404);
			}
		} catch (PermissionException pe) {
			logger.warn("task execution failed; error: " + pe.getErrorCode());
			result.setStatus(pe.getErrorCode());
			result.setBody(String.valueOf(pe.getErrorCode()));
		} catch (ConfigurationException ce) {
			logger.warn("task execution failed; error: " + ce.getErrorCode());
			result.setStatus(ce.getErrorCode());
			result.setBody(String.valueOf(ce.getErrorCode()));
		} catch (Exception e) {
			logger.error("task execution failed", e);
			result.setStatus(500);
		}

		logger.info("result for task '" + task.getId() + "' available with status " + result.getStatus());
		return result;
	}


	private String[] pathTokenizer(String url) {
		Map<Integer, String> params = new HashMap<>();
		String[] path = Pattern.compile("^.*" + NGA_API + "/?").matcher(url).replaceFirst("").split("/");
		params.put(0, path[0]);
		for (int i = 1; i < path.length; i++) {
			if ((path[i].equals(BUILDS) || path[i].equals(RUN)) && i == path.length - 1) { // last token
				params.put(2, path[i]);
			} else if (path[i].equals(BUILDS) && i == path.length - 2) {        // one before last token
				params.put(2, path[i]);
				params.put(3, path[i + 1]);
				break;
			} else {
				if (params.get(1) == null) {
					params.put(1, path[i]);
				} else {
					params.put(1, params.get(1) + "/" + path[i]);
				}
			}
		}
		// converting to an array
		List<String> listAsArray = new ArrayList<>();
		for (int i = 0; i < params.size(); i++) {
			listAsArray.add(i, params.get(i));
		}
		return listAsArray.toArray(new String[0]);
	}

	private void executeStatusRequest(OctaneResultAbridged result) {
		CIPluginSDKInfo sdkInfo = dtoFactory.newDTO(CIPluginSDKInfo.class)
				.setApiVersion(OctaneSDK.API_VERSION)
				.setSdkVersion(OctaneSDK.SDK_VERSION);
		CIProviderSummaryInfo status = dtoFactory.newDTO(CIProviderSummaryInfo.class)
				.setServer(pluginServices.getServerInfo())
				.setPlugin(pluginServices.getPluginInfo())
				.setSdk(sdkInfo);
		result.setBody(dtoFactory.dtoToJson(status));
		result.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
	}

	private void executeJobsListRequest(OctaneResultAbridged result, boolean includingParameters) {
		CIJobsList content = pluginServices.getJobsList(includingParameters);
		result.setBody(dtoFactory.dtoToJson(content));
		result.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
	}

	private void executePipelineRequest(OctaneResultAbridged result, String jobId) {
		PipelineNode content = pluginServices.getPipeline(jobId);
		result.setBody(dtoFactory.dtoToJson(content));
		result.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
	}

	private void executePipelineRunRequest(OctaneResultAbridged result, String jobId, String originalBody) {
		pluginServices.runPipeline(jobId, originalBody);
		result.setStatus(201);
	}

	private void suspendCiEvents(OctaneResultAbridged result, String suspend) {
		Boolean toSuspend = Boolean.parseBoolean(suspend);
		pluginServices.suspendCIEvents(toSuspend);
		result.setStatus(201);
	}

	private void executeLatestSnapshotRequest(OctaneResultAbridged result, String jobId, boolean subTree) {
		SnapshotNode data = pluginServices.getSnapshotLatest(jobId, subTree);
		if (data != null) {
			result.setBody(dtoFactory.dtoToJson(data));
		} else {
			result.setStatus(404);
		}
		result.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
	}

	private void executeSnapshotByNumberRequest(OctaneResultAbridged result, String jobCiId, String buildCiId, boolean subTree) {
		SnapshotNode data = pluginServices.getSnapshotByNumber(jobCiId, buildCiId, subTree);
		if (data != null) {
			result.setBody(dtoFactory.dtoToJson(data));
		} else {
			result.setStatus(404);
		}
		result.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
	}

	private void executeUpsertCredentials(OctaneResultAbridged result, CredentialsInfo credentialsInfo) {
		OctaneResponse response = pluginServices.upsertCredentials(credentialsInfo);
		result.setBody(response.getBody());
		result.setStatus(response.getStatus());
	}
}

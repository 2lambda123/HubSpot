import { buildApiAction, buildJsonApiAction } from './base';

export const FetchRequests = buildApiAction(
  'FETCH_REQUESTS',
  {url: '/requests'}
);

export const FetchRequestIds = buildApiAction(
  'FETCH_REQUESTS',
  {url: '/requests/ids?useWebCache=true&state=ACTIVE&state=PAUSED&state=SYSTEM_COOLDOWN&state=FINISHED&state=DEPLOYING_TO_UNPAUSE'}
);

export const FetchRequestsInState = buildApiAction(
  'FETCH_REQUESTS_IN_STATE',
  (state = 'all', renderNotFoundIf404 = true, propertyFilter = null) => {
    let queryString = '?useWebCache=true';
    const propertyJoin = '&property=';
    if (propertyFilter != null) {
      queryString += '&property=';
      queryString += propertyFilter.join(propertyJoin);
    }
    if (_.contains(['pending', 'cleanup'], state)) {
      return {url: `/requests/queued/${state}`, renderNotFoundIf404}; // no property filter for these, different format
    } else if (_.contains(['all', 'noDeploy', 'activeDeploy', 'overUtilizedCpu', 'underUtilizedCpu', 'underUtilizedMem', 'underUtilizedDisk'], state)) {
      return {url: `/requests${queryString}`, renderNotFoundIf404};
    }
    return {url: `/requests/${state}${queryString}`, renderNotFoundIf404};
  }
);

export const FetchRequest = buildApiAction(
  'FETCH_REQUEST',
  (requestId, renderNotFoundIf404) => ({
    url: `/requests/request/${requestId}`,
    renderNotFoundIf404,
    catchStatusCodes: [404]
  }),
  (requestId) => requestId
);

export const SaveRequest = buildJsonApiAction(
  'SAVE_REQUEST',
  'POST',
  (requestData) => ({
    url: '/requests',
    body: requestData,
    catchStatusCodes: [400]
  })
);

export const RemoveRequest = buildJsonApiAction(
  'REMOVE_REQUEST',
  'DELETE',
  (requestId, { message, deleteFromLoadBalancer }) => ({
    url: `/requests/request/${requestId}`,
    body: { message, deleteFromLoadBalancer }
  })
);

export const RunRequest = buildJsonApiAction(
  'RUN_REQUEST_NOW',
  'POST',
  (requestId, data) => ({
    url: `/requests/request/${requestId}/run`,
    body: data
  })
);

export const FetchRequestRun = buildApiAction(
  'FETCH_REQUEST_RUN',
  (requestId, runId, catchStatusCodes = null) => ({
    url: `/requests/request/${ requestId }/run/${runId}`,
    catchStatusCodes
  })
);

export const PauseRequest = buildJsonApiAction(
  'PAUSE_REQUEST',
  'POST',
  (requestId, { durationMillis, allowRunningTasksToFinish, message, actionId, runShellCommandBeforeKill }, catchStatusCodes = null) => ({
    url: `/requests/request/${requestId}/pause`,
    body: {
      killTasks: !allowRunningTasksToFinish,
      durationMillis,
      message,
      actionId,
      runShellCommandBeforeKill
    },
    catchStatusCodes
  })
);

export const PersistRequestPause = buildJsonApiAction(
  'PERSIST_REQUEST_PAUSE',
  'DELETE',
  (requestId) => ({
    url: `/requests/request/${requestId}/pause`
  })
);

export const UnpauseRequest = buildJsonApiAction(
  'UNPAUSE_REQUEST',
  'POST',
  (requestId, { skipHealthchecks, message, actionId }, catchStatusCodes = null) => ({
    url: `/requests/request/${requestId}/unpause`,
    body: { skipHealthchecks, message, actionId },
    catchStatusCodes
  })
);

export const ExitRequestCooldown = buildJsonApiAction(
  'EXIT_REQUEST_COOLDOWN',
  'POST',
  (requestId, {skipHealthchecks, message, actionId}) => ({
    url: `/requests/request/${requestId}/exit-cooldown`,
    body: { skipHealthchecks, message, actionId }
  })
);

export const SkipRequestHealthchecks = buildJsonApiAction(
  'SKIP_REQUEST_HEALTHCHECKS',
  'PUT',
  (requestId, {skipHealthchecks, durationMillis, message, actionId}) => ({
    url: `/requests/request/${requestId}/skip-healthchecks`,
    body: { skipHealthchecks, durationMillis, message, actionId }
  })
);

export const PersistSkipRequestHealthchecks = buildJsonApiAction(
  'PERSIST_SKIP_REQUEST_HEALTHCHECKS',
  'DELETE',
  (requestId) => ({
    url: `/requests/request/${requestId}/skip-healthchecks`
  })
);

export const ScaleRequest = buildJsonApiAction(
  'SCALE_REQUEST',
  'PUT',
  (requestId, {instances, skipHealthchecks, durationMillis, message, actionId, bounce, incremental, largeScaleDownAcknowledged }) => ({
    url: `/requests/request/${requestId}/scale?largeScaleDownAcknowledged=${largeScaleDownAcknowledged}`,
    body: { instances, skipHealthchecks, durationMillis, message, actionId, bounce, incremental }
  })
);

export const PriorityRequest = buildJsonApiAction(
  'PRIORITY_REQUEST',
  'PUT',
  (requestId, {priority, durationMillis, message, actionId }) => ({
    url: `/requests/request/${requestId}/priority`,
    body: { priority, durationMillis, message, actionId }
  })
);

export const PersistRequestPriority = buildJsonApiAction(
  'PERSIST_REQUEST_SCALE',
  'DELETE',
  (requestId) => ({
    url: `/requests/request/${requestId}/priority`
  })
);

export const PersistRequestScale = buildJsonApiAction(
  'PERSIST_REQUEST_SCALE',
  'DELETE',
  (requestId) => ({
    url: `/requests/request/${requestId}/scale`
  })
);

export const BounceRequest = buildJsonApiAction(
  'BOUNCE_REQUEST',
  'POST',
  (requestId, data) => ({
    url: `/requests/request/${requestId}/bounce`,
    body: data
  })
);

export const CancelRequestBounce = buildJsonApiAction(
  'CANCEL_REQUEST_BOUNCE',
  'DELETE',
  (requestId) => ({
    url: `/requests/request/${requestId}/bounce`
  })
);

export const FetchRequestShuffleOptOut = buildApiAction(
  'FETCH_REQUEST_SHUFFLE_OPT_OUT',
  (requestId, renderNotFoundIf404) => ({
    url: `/shuffle/blacklist/${requestId}`,
    renderNotFoundIf404,
    catchStatusCodes: [404]
  }),
  (requestId) => requestId
);

export const EnableRequestShuffleOptOut = buildJsonApiAction(
  'ENABLE_REQUEST_SHUFFLE_OPT_OUT',
  'POST',
  (requestId) => ({
    url: `/shuffle/blacklist/${requestId}`,
  })
);

export const DisableRequestShuffleOptOut = buildJsonApiAction(
  'DISABLE_REQUEST_SHUFFLE_OPT_OUT',
  'DELETE',
  (requestId) => ({
    url: `/shuffle/blacklist/${requestId}`,
  })
);



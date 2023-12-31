package com.hubspot.singularity.resources;

import static com.hubspot.singularity.WebExceptions.checkNotFound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.singularity.SingularityAuthorizationScope;
import com.hubspot.singularity.SingularityDeploy;
import com.hubspot.singularity.SingularityDeployMarker;
import com.hubspot.singularity.SingularityPendingDeploy;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.SingularityRequestParent;
import com.hubspot.singularity.SingularityRequestWithState;
import com.hubspot.singularity.SingularityUser;
import com.hubspot.singularity.auth.SingularityAuthorizer;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.SingularityValidator;
import com.hubspot.singularity.helpers.RequestHelper;
import com.ning.http.client.AsyncHttpClient;
import java.util.Optional;
import org.apache.curator.framework.recipes.leader.LeaderLatch;

public class AbstractRequestResource extends AbstractLeaderAwareResource {
  protected final RequestManager requestManager;
  protected final DeployManager deployManager;
  protected final RequestHelper requestHelper;
  protected final SingularityValidator validator;
  protected final SingularityAuthorizer authorizationHelper;

  public AbstractRequestResource(
    RequestManager requestManager,
    DeployManager deployManager,
    SingularityValidator validator,
    SingularityAuthorizer authorizationHelper,
    AsyncHttpClient httpClient,
    LeaderLatch leaderLatch,
    ObjectMapper objectMapper,
    RequestHelper requestHelper
  ) {
    super(httpClient, leaderLatch, objectMapper);
    this.requestManager = requestManager;
    this.deployManager = deployManager;
    this.requestHelper = requestHelper;
    this.validator = validator;
    this.authorizationHelper = authorizationHelper;
  }

  protected SingularityRequestWithState fetchRequestWithState(
    String requestId,
    SingularityUser user
  ) {
    return fetchRequestWithState(requestId, false, false, user);
  }

  protected SingularityRequestWithState fetchRequestWithState(
    String requestId,
    boolean useWebCache,
    boolean skipApiCache,
    SingularityUser user
  ) {
    Optional<SingularityRequestWithState> request = requestManager.getRequest(
      requestId,
      useWebCache,
      skipApiCache
    );

    checkNotFound(request.isPresent(), "Couldn't find request with id %s", requestId);

    authorizationHelper.checkForAuthorization(
      request.get().getRequest(),
      user,
      SingularityAuthorizationScope.READ
    );

    return request.get();
  }

  protected SingularityRequestParent fillEntireRequest(
    SingularityRequestWithState requestWithState
  ) {
    return fillEntireRequest(requestWithState, Optional.empty());
  }

  protected SingularityRequestParent fillEntireRequest(
    SingularityRequestWithState requestWithState,
    Optional<SingularityRequest> newRequestData
  ) {
    final String requestId = requestWithState.getRequest().getId();

    final Optional<SingularityRequestDeployState> requestDeployState = deployManager.getRequestDeployState(
      requestId
    );

    Optional<SingularityDeploy> activeDeploy = Optional.empty();
    Optional<SingularityDeploy> pendingDeploy = Optional.empty();

    if (requestDeployState.isPresent()) {
      activeDeploy = fillDeploy(requestDeployState.get().getActiveDeploy());
      pendingDeploy = fillDeploy(requestDeployState.get().getPendingDeploy());
    }

    Optional<SingularityPendingDeploy> pendingDeployState = deployManager.getPendingDeploy(
      requestId
    );

    return new SingularityRequestParent(
      newRequestData.orElse(requestWithState.getRequest()),
      requestWithState.getState(),
      requestDeployState,
      activeDeploy,
      pendingDeploy,
      pendingDeployState,
      requestManager.getExpiringBounce(requestId),
      requestManager.getExpiringPause(requestId),
      requestManager.getExpiringScale(requestId),
      requestManager.getExpiringPriority(requestId),
      requestManager.getExpiringSkipHealthchecks(requestId),
      requestHelper.getTaskIdsByStatusForRequest(requestId)
    );
  }

  protected Optional<SingularityDeploy> fillDeploy(
    Optional<SingularityDeployMarker> deployMarker
  ) {
    if (!deployMarker.isPresent()) {
      return Optional.empty();
    }

    return deployManager.getDeploy(
      deployMarker.get().getRequestId(),
      deployMarker.get().getDeployId()
    );
  }
}

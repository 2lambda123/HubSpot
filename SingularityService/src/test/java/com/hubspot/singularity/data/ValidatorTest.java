package com.hubspot.singularity.data;

import com.google.inject.Inject;
import com.hubspot.deploy.HealthcheckOptions;
import com.hubspot.deploy.HealthcheckOptionsBuilder;
import com.hubspot.singularity.RequestType;
import com.hubspot.singularity.SingularityDeploy;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestBuilder;
import com.hubspot.singularity.SingularityRunNowRequestBuilder;
import com.hubspot.singularity.api.SingularityRunNowRequest;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.config.UIConfiguration;
import com.hubspot.singularity.data.history.DeployHistoryHelper;
import com.hubspot.singularity.scheduler.SingularitySchedulerTestBase;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import javax.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ValidatorTest extends SingularitySchedulerTestBase {
  @Inject
  private SingularityConfiguration singularityConfiguration;

  @Inject
  private DeployHistoryHelper deployHistoryHelper;

  @Inject
  private PriorityManager priorityManager;

  @Inject
  private DisasterManager disasterManager;

  @Inject
  private AgentManager agentManager;

  @Inject
  private UIConfiguration uiConfiguration;

  private SingularityValidator validator;

  public ValidatorTest() {
    super(false);
  }

  @BeforeEach
  public void createValidator() {
    validator =
      new SingularityValidator(
        singularityConfiguration,
        deployHistoryHelper,
        priorityManager,
        disasterManager,
        agentManager,
        uiConfiguration,
        testingLbClient
      );
  }

  /**
   * Standard cron: day of week (0 - 6) (0 to 6 are Sunday to Saturday, or use names; 7 is Sunday, the same as 0)
   * Quartz: 1-7 or SUN-SAT
   */

  @Test
  public void testCronExpressionHandlesDayIndexing() {
    Assertions.assertEquals(
      "0 0 12 ? * SUN",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * 7")
    );
    Assertions.assertEquals(
      "0 0 12 ? * SAT",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * 6")
    );
    Assertions.assertEquals(
      "0 0 12 ? * SUN",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * 0")
    );
    Assertions.assertEquals(
      "0 0 12 ? * SUN-FRI",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * 0-5")
    );
    Assertions.assertEquals(
      "0 0 12 ? * SUN,MON,TUE,WED",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * 0,1,2,3")
    );
    Assertions.assertEquals(
      "0 0 12 ? * MON,TUE,WED",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * MON,TUE,WED")
    );
    Assertions.assertEquals(
      "0 0 12 ? * MON-WED",
      validator.getQuartzScheduleFromCronSchedule("0 12 * * MON-WED")
    );
  }

  @Test
  public void itForbidsBracketCharactersInDeployIds() throws Exception {
    final String badDeployId = "deployKey[[";

    SingularityDeploy singularityDeploy = SingularityDeploy
      .newBuilder(badDeployId, badDeployId)
      .build();
    SingularityRequest singularityRequest = new SingularityRequestBuilder(
      badDeployId,
      RequestType.SERVICE
    )
    .build();

    Assertions.assertThrows(
      WebApplicationException.class,
      () -> validator.checkDeploy(singularityRequest, singularityDeploy)
    );
  }

  @Test
  public void itForbidsQuotesInDeployIds() throws Exception {
    final String badDeployId = "deployKey'";

    SingularityDeploy singularityDeploy = SingularityDeploy
      .newBuilder(badDeployId, badDeployId)
      .build();
    SingularityRequest singularityRequest = new SingularityRequestBuilder(
      badDeployId,
      RequestType.SERVICE
    )
    .build();

    boolean thrown = false;
    try {
      validator.checkDeploy(singularityRequest, singularityDeploy);
    } catch (WebApplicationException exn) {
      Assertions.assertTrue(
        ((String) exn.getResponse().getEntity()).contains("[a-zA-Z0-9_.]")
      );
      thrown = true;
    }
    Assertions.assertTrue(thrown);
  }

  @Test
  public void itForbidsTooLongDeployId() {
    String requestId = "requestId";
    SingularityRequest request = new SingularityRequestBuilder(
      requestId,
      RequestType.SCHEDULED
    )
    .build();

    SingularityDeploy deploy = SingularityDeploy
      .newBuilder(requestId, tooLongId())
      .build();

    Assertions.assertThrows(
      WebApplicationException.class,
      () -> validator.checkDeploy(request, deploy)
    );
  }

  @Test
  public void itForbidsInstancesGreaterThanRequestMaxScale() {
    int globalMaxScale = configuration
      .getMesosConfiguration()
      .getMaxNumInstancesPerRequest();
    int requestMaxScale = globalMaxScale - 5;
    SingularityRequest request = new SingularityRequestBuilder(
      "requestId",
      RequestType.SERVICE
    )
      .setMaxScale(Optional.of(requestMaxScale)) // request level max scale < global max scale
      .setInstances(Optional.of(requestMaxScale + 1)) // instances > request level max scale
      .build();

    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkSingularityRequest(
          request,
          Optional.empty(),
          Optional.empty(),
          Optional.empty()
        )
    );
  }

  @Test
  public void itForbidsInstancesGreaterThanGlobalMaxScale() {
    int globalMaxScale = configuration
      .getMesosConfiguration()
      .getMaxNumInstancesPerRequest();
    int requestMaxScale = globalMaxScale + 5;
    SingularityRequest request = new SingularityRequestBuilder(
      "requestId",
      RequestType.SERVICE
    )
      .setMaxScale(Optional.of(requestMaxScale)) // global max scale < request level max scale
      .setInstances(Optional.of(globalMaxScale + 1)) // instances > global max scale (mesos config)
      .build();

    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkSingularityRequest(
          request,
          Optional.empty(),
          Optional.empty(),
          Optional.empty()
        )
    );
  }

  @Test
  public void itForbidsRunNowOfScheduledWhenAlreadyRunning() {
    String deployID = "deploy";
    Optional<String> userEmail = Optional.empty();
    SingularityRequest request = new SingularityRequestBuilder(
      "request2",
      RequestType.SCHEDULED
    )
      .setInstances(Optional.of(1))
      .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.empty();

    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkRunNowRequest(deployID, userEmail, request, runNowRequest, 1, 0)
    );
  }

  @Test
  public void whenRunNowItForbidsMoreInstancesForOnDemandThanInRequest() {
    String deployID = "deploy";
    Optional<String> userEmail = Optional.empty();
    SingularityRequest request = new SingularityRequestBuilder(
      "request2",
      RequestType.ON_DEMAND
    )
      .setInstances(Optional.of(1))
      .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.empty();

    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkRunNowRequest(deployID, userEmail, request, runNowRequest, 1, 0)
    );
  }

  @Test
  public void whenRunNowItForbidsRequestsForLongRunningTasks() {
    String deployID = "deploy";
    Optional<String> userEmail = Optional.empty();
    SingularityRequest request = new SingularityRequestBuilder(
      "request2",
      RequestType.SERVICE
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.empty();

    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkRunNowRequest(deployID, userEmail, request, runNowRequest, 0, 0)
    );
  }

  @Test
  public void whenRunNowItForbidsTooLongRunIds() {
    String deployID = "deploy";
    Optional<String> userEmail = Optional.empty();
    SingularityRequest request = new SingularityRequestBuilder(
      "request2",
      RequestType.SERVICE
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.of(
      runNowRequest(tooLongId())
    );

    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkRunNowRequest(deployID, userEmail, request, runNowRequest, 0, 0)
    );
  }

  @Test
  public void whenRunNowIfRunIdSetItWillBePropagated() {
    String deployID = "deploy";
    Optional<String> userEmail = Optional.empty();
    SingularityRequest request = new SingularityRequestBuilder(
      "request2",
      RequestType.ON_DEMAND
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.of(
      runNowRequest("runId")
    );

    SingularityPendingRequest pendingRequest = validator.checkRunNowRequest(
      deployID,
      userEmail,
      request,
      runNowRequest,
      0,
      0
    );

    Assertions.assertEquals("runId", pendingRequest.getRunId().get());
  }

  @Test
  public void whenRunNowIfNoRunIdSetItWillGenerateAnId() {
    String deployID = "deploy";
    Optional<String> userEmail = Optional.empty();
    SingularityRequest request = new SingularityRequestBuilder(
      "request2",
      RequestType.ON_DEMAND
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.of(runNowRequest());

    SingularityPendingRequest pendingRequest = validator.checkRunNowRequest(
      deployID,
      userEmail,
      request,
      runNowRequest,
      0,
      0
    );

    Assertions.assertTrue(pendingRequest.getRunId().isPresent());
  }

  @Test
  public void whenDeployHasRunNowSetAndNotDeployedItWillRunImmediately() {
    String requestId = "request";
    String deployID = "deploy";
    SingularityRequest request = new SingularityRequestBuilder(
      requestId,
      RequestType.ON_DEMAND
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.of(runNowRequest());

    SingularityDeploy deploy = SingularityDeploy
      .newBuilder(requestId, deployID)
      .setCommand(Optional.of("printenv"))
      .setRunImmediately(runNowRequest)
      .build();

    SingularityDeploy result = validator.checkDeploy(request, deploy);
    Assertions.assertTrue(result.getRunImmediately().isPresent());
    Assertions.assertTrue(result.getRunImmediately().get().getRunId().isPresent());
  }

  @Test
  public void whenDeployHasRunNowSetItValidatesThatItIsLessThanACertaionLength() {
    String requestId = "request";
    String deployID = "deploy";
    SingularityRequest request = new SingularityRequestBuilder(
      requestId,
      RequestType.ON_DEMAND
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.of(
      runNowRequest(tooLongId())
    );

    SingularityDeploy deploy = SingularityDeploy
      .newBuilder(requestId, deployID)
      .setCommand(Optional.of("printenv"))
      .setRunImmediately(runNowRequest)
      .build();

    Assertions.assertThrows(
      WebApplicationException.class,
      () -> validator.checkDeploy(request, deploy)
    );
  }

  @Test
  public void whenDeployNotOneOffOrScheduledItForbidsRunImmediately() {
    String requestId = "request";
    String deployID = "deploy";
    SingularityRequest request = new SingularityRequestBuilder(
      requestId,
      RequestType.WORKER
    )
    .build();
    Optional<SingularityRunNowRequest> runNowRequest = Optional.of(runNowRequest());

    SingularityDeploy deploy = SingularityDeploy
      .newBuilder(requestId, deployID)
      .setCommand(Optional.of("printenv"))
      .setRunImmediately(runNowRequest)
      .build();

    Assertions.assertThrows(
      WebApplicationException.class,
      () -> validator.checkDeploy(request, deploy)
    );
  }

  private SingularityRunNowRequest runNowRequest(String runId) {
    return new SingularityRunNowRequestBuilder()
      .setMessage("message")
      .setSkipHealthchecks(false)
      .setRunId(runId)
      .setCommandLineArgs(Collections.singletonList("--help"))
      .build();
  }

  private SingularityRunNowRequest runNowRequest() {
    return new SingularityRunNowRequestBuilder()
      .setMessage("message")
      .setSkipHealthchecks(false)
      .setCommandLineArgs(Collections.singletonList("--help"))
      .build();
  }

  private String tooLongId() {
    char[] runId = new char[150];
    Arrays.fill(runId, 'x');
    return new String(runId);
  }

  @Test
  public void itForbidsHealthCheckStartupDelaysLongerThanKillWait() {
    // Default kill wait time is 10 minutes (600 seconds)
    HealthcheckOptions healthCheck = new HealthcheckOptionsBuilder("/")
      .setPortNumber(Optional.of(8080L))
      .setStartupDelaySeconds(Optional.of(10000))
      .build();
    SingularityDeploy deploy = SingularityDeploy
      .newBuilder("1234567", "1234567")
      .setHealthcheck(Optional.of(healthCheck))
      .build();
    SingularityRequest request = new SingularityRequestBuilder(
      "1234567",
      RequestType.SERVICE
    )
    .build();

    boolean thrown = false;
    try {
      validator.checkDeploy(request, deploy);
    } catch (WebApplicationException exn) {
      thrown = true;
      Assertions.assertTrue(
        ((String) exn.getResponse().getEntity()).contains("Health check startup delay")
      );
    }

    Assertions.assertTrue(thrown);
  }

  @Test
  public void itForbidsHealthCheckGreaterThanMaxTotalHealthCheck() {
    singularityConfiguration.setHealthcheckMaxTotalTimeoutSeconds(Optional.of(100));
    createValidator();

    // Total wait time on this request is (startup time) + ((interval) + (http timeout)) * (1 + retries)
    // = 50 + (5 + 5) * (9 + 1)
    // = 150
    HealthcheckOptions healthCheck = new HealthcheckOptionsBuilder("/")
      .setPortNumber(Optional.of(8080L))
      .setStartupTimeoutSeconds(Optional.of(50))
      .setIntervalSeconds(Optional.of(5))
      .setResponseTimeoutSeconds(Optional.of(5))
      .setMaxRetries(Optional.of(9))
      .build();
    SingularityDeploy deploy = SingularityDeploy
      .newBuilder("1234567", "1234567")
      .setHealthcheck(Optional.of(healthCheck))
      .setCommand(Optional.of("sleep 100;"))
      .build();
    SingularityRequest request = new SingularityRequestBuilder(
      "1234567",
      RequestType.SERVICE
    )
    .build();

    boolean thrown = false;
    try {
      validator.checkDeploy(request, deploy);
    } catch (WebApplicationException exn) {
      thrown = true;
      Assertions.assertTrue(
        ((String) exn.getResponse().getEntity()).contains("Max healthcheck time")
      );
    }

    Assertions.assertTrue(thrown);
  }

  @Test
  public void itAllowsWorkerToServiceTransitionIfNotLoadBalanced() {
    SingularityRequest request = new SingularityRequestBuilder("test", RequestType.WORKER)
    .build();
    SingularityRequest newRequest = new SingularityRequestBuilder(
      "test",
      RequestType.SERVICE
    )
    .build();
    SingularityRequest result = validator.checkSingularityRequest(
      newRequest,
      Optional.of(request),
      Optional.empty(),
      Optional.empty()
    );
    Assertions.assertEquals(newRequest.getRequestType(), result.getRequestType());
  }

  @Test
  public void itDoesNotWorkerToServiceTransitionIfLoadBalanced() {
    SingularityRequest request = new SingularityRequestBuilder("test", RequestType.WORKER)
    .build();
    SingularityRequest newRequest = new SingularityRequestBuilder(
      "test",
      RequestType.SERVICE
    )
      .setLoadBalanced(Optional.of(true))
      .build();
    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkSingularityRequest(
          newRequest,
          Optional.of(request),
          Optional.empty(),
          Optional.empty()
        )
    );
  }

  @Test
  public void itDoesNotAllowOtherRequestTypesToChange() {
    SingularityRequest request = new SingularityRequestBuilder(
      "test",
      RequestType.ON_DEMAND
    )
    .build();
    SingularityRequest newRequest = new SingularityRequestBuilder(
      "test",
      RequestType.SCHEDULED
    )
    .build();
    Assertions.assertThrows(
      WebApplicationException.class,
      () ->
        validator.checkSingularityRequest(
          newRequest,
          Optional.of(request),
          Optional.empty(),
          Optional.empty()
        )
    );
  }

  @Test
  public void itKeepPreviousInstancesCountWhenEmpty() {
    singularityConfiguration.setAllowEmptyRequestInstances(true);
    SingularityRequest request = new SingularityRequestBuilder("test", RequestType.WORKER)
      .setInstances(Optional.of(5))
      .build();
    SingularityRequest newRequest = new SingularityRequestBuilder(
      "test",
      RequestType.WORKER
    )
    .build();

    SingularityRequest returnedRequest = validator.checkSingularityRequest(
      newRequest,
      Optional.of(request),
      Optional.empty(),
      Optional.empty()
    );

    Assertions.assertTrue(returnedRequest.getInstances().isPresent());
    Assertions.assertSame(
      returnedRequest.getInstances().get(),
      request.getInstances().get()
    );
  }
}

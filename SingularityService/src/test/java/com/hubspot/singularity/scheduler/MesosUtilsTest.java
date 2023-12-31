package com.hubspot.singularity.scheduler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.hubspot.singularity.ExtendedTaskState;
import com.hubspot.singularity.SingularityTaskHistoryUpdate;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.helpers.MesosUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.mesos.v1.Protos.AgentID;
import org.apache.mesos.v1.Protos.FrameworkID;
import org.apache.mesos.v1.Protos.Offer;
import org.apache.mesos.v1.Protos.OfferID;
import org.apache.mesos.v1.Protos.Resource;
import org.apache.mesos.v1.Protos.Value.Range;
import org.apache.mesos.v1.Protos.Value.Ranges;
import org.apache.mesos.v1.Protos.Value.Scalar;
import org.apache.mesos.v1.Protos.Value.Type;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MesosUtilsTest {

  private void test(int numPorts, String... ranges) {
    Resource resource = MesosUtils.getPortsResource(numPorts, buildOffer(ranges));

    Assertions.assertEquals(
      numPorts,
      MesosUtils.getNumPorts(Collections.singletonList(resource))
    );
  }

  @Test
  public void testResourceAddition() {
    List<List<Resource>> toAdd = ImmutableList.of(
      ImmutableList.of(
        Resource
          .newBuilder()
          .setType(Type.SCALAR)
          .setName(MesosUtils.CPUS)
          .setScalar(Scalar.newBuilder().setValue(1))
          .build(),
        Resource
          .newBuilder()
          .setType(Type.SCALAR)
          .setName(MesosUtils.MEMORY)
          .setScalar(Scalar.newBuilder().setValue(1024))
          .build()
      ),
      ImmutableList.of(
        Resource
          .newBuilder()
          .setType(Type.SCALAR)
          .setName(MesosUtils.CPUS)
          .setScalar(Scalar.newBuilder().setValue(2))
          .build(),
        Resource
          .newBuilder()
          .setType(Type.SCALAR)
          .setName(MesosUtils.MEMORY)
          .setScalar(Scalar.newBuilder().setValue(1024))
          .build()
      ),
      ImmutableList.of(
        Resource
          .newBuilder()
          .setType(Type.SCALAR)
          .setName(MesosUtils.CPUS)
          .setScalar(Scalar.newBuilder().setValue(3))
          .build(),
        Resource
          .newBuilder()
          .setType(Type.SCALAR)
          .setName(MesosUtils.MEMORY)
          .setScalar(Scalar.newBuilder().setValue(1024))
          .build()
      )
    );
    List<Resource> combined = MesosUtils.combineResources(toAdd);

    Assertions.assertEquals(6, MesosUtils.getNumCpus(combined, Optional.empty()), 0.1);
    Assertions.assertEquals(3072, MesosUtils.getMemory(combined, Optional.empty()), 0.1);
  }

  @Test
  public void testTaskOrdering() {
    final SingularityTaskId taskId = new SingularityTaskId(
      "r",
      "d",
      System.currentTimeMillis(),
      1,
      "h",
      "r"
    );
    final Optional<String> msg = Optional.empty();

    SingularityTaskHistoryUpdate update1 = new SingularityTaskHistoryUpdate(
      taskId,
      1L,
      ExtendedTaskState.TASK_LAUNCHED,
      msg,
      Optional.<String>empty()
    );
    SingularityTaskHistoryUpdate update2 = new SingularityTaskHistoryUpdate(
      taskId,
      2L,
      ExtendedTaskState.TASK_RUNNING,
      msg,
      Optional.<String>empty()
    );
    SingularityTaskHistoryUpdate update3 = new SingularityTaskHistoryUpdate(
      taskId,
      2L,
      ExtendedTaskState.TASK_FAILED,
      msg,
      Optional.<String>empty()
    );

    List<SingularityTaskHistoryUpdate> list = Arrays.asList(update2, update1, update3);

    Collections.sort(list);

    Assertions.assertTrue(list.get(0).getTaskState() == ExtendedTaskState.TASK_LAUNCHED);
    Assertions.assertTrue(list.get(1).getTaskState() == ExtendedTaskState.TASK_RUNNING);
    Assertions.assertTrue(list.get(2).getTaskState() == ExtendedTaskState.TASK_FAILED);
  }

  @Test
  public void testSubtractResources() {
    Assertions.assertEquals(
      createResources(3, 60, "23:23", "100:175", "771:1000"),
      MesosUtils.subtractResources(
        createResources(5, 100, "23:23", "100:1000"),
        createResources(2, 40, "176:770")
      )
    );

    List<Resource> subtracted = createResources(100, 1000, "1:100", "101:1000");

    subtracted =
      MesosUtils.subtractResources(
        subtracted,
        createResources(5, 100, "23:74", "101:120", "125:130", "750:756")
      );

    Assertions.assertEquals(
      createResources(95, 900, "1:22", "75:100", "121:124", "131:749", "757:1000"),
      subtracted
    );

    subtracted =
      MesosUtils.subtractResources(
        subtracted,
        createResources(20, 20, "75:90", "121:121", "757:1000")
      );

    Assertions.assertEquals(
      createResources(75, 880, "1:22", "91:100", "122:124", "131:749"),
      subtracted
    );
  }

  @Test
  public void testSubtractMissingResources() {
    // cpu
    Assertions.assertEquals(
      createResources(-3, 60, "100:1000"),
      MesosUtils.subtractResources(
        createResources(2, 100, "23:23", "100:1000"),
        createResources(5, 40, "23:23")
      )
    );

    // memory
    Assertions.assertEquals(
      createResources(3, -60, "100:1000"),
      MesosUtils.subtractResources(
        createResources(5, 40, "23:23", "100:1000"),
        createResources(2, 100, "23:23")
      )
    );

    // TODO - mesos utils expects but does not validate that subtraction arguments produce positive/valid results
    Assertions.assertNotEquals(
      createResources(3, 60, "23:23", "100:100", "103:1000"),
      MesosUtils.subtractResources(
        createResources(5, 100, "23:23", "100:1000"),
        createResources(2, 40, "24:24", "101:102")
      )
    );
  }

  @Test
  public void testSubtractDuplicatePorts() {
    // TODO - if an offer were to have duplicate port ranges, subtraction will not dedup
    Assertions.assertEquals(
      createResources(3, 60, "23:23", "100:100", "103:1000", "100:100", "103:1000"),
      MesosUtils.subtractResources(
        createResources(5, 100, "23:23", "23:23", "100:1000", "100:1000"),
        createResources(2, 40, "23:23", "101:102")
      )
    );
  }

  @Test
  public void testCombineDuplicatePorts() {
    Assertions.assertEquals(
      createResources(7, 140, "23:23", "100:1000"),
      MesosUtils.combineResources(
        Arrays.asList(
          createResources(5, 100, "23:23", "100:1000"),
          createResources(2, 40, "23:23", "100:420")
        )
      )
    );

    Assertions.assertEquals(
      createResources(7, 140, "23:23", "24:24", "25:32", "100:1000"),
      MesosUtils.combineResources(
        Arrays.asList(
          createResources(5, 100, "23:23", "25:30", "100:1000"),
          createResources(2, 40, "23:23", "24:24", "27:32", "100:420")
        )
      )
    );
  }

  private List<Resource> createResources(int cpus, int memory, String... ranges) {
    List<Resource> resources = Lists.newArrayList();

    resources.add(
      Resource
        .newBuilder()
        .setType(Type.SCALAR)
        .setName(MesosUtils.CPUS)
        .setScalar(Scalar.newBuilder().setValue(cpus).build())
        .build()
    );
    resources.add(
      Resource
        .newBuilder()
        .setType(Type.SCALAR)
        .setName(MesosUtils.MEMORY)
        .setScalar(Scalar.newBuilder().setValue(memory).build())
        .build()
    );

    if (ranges.length > 0) {
      resources.add(buildPortRanges(ranges));
    }

    return resources;
  }

  @Test
  public void testRangeSelection() {
    test(4, "23:24", "26:26", "28:28", "29:29", "31:32");
    test(2, "22:23");
    test(3, "22:22", "23:23", "24:24", "25:25");
    test(10, "100:10000");
    test(23, "90:100", "9100:9100", "185:1000");
  }

  @Test
  public void testLiteralHostPortSelection() {
    String[] rangesNotOverlappingRequestedPorts = { "23:24", "25:25", "31:32", "50:51" };
    int numPorts = 1;
    List<Long> requestedPorts = Arrays.asList(50L, 51L);
    Resource resource = MesosUtils.getPortsResource(
      numPorts,
      buildOffer(rangesNotOverlappingRequestedPorts).getResourcesList(),
      requestedPorts
    );
    Assertions.assertTrue(
      MesosUtils
        .getAllPorts(Collections.singletonList(resource))
        .containsAll(requestedPorts)
    );
    Assertions.assertEquals(
      numPorts + requestedPorts.size(),
      MesosUtils.getNumPorts(Collections.singletonList(resource))
    );

    String[] rangesOverlappingRequestPorts = { "23:28" };
    numPorts = 4;
    requestedPorts = Arrays.asList(25L, 27L);
    resource =
      MesosUtils.getPortsResource(
        numPorts,
        buildOffer(rangesOverlappingRequestPorts).getResourcesList(),
        requestedPorts
      );
    Assertions.assertTrue(
      MesosUtils
        .getAllPorts(Collections.singletonList(resource))
        .containsAll(requestedPorts)
    );
    Assertions.assertEquals(
      numPorts + requestedPorts.size(),
      MesosUtils.getNumPorts(Collections.singletonList(resource))
    );
  }

  @Test
  public void testGetZeroPortsFromResource() {
    String[] rangesOverlappingRequestPorts = { "23:28" };
    int numPorts = 0;
    List<Long> requestedPorts = Arrays.asList(25L, 27L);
    Resource resource = MesosUtils.getPortsResource(
      numPorts,
      buildOffer(rangesOverlappingRequestPorts).getResourcesList(),
      requestedPorts
    );
    Assertions.assertEquals(0, MesosUtils.getPorts(resource, numPorts).length);
  }

  public static Resource buildPortRanges(String... ranges) {
    Resource.Builder resources = Resource
      .newBuilder()
      .setType(Type.RANGES)
      .setName(MesosUtils.PORTS);

    Ranges.Builder rangesBuilder = Ranges.newBuilder();

    for (String range : ranges) {
      String[] split = range.split("\\:");

      rangesBuilder.addRange(
        Range
          .newBuilder()
          .setBegin(Long.parseLong(split[0]))
          .setEnd(Long.parseLong(split[1]))
      );
    }

    resources.setRanges(rangesBuilder);

    return resources.build();
  }

  private Offer buildOffer(String... ranges) {
    Offer.Builder offer = Offer
      .newBuilder()
      .setId(OfferID.newBuilder().setValue("offerid").build())
      .setFrameworkId(FrameworkID.newBuilder().setValue("frameworkid").build())
      .setHostname("hostname")
      .setAgentId(AgentID.newBuilder().setValue("agentid").build());

    offer.addResources(buildPortRanges(ranges));

    return offer.build();
  }
}

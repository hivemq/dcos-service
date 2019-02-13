package com.mesosphere.sdk.operator.scheduler;

import com.mesosphere.sdk.config.ConfigurationFactory;
import com.mesosphere.sdk.framework.TaskKiller;
import com.mesosphere.sdk.offer.Constants;
import com.mesosphere.sdk.offer.evaluate.PodInfoBuilder;
import com.mesosphere.sdk.scheduler.SchedulerBuilder;
import com.mesosphere.sdk.scheduler.plan.*;
import com.mesosphere.sdk.scheduler.uninstall.TaskKillStep;
import com.mesosphere.sdk.scheduler.uninstall.UninstallStep;
import com.mesosphere.sdk.specification.*;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.state.ConfigTargetStore;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.storage.Persister;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class UpgradeCustomizer implements PlanCustomizer {
    private final Persister persister;
    private final DefaultStepFactory defaultStepFactory;

    Logger log = LoggerFactory.getLogger(UpgradeCustomizer.class);
    private StateStore stateStore;

    public UpgradeCustomizer(Persister persister, ConfigStore<ServiceSpec> configStore) {
        this.persister = persister;
        this.stateStore = new StateStore(persister);
        this.defaultStepFactory = new DefaultStepFactory(configStore, stateStore, Optional.empty());
    }

    @Override
    public Plan updatePlan(Plan plan) {
        log.info("Received plan: {}", plan);
        return handleUpdatePlan(plan);
    }

    private Plan handleUpdatePlan(Plan plan) {
        for (Phase phase : plan.getChildren()) {
            if ("rolling-upgrade".equals(phase.getName())) {
                log.info("Modifying update plan for rolling upgrade");
                // FIXME We would have to add a overcapacity pod instance here first, so:
                //  we bump the pod spec's count
                //  we deploy using the update plan (revert order => deploy overcap pod instance first)
                //  we reduce the pod spec's count again (order doesn't matter, only last pod (overcap instance) is affected
                //  ... not currently possible it seems ("bump instance count step"? how? can't update spec during schedulers lifetime it seems.)

                // For now, bump the instance count manually...
                Collections.reverse(phase.getChildren());
            }
        }
        log.info("New plan: {}", plan);
        return plan;
    }

    private void addNewLastPod(List<Step> steps, DeploymentStep lastStep) {
        String newName;
        final DeploymentStep origDeploy = lastStep;
        //final int index = Integer.parseInt(origDeploy.getName().replace("hivemq-", ""));
        final int index = steps.size();
        newName = "hivemq-" + index + ":[node]";
        final PodInstanceRequirement origRequirement = origDeploy.getPodInstanceRequirement().get();
        final Collection<String> tasks = origRequirement.getTasksToLaunch();
        final PodSpec pod = origRequirement.getPodInstance().getPod();
        final int count = pod.getCount() + 1;
        final DefaultPodSpec newPodSpec = DefaultPodSpec.newBuilder(pod).count(count).build();
        final DefaultPodInstance podInstance = new DefaultPodInstance(pod, index);
        final PodInstanceRequirement newRequirement = PodInstanceRequirement.newBuilder(podInstance,
                tasks).build();
        final DefaultPodInstance defaultPodInstance = new DefaultPodInstance(newPodSpec, index);
        log.info("New requirement: {}; {}", podInstance.getPod(), tasks);
        final Step newPod = defaultStepFactory.getStep(defaultPodInstance, tasks);
        steps.add(newPod);
    }

    @Override
    public Plan updateUninstallPlan(Plan uninstallPlan) {
        log.info("Received uninstall plan: {}", uninstallPlan);
        return uninstallPlan;
    }

    public class RollbackStep extends UninstallStep {

        private final String taskName;

        public RollbackStep(String stepName, Optional<String> namespace, String taskName) {
            super(stepName, namespace);
            this.taskName = taskName;
        }

        @Override
        public void start() {
            // At this point, zookeeper should know about the task we added in post and have executed it.
            setStatus(Status.IN_PROGRESS);
            final Optional<Protos.TaskInfo> taskInfo = stateStore.fetchTask(taskName);
            if (!taskInfo.isPresent()) {
                log.warn("Could not find task for name {}, aborting rollback", taskName);
                setStatus(Status.ERROR);
                return;
            }
            final Protos.TaskID taskId = taskInfo.get().getTaskId();
            TaskKiller.killTask(taskId);
            setStatus(Status.COMPLETE);
        }
    }
}

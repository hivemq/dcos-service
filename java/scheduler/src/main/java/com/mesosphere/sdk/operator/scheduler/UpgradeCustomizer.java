package com.mesosphere.sdk.operator.scheduler;

import com.google.common.collect.Lists;
import com.mesosphere.sdk.framework.TaskKiller;
import com.mesosphere.sdk.offer.Constants;
import com.mesosphere.sdk.scheduler.plan.*;
import com.mesosphere.sdk.scheduler.plan.strategy.*;
import com.mesosphere.sdk.scheduler.uninstall.UninstallStep;
import com.mesosphere.sdk.specification.*;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.storage.Persister;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class UpgradeCustomizer implements PlanCustomizer {
    private final ReversePhaseFactory reversePhaseFactory;
    private final DefaultServiceSpec defaultServiceSpec;

    Logger log = LoggerFactory.getLogger(UpgradeCustomizer.class);
    private StateStore stateStore;

    public UpgradeCustomizer(Persister persister, DefaultServiceSpec defaultServiceSpec) {
        this.stateStore = new StateStore(persister);
        this.reversePhaseFactory = new ReversePhaseFactory();
        this.defaultServiceSpec = defaultServiceSpec;
    }

    @Override
    public Plan updatePlan(Plan plan) {

        /*if (plan.getName().equals(Constants.DEPLOY_PLAN_NAME)) {
            plan.getChildren().forEach(phase -> Collections.reverse(phase.getChildren()));
        }
        log.info("Modified plan: {}", plan);
        return plan;*/
        log.info("Received plan: {}", plan);

        return handleUpdatePlan(plan);
    }


    private Plan handleUpdatePlan(Plan plan) {
        int index = -1;

        for (int i = 0; i < plan.getChildren().size(); ++i) {
            final Phase phase = plan.getChildren().get(i);
            if ("rolling-upgrade".equals(phase.getName())) {
                if(index != -1) {
                    log.warn("More than one rolling upgrade phase found");
                }
                index = i;
            }
        }

        if (index != -1) {
            final PodSpec podSpec = defaultServiceSpec.getPods().get(0);
            log.info("Modifying update phase for rolling upgrade");
            final Phase phase = plan.getChildren().get(index);
            log.info("Original plan: {}", plan);
            plan.getChildren().set(index, revertPhase(podSpec, phase.getChildren()));
            log.info("Modified plan: {}", plan);
            log.info("Strategy: {}", plan.getStrategy());
        }
        Collections.reverse(plan.getChildren());
        return plan;
    }

    private Phase revertPhase(PodSpec podSpec, List<Step> originalSteps) {
        final int count = podSpec.getCount();
        final DefaultPodSpec newPodSpec = DefaultPodSpec.newBuilder(podSpec).count(count).build();
        final Phase phase = reversePhaseFactory.getPhase(newPodSpec, new ReverseSerialStrategy<>(), originalSteps);
        log.info("Phase strategy: {}", phase.getStrategy().getName());
        log.info("Candidates initial: {}", phase.getStrategy().getCandidates(phase.getChildren(), Collections.EMPTY_SET));
        return phase;
    }

    /**
     * Just a {@link SerialStrategy} with reverse dependencies
     *
     * @param <C> element type
     */
    public class ReverseSerialStrategy<C extends Element> extends SerialStrategy<C> {
        @Override
        public Collection<C> getCandidates(Collection<C> elements, Collection<PodInstanceRequirement> dirtyAssets) {
            return getDependencyStrategyHelper(elements).getCandidates(isInterrupted(), dirtyAssets);
        }

        @Override
        public String getName() {
            return "reverse-serial";
        }

        private DependencyStrategyHelper<C> getDependencyStrategyHelper(Collection<C> elements) {
            dependencyStrategyHelper = new DependencyStrategyHelper<>(elements);
            List<C> planElements = elements.stream()
                    .filter(el -> !el.isComplete())
                    .collect(Collectors.toList());
            Collections.reverse(planElements);
            log.info("Reverse strategy element order:");
            planElements.forEach(e -> log.info("element: {}", e.getName()));

            // Note: We mark ALL dependencies (including inferred dependencies) because DependencyStrategyHelper doesn't
            // internally navigate the chain to see if ALL dependencies are complete.
            // For example, say we had c->b->a where b is complete but the other two are not. In this situation,
            // DependencyStrategyHelper would return both c and a as candidates!
            for (int i = 1; i < planElements.size(); i++) {
                C previous = planElements.get(i - 1);

                for (int currIndex = i; currIndex < planElements.size(); currIndex++) {
                    C current = planElements.get(currIndex);
                    dependencyStrategyHelper.addDependency(previous, current);
                    log.info("Adding dependency: child {} ->(depends on) parent {}", previous.getName(), current.getName());
                }
            }
            //Collections.reverse(planElements);
            log.info("Reverse strategy candidates for these elements: {}", dependencyStrategyHelper.getCandidates(false, Collections.EMPTY_SET));
            return dependencyStrategyHelper;
        }
    }

    /**
     * Custom phase factory to revert the steps at creation, therefore reversing their dependencies (and order)
     */
    public class ReversePhaseFactory {

        public Phase getPhase(PodSpec podSpec, Strategy<Step> strategy, List<Step> originalSteps) {
            return new DefaultPhase(
                    podSpec.getType(),
                    getSteps(podSpec, originalSteps),
                    strategy,
                    Collections.emptyList());
        }

        private List<Step> getSteps(PodSpec podSpec, List<Step> originalSteps) {
            return originalSteps;
        }
    }

    @Override
    public Plan updateUninstallPlan(Plan uninstallPlan) {
        log.info("Received uninstall plan: {}", uninstallPlan);
        return uninstallPlan;
    }

}

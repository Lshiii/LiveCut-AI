package io.livecut.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Broker outages must not occupy the scheduler that renews other application maintenance. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "livecut.dispatch.mode", havingValue = "kafka")
public class StageWakeupScheduling {
    // Register the conventional default too: otherwise our dedicated scheduler would be
    // the single TaskScheduler bean and Spring would also select it for unnamed tasks.
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler maintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("livecut-maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(3);
        return scheduler;
    }

    @Bean(name = "stageWakeupScheduler")
    public ThreadPoolTaskScheduler stageWakeupScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stage-wakeup-relay-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(3);
        return scheduler;
    }
}

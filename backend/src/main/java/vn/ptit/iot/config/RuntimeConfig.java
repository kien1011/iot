package vn.ptit.iot.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ptit.iot.util.LuxConverter;

@Configuration
public class RuntimeConfig {
    @Bean public Clock clock() { return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")); }
    @Bean public TransactionTemplate transactions(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
    @Bean public ThreadPoolTaskScheduler commandScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("led-timeout-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
    @Bean public LuxConverter luxConverter(@Value("${ldr.fixed-resistance}") double fixed,
            @Value("${ldr.resistance-at-10-lux}") double at10, @Value("${ldr.gamma}") double gamma) {
        return new LuxConverter(fixed, at10, gamma);
    }
}

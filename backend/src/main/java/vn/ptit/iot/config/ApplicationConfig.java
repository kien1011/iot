package vn.ptit.iot.config;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ptit.iot.util.LuxConverter;
@Configuration
public class ApplicationConfig {
    @Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")); }
    @Bean TransactionTemplate transactions(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    @Bean(destroyMethod="shutdownNow") ScheduledExecutorService timers() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(3);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    @Bean LuxConverter luxConverter(@Value("${app.lux.fixed-resistance}") double fixed,
        @Value("${app.lux.resistance-at-10-lux}") double r10, @Value("${app.lux.gamma}") double gamma) {
        return new LuxConverter(fixed, r10, gamma);
    }
}

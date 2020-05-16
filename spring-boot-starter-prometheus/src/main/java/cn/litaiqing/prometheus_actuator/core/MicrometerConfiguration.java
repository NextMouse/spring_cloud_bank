package cn.litaiqing.prometheus_actuator.core;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.actuate.health.CompositeHealthIndicator;
import org.springframework.boot.actuate.health.HealthAggregator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h3>千分尺配置核心逻辑</h3>
 * <br/>
 *
 * @version 1.0
 * @date: 2020-05-16 21:58
 * @since JDK 1.8
 */
@Configuration
public class MicrometerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MicrometerConfiguration.class);

    private static final String TAG_APPLICATION = "application";
    private static final String TAG_INSTANCE = "instance";
    private static final String TAG_ADDRESS = "address";
    private static final String TAG_ENV = "env";
    private static final String TAG_PORT = "port";
    private static final String HEALTH = "health";
    private static final String ENV_DEFAULT = "default";
    private static final String LOCALHOST = "127.0.0.1";
    private static final String GOOGLE_DNS = "8.8.8.8";


    /**
     * 获取当前IP
     *
     * @return
     */
    private String getHost() {
        try (final DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(GOOGLE_DNS), 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            log.error("{}", e);
        }
        return LOCALHOST;
    }


    @Autowired
    private Environment environment;

    private static final Map<String, String> TAGS = new HashMap<>();

    /**
     * Map TASG 转 String 标签
     */
    public String tagsToString() {
        if (CollectionUtils.isEmpty(TAGS)) {
            return null;
        }
        StringBuffer sb = new StringBuffer();
        for (java.util.Map.Entry<String, String> entry : TAGS.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(",");
        }
        return sb.toString();
    }

    /**
     * 标签汇总Map
     *
     * @return
     * @date 2018年11月14日 下午6:51:01
     */

    public Map<String, String> tagsMap() {
        if (!CollectionUtils.isEmpty(TAGS)) {
            return TAGS;
        }
        // 项目名
        String application = environment.getProperty("spring.application.name", "micrometer");
        TAGS.put(TAG_APPLICATION, application.toLowerCase());
        // 项目端口
        String PORT = environment.getProperty("server.port", "8080");
        if (StringUtils.isNotBlank(PORT)) {
            TAGS.put(TAG_PORT, PORT);
        }
        // 项目服务器IP
        String ADDRESS = getHost();
        if (StringUtils.isNotBlank(ADDRESS)) {
            TAGS.put(TAG_ADDRESS, ADDRESS);
        }
        if (StringUtils.isNotBlank(ADDRESS) && StringUtils.isNotBlank(PORT)) {
            String INSTANCE = ADDRESS + ":" + PORT;
            TAGS.put(TAG_INSTANCE, INSTANCE);
        }
        // 项目指定环境
        String springEnv = environment.getProperty("env");
        String springProfilesActive = environment.getProperty("spring.profiles.active");
        String springCloudConfigProfile = environment.getProperty("spring.cloud.config.profile");
        if (log.isDebugEnabled()) {
            log.debug("env:{}, spring.profiles.active:{}, spring.cloud.config.profile:{}",
                    springEnv,
                    springProfilesActive,
                    springCloudConfigProfile);
        }
        String theEnv = null;
        if (StringUtils.isNotBlank(springEnv)) {
            theEnv = springEnv;
        } else if (StringUtils.isNotBlank(springProfilesActive)) {
            theEnv = springProfilesActive;
        } else if (StringUtils.isNotBlank(springCloudConfigProfile)) {
            theEnv = springCloudConfigProfile;
        } else {
            theEnv = ENV_DEFAULT;
        }
        if (StringUtils.isNotBlank(theEnv)) {
            TAGS.put(TAG_ENV, theEnv.toLowerCase());
        }
        if (log.isDebugEnabled()) {
            log.debug("TAGS:{}", tagsToString());
        }
        return TAGS;
    }

    private CompositeHealthIndicator healthIndicator;

    @Bean
    @ConditionalOnMissingBean(MeterRegistryCustomizer.class)
    MeterRegistryCustomizer<?> meterRegistryCustomizer(MeterRegistry meterRegistry,
                                                       HealthAggregator healthAggregator,
                                                       List<HealthIndicator> healthIndicatorList) {
        if (log.isDebugEnabled()) {
            log.debug("MicrometerConfiguration Open...");
        }
        open();
        final Map<String, String> tagsMap = tagsMap();
        Map<String, HealthIndicator> healthIndicatorMap = new HashMap<>();
        for (Integer i = 0; i < healthIndicatorList.size(); i++) {
            healthIndicatorMap.put(i.toString(), healthIndicatorList.get(i));
        }
        healthIndicator = new CompositeHealthIndicator(healthAggregator, healthIndicatorMap);
        meterRegistry.gauge(
                HEALTH,
                Tags.of(
                        TAG_APPLICATION,
                        tagsMap.get(TAG_APPLICATION),
                        TAG_ADDRESS,
                        tagsMap.get(TAG_ADDRESS),
                        TAG_PORT,
                        tagsMap.get(TAG_PORT),
                        TAG_ENV,
                        tagsMap.get(TAG_ENV)),
                healthIndicator,
                health -> {
                    Status status = health.health().getStatus();
                    switch (status.getCode()) {
                        case "UP":
                            return 3;
                        case "OUT_OF_SERVICE":
                            return 2;
                        case "DOWN":
                            return 1;
                        case "UNKNOWN":
                        default:
                            return 0;
                    }
                });
        return mr -> {
            MeterRegistry.Config config = mr.config();
            if (!CollectionUtils.isEmpty(tagsMap)) {
                for (java.util.Map.Entry<String, String> entry : tagsMap.entrySet()) {
                    if (!TAG_INSTANCE.equalsIgnoreCase(entry.getKey())) {
                        config.commonTags(entry.getKey(), entry.getValue());
                    }
                }
            }
        };
    }


    @Bean
    @ConditionalOnMissingBean(TimedAspect.class)
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(
                registry);
    }

    public MicrometerConfiguration() {
    }

    /**
     * 设置 prometheus 开关
     */
    private void open() {
        String enabledKey = "management.metrics.export.prometheus.enabled";
        String enabledValue = environment.getProperty(enabledKey);
        if (StringUtils.isBlank(enabledValue)) {
            System.setProperty(enabledKey, "true");
        }
        String includeKey = "management.endpoints.web.exposure.include";
        String includeValue = environment.getProperty(includeKey);
        if (StringUtils.isBlank(includeValue)) {
            System.setProperty(includeKey, "health,info,env,prometheus,metrics,httptrace,threaddump,heapdump");
        }
    }

}

package com.labex.monitor.config;

import com.labex.monitor.access.AccessLogInterceptor;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.geo.IpLocationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({MonitorProperties.class, IpLocationProperties.class})
public class MonitorConfiguration implements WebMvcConfigurer {

    private final AccessLogInterceptor accessLogInterceptor;
    private final MonitorAuthInterceptor monitorAuthInterceptor;

    public MonitorConfiguration(AccessLogInterceptor accessLogInterceptor, MonitorAuthInterceptor monitorAuthInterceptor) {
        this.accessLogInterceptor = accessLogInterceptor;
        this.monitorAuthInterceptor = monitorAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessLogInterceptor).addPathPatterns("/**");
        registry.addInterceptor(monitorAuthInterceptor).addPathPatterns("/ops/**");
    }
}
package com.pams.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * 批量导入课表需一次上传几十个文件，而 Tomcat 对 multipart 请求的 part/参数
 * 数量有上限（实测默认仅 ~10 个 part 就抛 FileCountLimitExceededException，500）。
 * Spring Boot 的 spring.servlet.multipart 不暴露这两个上限，只能直接配 Tomcat
 * connector：maxParameterCount 决定 partLimit 上限，maxPartCount 决定 part 数上限。
 */
@Component
public class TomcatMultipartConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            connector.setMaxParameterCount(1000);
            connector.setMaxPartCount(1000);
        });
    }
}

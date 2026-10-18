package com.aash.mailguard;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class MailGuardWorkerApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(MailGuardApplication.class)
                .web(WebApplicationType.NONE)
                .properties("jobs.worker.enabled=true")
                .run(args);
    }
}

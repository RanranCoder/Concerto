package com.pams.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("!postgresql-candidate")
@EnableScheduling
class SchedulingConfig {}

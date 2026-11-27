package com.campussphere.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Every five minutes, adds Skill Exchange reminders (a day and an hour before each scheduled session) for the two
 * students of that session. Each reminder has its own event key, so nothing is ever added twice. Until the Skill
 * Exchange tables exist (V23), it only logs once and does nothing. Set app.skills.reminders-enabled=false to stop it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.skills.reminders-enabled", havingValue = "true", matchIfMissing = true)
class SkillReminders {
    private static final Logger log = LoggerFactory.getLogger(SkillReminders.class);
    private final SkillExchange skills;
    private boolean warned;

    SkillReminders(SkillExchange skills) {
        this.skills = skills;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    void remind() {
        try {
            int added = skills.sendReminders();
            if (added > 0) log.info("Added {} Skill Exchange session reminder(s)", added);
        } catch (BadSqlGrammarException missingTables) {
            if (!warned) log.info("Skill Exchange reminders are waiting for the V23 tables");
            warned = true;
        }
    }
}

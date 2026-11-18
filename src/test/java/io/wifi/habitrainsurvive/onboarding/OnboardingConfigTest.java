package io.wifi.habitrainsurvive.onboarding;

import io.wifi.habitrainsurvive.presentation.ConfigFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingConfigTest {
    @TempDir Path directory;

    @Test void bundledConfigurationIsValidAndUsesExistingDailyCommand() throws Exception {
        Path file = directory.resolve("onboarding.json");
        ConfigFiles.installDefault(file, "/defaults/onboarding.json");
        var config = ConfigFiles.read(file, OnboardingConfig.class);
        config.validate();
        assertEquals("newbie", config.rewardKit);
        assertTrue(config.steps.stream().anyMatch(step -> step.command.equals("daily")));
        assertEquals(5, config.steps.size());
    }

    @Test void duplicateStepIdsAreRejected() {
        var config = new OnboardingConfig();
        config.steps = new ArrayList<>(config.steps);
        config.steps.add(config.steps.getFirst());
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test void multilineCommandsAndNullRulesAreRejected() {
        var config = new OnboardingConfig();
        config.steps = List.of(new OnboardingConfig.Step("bad", "错误配置", List.of("说明"), "daily\nop someone"));
        assertThrows(IllegalArgumentException.class, config::validate);
        config = new OnboardingConfig();
        config.rules = null;
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}

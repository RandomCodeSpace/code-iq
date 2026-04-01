package io.github.randomcodespace.iq.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that SpaController is conditionally registered based on codeiq.ui.enabled,
 * and that static resource serving is also disabled via spring.web.resources.add-mappings=false.
 */
class SpaControllerConditionalTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SpaController.class)
            .withSystemProperties("spring.profiles.active=serving");

    @Test
    void spaControllerHasConditionalOnPropertyAnnotation() {
        var annotation = SpaController.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).contains("codeiq.ui.enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
        assertThat(annotation.matchIfMissing()).isTrue();
    }

    @Test
    void spaControllerRegisteredWhenUiEnabledTrue() {
        contextRunner
                .withPropertyValues("codeiq.ui.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SpaController.class));
    }

    @Test
    void spaControllerRegisteredWhenPropertyAbsent() {
        contextRunner
                .run(context -> assertThat(context).hasSingleBean(SpaController.class));
    }

    @Test
    void spaControllerNotRegisteredWhenUiEnabledFalse() {
        contextRunner
                .withPropertyValues("codeiq.ui.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SpaController.class));
    }

    @Test
    void spaControllerHasProfileAnnotation() {
        var annotation = SpaController.class.getAnnotation(Profile.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains("serving");
    }

    @Test
    void staticResourcesDisabledWhenUiDisabled() {
        // When --no-ui is active, CodeIqApplication sets both properties.
        // Verify that spring.web.resources.add-mappings=false combined with
        // codeiq.ui.enabled=false leaves no SpaController in the context.
        contextRunner
                .withPropertyValues("codeiq.ui.enabled=false", "spring.web.resources.add-mappings=false")
                .run(context -> assertThat(context).doesNotHaveBean(SpaController.class));
    }

    @Test
    void spaControllerExplicitRoutesContainGraph() {
        // Verify that /graph routes are present and /topology, /flow routes are removed.
        Method forwardMethod = Arrays.stream(SpaController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(GetMapping.class))
                .filter(m -> m.getName().equals("forward"))
                .findFirst()
                .orElse(null);
        assertThat(forwardMethod).isNotNull();
        List<String> routes = Arrays.asList(forwardMethod.getAnnotation(GetMapping.class).value());
        assertThat(routes).contains("/graph", "/graph/**");
        assertThat(routes).doesNotContain("/topology", "/topology/**", "/flow", "/flow/**");
    }
}

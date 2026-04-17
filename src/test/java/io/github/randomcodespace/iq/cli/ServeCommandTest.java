package io.github.randomcodespace.iq.cli;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ServeCommandTest {

    @Test
    void commandNameIsServe() {
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        assertEquals("serve", cmdLine.getCommandName());
    }

    @Test
    void defaultPortIs8080() {
        var cmd = new ServeCommand();
        // After picocli parsing with defaults
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs();  // Use defaults
        assertEquals(8080, cmd.getPort());
    }

    @Test
    void defaultHostIsAllInterfaces() {
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs();
        assertEquals("0.0.0.0", cmd.getHost());
    }

    @Test
    void pathDefaultsToCurrentDir() {
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs();
        assertNotNull(cmd.getPath());
        assertEquals(".", cmd.getPath().toString());
    }

    @Test
    void customPortIsParsed() {
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs("--port", "9090");
        assertEquals(9090, cmd.getPort());
    }

    @Test
    void noUiDefaultsToFalse() {
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs();
        assertEquals(false, cmd.isNoUi());
    }

    @Test
    void noUiFlagIsRecognized() {
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs("--no-ui");
        assertEquals(true, cmd.isNoUi());
    }

    @Test
    void pathNotSwallowedWhenNoUiPrecedesPath() {
        // Regression: --no-ui is boolean and must not consume the next positional arg.
        var cmd = new ServeCommand();
        var cmdLine = new CommandLine(cmd);
        cmdLine.parseArgs("--no-ui", "/some/repo");
        assertEquals(true, cmd.isNoUi());
        assertEquals(Path.of("/some/repo"), cmd.getPath());
    }

    @Test
    void markReadyPublishesLivenessThenReadiness() {
        // Regression guard for /actuator/health returning 503 OUT_OF_SERVICE:
        // serve's CommandLineRunner blocks forever, so Spring never fires
        // ApplicationReadyEvent and readiness stays REFUSING_TRAFFIC.
        // ServeCommand must publish LivenessState.CORRECT + ReadinessState.ACCEPTING_TRAFFIC
        // before blocking so /actuator/health reports UP (200).
        var cmd = new ServeCommand();
        var mockEvents = Mockito.mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(cmd, "events", mockEvents);

        cmd.markReady();

        var captor = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(mockEvents, times(2)).publishEvent(captor.capture());
        var published = captor.getAllValues();
        // Order matters: liveness first (process is alive), then readiness (serving traffic).
        assertEquals(LivenessState.CORRECT, published.get(0).getState());
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, published.get(1).getState());
    }
}

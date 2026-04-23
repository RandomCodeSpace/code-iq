package io.github.randomcodespace.iq.detector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every detector has a valid {@link DetectorInfo} annotation
 * and that annotation metadata is consistent with the detector's runtime values.
 */
@SpringBootTest
@ActiveProfiles("test")
class DetectorInfoAnnotationTest {

    @Autowired
    private DetectorRegistry registry;

    @Test
    void everyDetectorHasDetectorInfoAnnotation() {
        List<Detector> missing = registry.allDetectors().stream()
                .filter(d -> d.getClass().getAnnotation(DetectorInfo.class) == null)
                .toList();
        assertTrue(missing.isEmpty(),
                "Detectors missing @DetectorInfo: " +
                        missing.stream().map(Detector::getName).collect(Collectors.joining(", ")));
    }

    @Test
    void annotationNameMatchesGetName() {
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info, d.getClass().getSimpleName() + " missing @DetectorInfo");
            assertEquals(info.name(), d.getName(),
                    d.getClass().getSimpleName() + ": @DetectorInfo.name does not match getName()");
        }
    }

    @Test
    void annotationLanguagesMatchGetSupportedLanguages() {
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info);
            Set<String> annotationLangs = Set.of(info.languages());
            Set<String> runtimeLangs = d.getSupportedLanguages();
            assertEquals(runtimeLangs, annotationLangs,
                    d.getClass().getSimpleName() + ": @DetectorInfo.languages does not match getSupportedLanguages()");
        }
    }

    @Test
    void annotationCategoryIsNotBlank() {
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info);
            assertFalse(info.category().isBlank(),
                    d.getClass().getSimpleName() + ": @DetectorInfo.category is blank");
        }
    }

    @Test
    void annotationDescriptionIsNotBlank() {
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info);
            assertFalse(info.description().isBlank(),
                    d.getClass().getSimpleName() + ": @DetectorInfo.description is blank");
        }
    }

    @Test
    void annotationNodeKindsIsNotEmpty() {
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info);
            assertTrue(info.nodeKinds().length > 0,
                    d.getClass().getSimpleName() + ": @DetectorInfo.nodeKinds is empty");
        }
    }

    @Test
    void annotationParserTypeIsConsistentWithBaseClass() {
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info);
            ParserType parser = info.parser();
            // Verify parser type aligns with the class hierarchy
            if (parser == ParserType.JAVAPARSER) {
                assertTrue(d.getClass().getName().contains("java"),
                        d.getClass().getSimpleName() + ": JAVAPARSER parser but not in java package");
            }
        }
    }

    @Test
    void allCategoriesAreValid() {
        Set<String> validCategories = Set.of(
                "endpoints", "entities", "auth", "messaging", "config",
                "infra", "structures", "frontend", "database"
        );
        for (Detector d : registry.allDetectors()) {
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            assertNotNull(info);
            assertTrue(validCategories.contains(info.category()),
                    d.getClass().getSimpleName() + ": unknown category '" + info.category() + "'");
        }
    }
}

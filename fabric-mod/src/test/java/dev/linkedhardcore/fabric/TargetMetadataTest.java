package dev.linkedhardcore.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TargetMetadataTest {
    private static JsonObject depends;
    private static String target;

    @BeforeAll
    static void loadBuiltMetadata() throws Exception {
        SharedConstants.tryDetectVersion();
        target = SharedConstants.getCurrentVersion().id();
        try (var stream = TargetMetadataTest.class.getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(stream);
            depends = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("depends");
        }
    }

    private boolean accepts(String dependency, String version) throws Exception {
        return VersionPredicate.parse(depends.get(dependency).getAsString()).test(Version.parse(version));
    }

    @Test
    void metadataAcceptsActualMappedMinecraftAndRejectsOtherTargets() throws Exception {
        assertTrue(target.equals("26.2") || target.equals("26.3"), target);
        assertTrue(accepts("minecraft", target));
        assertFalse(accepts("minecraft", target.equals("26.2") ? "26.3" : "26.2"));
        assertFalse(accepts("minecraft", "26.4"));
        assertFalse(accepts("minecraft", "26.1"));
    }

    @Test
    void apiMinimumMatchesCompiledTarget() throws Exception {
        String api = target.equals("26.2") ? "0.156.0+26.2" : "0.165.0+26.3";
        assertEquals(">=" + api, depends.get("fabric-api").getAsString());
        assertTrue(accepts("fabric-api", api));
        assertFalse(accepts("fabric-api", target.equals("26.2") ? "0.155.0+26.2" : "0.164.0+26.3"));
    }

    @Test
    void loaderAndJavaPredicatesEnforceMinimums() throws Exception {
        assertTrue(accepts("fabricloader", "0.19.3"));
        assertFalse(accepts("fabricloader", "0.19.2"));
        assertTrue(accepts("java", "25"));
        assertFalse(accepts("java", "24"));
    }
}

package com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class JsonPathTypeAdapterTest {
    private <T> T read(Class<T> type, InputStream input) {
        return new JsonPathTypeAdapter<T>(com.jayway.jsonpath.JsonPath.using(
                Configuration.builder().jsonProvider(new GsonJsonProvider()).build()), type).read(input);
    }

    public static class Item implements com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPathObj {
        @JsonPath({"$.missing", "$.name"}) public String name;
        @JsonPath("$.enabled") public boolean enabled;
    }

    public static class Container {
        @JsonPath("$.item") public Item item;
        @JsonPath("$.items[*]") public List<Item> items;
        @JsonPath("$.labels[*]") public List<String> labels;
        @JsonPath("$.absent") public String absent;
    }

    @Test public void readsNestedObjectsArraysAndFallbackPaths() {
        String json = "{\"item\":{\"name\":\"one\",\"enabled\":true},"
                + "\"items\":[{\"name\":\"two\",\"enabled\":false}],\"labels\":[\"a\",\"b\"]}";
        Container result = read(Container.class, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals("one", result.item.name);
        assertTrue(result.item.enabled);
        assertEquals("two", result.items.get(0).name);
        assertFalse(result.items.get(0).enabled);
        assertEquals(Arrays.asList("a", "b"), result.labels);
        assertNull(result.absent);
    }

    @Test public void readsRegularAndSabrPlaybackFixtures() throws Exception {
        for (String path : Arrays.asList("player/web/2025.11.10_player_regular.json",
                "player/web/2025.11.10_player_sabr_only.json")) {
            JsonObject expected;
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input);
                expected = new JsonParser().parse(new java.io.InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            VideoInfo result;
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                result = read(VideoInfo.class, input);
            }
            assertNotNull(result);
            assertEquals(expected.getAsJsonObject("videoDetails").get("videoId").getAsString(), result.getVideoDetails().getVideoId());
            assertEquals(expected.getAsJsonObject("videoDetails").get("title").getAsString(), result.getVideoDetails().getTitle());
            assertEquals(expected.getAsJsonObject("streamingData").getAsJsonArray("adaptiveFormats").size(), result.getAdaptiveFormats().size());
        }
    }
}

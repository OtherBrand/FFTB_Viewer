package fft_battleground.wordcloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Emote dictionary parsers against live-shaped payloads (pinned from the
 * real BTTV/FFZ/7TV endpoints, 2026-10-25). HTTP behavior itself is covered
 * by the refresh degradation contract (partial on failure, keep-old on total
 * failure) which lives in the service body.
 */
public class EmoteDictionaryServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void parsesBttvGlobalAndChannelShapes() throws Exception {
		Set<String> codes = new HashSet<>();
		EmoteDictionaryService.parseBttv(this.mapper.readTree(
				"[{\"id\":\"54fa8f14\",\"code\":\":tf:\"},{\"id\":\"54fa8fce\",\"code\":\"CiGrip\"}]"), codes);
		assertEquals(Set.of(":tf:", "CiGrip"), codes);

		Set<String> channel = new HashSet<>();
		EmoteDictionaryService.parseBttvChannel(this.mapper.readTree(
				"{\"channelEmotes\":[{\"code\":\"ChanEmote\"}],\"sharedEmotes\":[{\"code\":\"SharedEmote\"}]}"), channel);
		assertEquals(Set.of("ChanEmote", "SharedEmote"), channel);
	}

	@Test
	public void parsesFfzGlobalAndRoomShapes() throws Exception {
		Set<String> codes = new HashSet<>();
		EmoteDictionaryService.parseFfz(this.mapper.readTree(
				"{\"sets\":{\"3\":{\"emoticons\":[{\"name\":\"ZrehplaR\"}]},\"648545\":{\"emoticons\":[{\"name\":\"Oakface\"}]}}}"), codes);
		assertEquals(Set.of("ZrehplaR", "Oakface"), codes);
	}

	@Test
	public void parsesSevenTvGlobalAndChannelShapes() throws Exception {
		Set<String> global = new HashSet<>();
		EmoteDictionaryService.parseSevenTv(this.mapper.readTree(
				"{\"emotes\":[{\"name\":\"reckH\"},{\"name\":\"3H\"}]}"), global);
		assertEquals(Set.of("reckH", "3H"), global);

		Set<String> channel = new HashSet<>();
		EmoteDictionaryService.parseSevenTvChannel(this.mapper.readTree(
				"{\"emote_set\":{\"emotes\":[{\"name\":\"ChanSeven\"}]}}"), channel);
		assertEquals(Set.of("ChanSeven"), channel);
	}

	@Test
	public void malformedPayloadsDegradeToEmpty() throws Exception {
		Set<String> codes = new HashSet<>();
		EmoteDictionaryService.parseBttv(this.mapper.readTree("{\"unexpected\":true}"), codes);
		EmoteDictionaryService.parseFfz(null, codes);
		EmoteDictionaryService.parseSevenTv(this.mapper.readTree("[]"), codes);
		assertTrue(codes.isEmpty(), "malformed payloads must not throw or invent codes");
	}

}

package fft_battleground.wordcloud;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fft_battleground.util.HttpClientFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Third-party emote dictionary (BTTV/FFZ/7TV, public endpoints, no auth):
 * supplements the Twitch-native emotes tag, which never carries third-party
 * emotes. Refreshed daily; after each successful load the word_counts rows
 * for known emote codes are purged, so the dictionary is self-healing for
 * rows written between refreshes. Endpoint failures degrade to partial
 * sets (old set is kept on total failure).
 */
@Service
@Slf4j
public class EmoteDictionaryService {

	private static final String BTTV_GLOBAL = "https://api.betterttv.net/3/cached/emotes/global";
	private static final String BTTV_CHANNEL = "https://api.betterttv.net/3/cached/users/twitch/";
	private static final String FFZ_GLOBAL = "https://api.frankerfacez.com/v1/set/global";
	private static final String FFZ_ROOM = "https://api.frankerfacez.com/v1/room/";
	private static final String SEVENTV_GLOBAL = "https://7tv.io/v3/emote-sets/global";
	private static final String SEVENTV_CHANNEL = "https://7tv.io/v3/users/twitch/";

	private final ObjectMapper jsonMapper = new ObjectMapper();
	private final RestTemplate restTemplate = HttpClientFactory.createRestTemplate();

	@Value("${irc.channel}")
	private String channel;

	@Autowired
	private WordCountRepository wordCountRepository;

	@Getter
	private volatile Set<String> emoteCodes = Set.of();

	@Scheduled(initialDelay = 20_000L, fixedDelay = 24 * 60 * 60 * 1000L)
	public void refresh() {
		try {
			Set<String> codes = new HashSet<>();
			this.fetchJson(BTTV_GLOBAL, EmoteDictionaryService::parseBttv, codes);
			this.fetchJson(FFZ_GLOBAL, EmoteDictionaryService::parseFfz, codes);
			this.fetchJson(SEVENTV_GLOBAL, EmoteDictionaryService::parseSevenTv, codes);

			//the FFZ room endpoint is login-keyed and carries the channel's twitch id,
			//which keys the BTTV/7TV channel endpoints
			JsonNode ffzRoom = this.fetchJsonNode(FFZ_ROOM + StringUtils.lowerCase(this.channel));
			if(ffzRoom != null && ffzRoom.path("room").has("twitch_id")) {
				String twitchId = ffzRoom.path("room").path("twitch_id").asText();
				this.parseFfz(ffzRoom, codes);
				this.fetchJson(BTTV_CHANNEL + twitchId, EmoteDictionaryService::parseBttvChannel, codes);
				this.fetchJson(SEVENTV_CHANNEL + twitchId, EmoteDictionaryService::parseSevenTvChannel, codes);
			}

			if(codes.isEmpty()) {
				log.warn("emote dictionary refresh produced nothing; keeping the previous set");
				return;
			}
			this.emoteCodes = codes.stream().map(StringUtils::lowerCase).collect(Collectors.toUnmodifiableSet());
			log.info("emote dictionary loaded {} codes", this.emoteCodes.size());

			//self-heal rows written before this dictionary knew a code
			int purged = this.wordCountRepository.purgeWords(this.emoteCodes);
			if(purged > 0) {
				log.info("purged {} emote-polluted word_counts rows", purged);
			}
		} catch(Exception e) {
			log.error("emote dictionary refresh failed; keeping the previous set", e);
		}
	}

	public boolean isEmote(String token) {
		return this.emoteCodes.contains(token);
	}

	private void fetchJson(String url, java.util.function.BiConsumer<JsonNode, Set<String>> parser, Set<String> into) {
		try {
			parser.accept(this.fetchJsonNode(url), into);
		} catch(Exception e) {
			log.warn("emote endpoint failed (continuing with partial dictionary): {} — {}", url, e.getMessage());
		}
	}

	private JsonNode fetchJsonNode(String url) throws Exception {
		String body = this.restTemplate.getForObject(url, String.class);
		return body != null ? this.jsonMapper.readTree(body) : null;
	}

	//BTTV global: [{code: "..."}]
	static void parseBttv(JsonNode root, Set<String> into) {
		if(root != null && root.isArray()) {
			root.forEach(item -> addIfPresent(into, item.path("code").asText(null)));
		}
	}

	//BTTV channel: {channelEmotes: [{code}], sharedEmotes: [{code}]}
	static void parseBttvChannel(JsonNode root, Set<String> into) {
		if(root == null) {
			return;
		}
		parseBttv(root.path("channelEmotes"), into);
		parseBttv(root.path("sharedEmotes"), into);
	}

	//FFZ: {sets: {<id>: {emoticons: [{name}]}}} (both global and room payloads)
	static void parseFfz(JsonNode root, Set<String> into) {
		if(root == null) {
			return;
		}
		root.path("sets").forEach(set -> set.path("emoticons")
				.forEach(emote -> addIfPresent(into, emote.path("name").asText(null))));
	}

	//7TV: {emotes: [{name}]}
	static void parseSevenTv(JsonNode root, Set<String> into) {
		parseSevenTvEmotes(root == null ? null : root.path("emotes"), into);
	}

	//7TV channel: {emote_set: {emotes: [{name}]}}
	static void parseSevenTvChannel(JsonNode root, Set<String> into) {
		parseSevenTvEmotes(root == null ? null : root.path("emote_set").path("emotes"), into);
	}

	private static void parseSevenTvEmotes(JsonNode emotes, Set<String> into) {
		if(emotes != null && emotes.isArray()) {
			emotes.forEach(emote -> addIfPresent(into, emote.path("name").asText(null)));
		}
	}

	private static void addIfPresent(Set<String> into, String code) {
		if(StringUtils.isNotBlank(code)) {
			into.add(code);
		}
	}

}

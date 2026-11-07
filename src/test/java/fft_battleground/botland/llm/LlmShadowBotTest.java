package fft_battleground.botland.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.model.BattleGroundTeam;
import fft_battleground.tournament.model.Unit;

/**
 * R7 Phase 1 shadow mode: serializer shape, verdict parsing (strict + tolerant
 * + garbage), kill-switch darkness, and the never-touch-betting isolation.
 */
public class LlmShadowBotTest {

	private Unit unit(String name, String className, short brave, short faith) {
		Unit unit = new Unit();
		unit.setName(name);
		unit.setClassName(className);
		unit.setBrave(brave);
		unit.setFaith(faith);
		unit.setSign("Aries");
		unit.setActionSkill("Jump");
		unit.setMainhand("Poison Bow");
		return unit;
	}

	@Test
	public void serializerProducesTheCompactContract() {
		MatchStateSerializer serializer = new MatchStateSerializer();
		String json = serializer.serialize(null, BattleGroundTeam.RED, BattleGroundTeam.BLUE,
				org.apache.commons.lang3.tuple.Pair.of(
						List.of(this.unit("david", "Squire", (short) 84, (short) 40)),
						List.of(this.unit("goliath", "Wizard", (short) 40, (short) 71))),
				org.apache.commons.lang3.tuple.Pair.of(30000, 10000),
				org.apache.commons.lang3.tuple.Pair.of(12, 5), null);

		assertTrue(json.contains("\"team\":\"RED\""));
		assertTrue(json.contains("\"pot\":30000"));
		assertTrue(json.contains("\"bettors\":12"));
		assertTrue(json.contains("\"class\":\"Squire\""));
		assertTrue(json.contains("\"brave\":84"));
		assertTrue(json.contains("\"zodiac\":\"Aries\""));
		assertTrue(json.contains("\"skills\":\"Jump\""));
		assertTrue(json.contains("\"gear\":\"Poison Bow\""));
		assertTrue(json.contains("\"leftWinShare\":0.75"));
		assertTrue(json.contains("\"leftOdds\":1.33"));
		assertTrue(json.contains("\"map\":\"unknown\""), "null mapInfo degrades, never throws");
	}

	@Test
	public void verdictParsesStrictJson() throws Exception {
		LlmShadowBot bot = new LlmShadowBot();
		LlmShadowBot.Verdict verdict = bot.parseVerdict(
				"{\"pick\":\"LEFT\",\"confidence\":72,\"top_reasons\":[\"brave advantage\",\"better gear\",\"map favors\"]}",
				BattleGroundTeam.RED, BattleGroundTeam.BLUE);
		assertEquals("RED", verdict.pick, "LEFT resolves to the left team's name");
		assertEquals(Integer.valueOf(72), verdict.confidence);
		assertNotNull(verdict.reasons);
		assertTrue(verdict.reasons.contains("brave advantage"));
	}

	@Test
	public void verdictToleratesMarkdownFencesAndProse() throws Exception {
		LlmShadowBot bot = new LlmShadowBot();
		LlmShadowBot.Verdict verdict = bot.parseVerdict(
				"Here's my analysis:\n```json\n{\"pick\":\"RIGHT\",\"confidence\":88,\"top_reasons\":[\"faith synergy\"]}\n```",
				BattleGroundTeam.RED, BattleGroundTeam.BLUE);
		assertEquals("BLUE", verdict.pick);
		assertEquals(Integer.valueOf(88), verdict.confidence);
	}

	@Test
	public void garbagePersistsAsNullPickNotAnException() throws Exception {
		LlmShadowBot bot = new LlmShadowBot();
		LlmShadowBot.Verdict verdict = bot.parseVerdict("I cannot answer that.", BattleGroundTeam.RED, BattleGroundTeam.BLUE);
		assertNull(verdict.pick, "unparseable output still counts against the sample honestly");
		assertNull(verdict.confidence);
	}

	@Test
	public void killSwitchMeansNoCallAndNoRow() {
		ChatClient client = mock(ChatClient.class);
		when(client.isEnabled()).thenReturn(false);
		LlmPredictionRepo repo = mock(LlmPredictionRepo.class);
		LlmShadowBot bot = new LlmShadowBot();
		ReflectionTestUtils.setField(bot, "chatClient", client);
		ReflectionTestUtils.setField(bot, "serializer", new MatchStateSerializer());
		ReflectionTestUtils.setField(bot, "llmPredictionRepo", repo);

		bot.evaluateShadowPrediction("{}", 42L, BattleGroundTeam.RED, BattleGroundTeam.BLUE);

		verify(client, never()).chat(anyString(), anyString());
		verify(repo, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(LlmPrediction.class));
	}

	@Test
	public void happyPathStoresTheFullPredictionRow() {
		ChatClient client = mock(ChatClient.class);
		when(client.isEnabled()).thenReturn(true);
		when(client.chat(anyString(), anyString())).thenReturn(new ChatClient.ChatResult(
				"{\"pick\":\"LEFT\",\"confidence\":80,\"top_reasons\":[\"a\",\"b\",\"c\"]}",
				"qwen/test", 900L, 120L, 3400L, 0.0021, false));
		LlmPredictionRepo repo = mock(LlmPredictionRepo.class);
		LlmShadowBot bot = new LlmShadowBot();
		ReflectionTestUtils.setField(bot, "chatClient", client);
		ReflectionTestUtils.setField(bot, "serializer", new MatchStateSerializer());
		ReflectionTestUtils.setField(bot, "llmPredictionRepo", repo);

		bot.evaluateShadowPrediction("{\"map\":\"test\"}", 99L, BattleGroundTeam.RED, BattleGroundTeam.BLUE);

		ArgumentCaptor<LlmPrediction> saved = ArgumentCaptor.forClass(LlmPrediction.class);
		verify(repo).saveAndFlush(saved.capture());
		assertEquals("RED", saved.getValue().getPick());
		assertEquals(Integer.valueOf(80), saved.getValue().getConfidence());
		assertEquals("qwen/test", saved.getValue().getModel());
		assertEquals(Long.valueOf(900L), saved.getValue().getPromptTokens());
		assertEquals(Long.valueOf(3400L), saved.getValue().getLatencyMs());
		assertEquals("{\"map\":\"test\"}", saved.getValue().getMatchStateJson());
		//the system prompt carries the strict-JSON contract
		org.mockito.Mockito.verify(client).chat(contains("STRICT JSON"), contains("\"map\":\"test\""));
	}
}

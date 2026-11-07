package fft_battleground.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Prestige checklist (owner spec 2026-10-27): missing = acquirable universe − owned,
 * prestige-category skills excluded from the universe (owner's rule), monster skills
 * included when tips are available and gracefully degraded when not.
 */
public class PrestigeProgressServiceTest {

	private PrestigeProgressService serviceWithMonsters(Set<String> monsters) throws Exception {
		SkillUtils utils = new SkillUtils();
		if(monsters != null) {
			SkillUtils spy = Mockito.spy(utils);
			Mockito.doReturn(monsters).when(spy).monsterSet();
			utils = spy;
		} else {
			SkillUtils failing = Mockito.spy(utils);
			Mockito.doThrow(new RuntimeException("tips unavailable")).when(failing).monsterSet();
			utils = failing;
		}
		PrestigeProgressService service = new PrestigeProgressService();
		ReflectionTestUtils.setField(service, "skillUtils", utils);
		return service;
	}

	@Test
	public void prestigeAndLegendarySkillsAreNeverRequirements() throws Exception {
		PrestigeProgressService service = this.serviceWithMonsters(Set.of("Squire", "Chocobo"));
		PrestigeProgressService.PrestigeProgress progress = service.progressFor("player", List.of());

		assertFalse(progress.getMissingSkills().contains("Teleport2"), "prestige rewards are not requirements");
		assertFalse(progress.getMissingSkills().contains("RaidBoss"), "prestige rewards are not requirements");
		assertFalse(progress.getMissingSkills().contains("BirbBrain"), "legendary skills are not acquirable requirements");
		assertTrue(progress.getMissingSkills().contains("PunchArt"), "job skills are requirements");
		assertTrue(progress.getMissingSkills().contains("Squire"), "monster-set skills are requirements");
	}

	@Test
	public void ownedSkillsReduceTheMissingList() throws Exception {
		PrestigeProgressService service = this.serviceWithMonsters(Set.of("Squire"));
		PrestigeProgressService.PrestigeProgress empty = service.progressFor("player", List.of());
		PrestigeProgressService.PrestigeProgress some = service.progressFor("player", List.of("PunchArt", "Jump"));

		assertEquals(empty.getRequiredCount(), some.getRequiredCount(), "universe is stable");
		assertEquals(empty.getRemainingCount() - 2, some.getRemainingCount(), "two owned skills = two fewer missing");
		assertEquals(some.getOwnedCount() + some.getRemainingCount(), some.getRequiredCount(), "totals are self-consistent");
		assertFalse(some.getMissingSkills().contains("PunchArt"), "owned skills leave the missing list");
	}

	@Test
	public void tipsFailureDegradesToStaticVocabulary() throws Exception {
		PrestigeProgressService service = this.serviceWithMonsters(null);
		PrestigeProgressService.PrestigeProgress progress = service.progressFor("player", List.of());
		assertTrue(progress.getRequiredCount() > 100, "static vocabulary still substantial: " + progress.getRequiredCount());
		assertFalse(progress.getMissingSkills().contains("Teleport2"), "exclusions hold in degradation too");
	}
}

package fft_battleground.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Prestige checklist (owner-specced 2026-10-27): the skills a player still
 * needs before they can prestige — the acquirable universe minus owned,
 * mirroring the SkillUtils category model the nightly prestige task runs on.
 */
@Service
@Slf4j
public class PrestigeProgressService {

	@Autowired
	private SkillUtils skillUtils;

	public PrestigeProgress progressFor(String player, List<String> ownedSkills) {
		try {
			Set<String> universe = this.skillUtils.acquirableVocabulary(this.skillUtils.monsterSet());
			Set<String> owned = ownedSkills != null
					? ownedSkills.stream().filter(skill -> skill != null && !skill.isBlank())
							.collect(Collectors.toSet())
					: Set.of();
			List<String> missing = universe.stream()
					.filter(skill -> !owned.contains(skill))
					.sorted()
					.collect(Collectors.toList());
			return new PrestigeProgress(universe.size() - missing.size(), universe.size(), missing);
		} catch(Exception e) {
			//the monster set needs the live tips; degrade to the static vocabulary
			log.warn("prestige progress computed without the monster set for {}: {}", player, e.getMessage());
			Set<String> universe = this.skillUtils.acquirableVocabulary(null);
			Set<String> owned = ownedSkills != null ? ownedSkills.stream()
					.filter(skill -> skill != null && !skill.isBlank()).collect(Collectors.toSet()) : Set.of();
			List<String> missing = universe.stream()
					.filter(skill -> !owned.contains(skill))
					.sorted()
					.collect(Collectors.toList());
			return new PrestigeProgress(universe.size() - missing.size(), universe.size(), missing);
		}
	}

	@lombok.Data
	public static class PrestigeProgress {
		private int ownedCount;
		private int requiredCount;
		private int remainingCount;
		private List<String> missingSkills;

		public PrestigeProgress(int owned, int required, List<String> missing) {
			this.ownedCount = owned;
			this.requiredCount = required;
			this.remainingCount = required - owned;
			this.missingSkills = missing;
		}
	}
}

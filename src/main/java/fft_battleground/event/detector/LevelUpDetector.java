package fft_battleground.event.detector;

import org.apache.commons.lang3.StringUtils;

import fft_battleground.event.EventDetector;
import fft_battleground.event.detector.model.LevelUpEvent;
import fft_battleground.event.detector.model.PlayerSkillEvent;
import fft_battleground.model.ChatMessage;

public class LevelUpDetector implements EventDetector<LevelUpEvent> {

	private static final String findString = "you advanced to Level ";
	private static final String endCharacter = "!";
	//real stream wording is "Your gil floor has increased to <N>!" with any floor value;
	//the old hardcoded "Gil Floor ... 368" probe never matched (wrong case, fixed value)
	private static final String skillFindString = "you learned the skill: ";

	@Override
	public LevelUpEvent detect(ChatMessage message) {
		LevelUpEvent event = null;
		if(StringUtils.equals(StringUtils.lowerCase(message.getUsername()), StringUtils.lowerCase("FFTBattleground")) &&
				StringUtils.contains(message.getMessage(), findString)) {
			String username = StringUtils.substringBefore(message.getMessage(), ",");
			String levelString = StringUtils.replace(StringUtils.substringBetween(message.getMessage(), findString, endCharacter), ",", "");
			Short level = Short.valueOf(levelString);
			PlayerSkillEvent skillEvent = null;
			int skillIndex = StringUtils.indexOfIgnoreCase(message.getMessage(), skillFindString);
			if(skillIndex >= 0) {
				String tail = message.getMessage().substring(skillIndex + skillFindString.length());
				String skill = StringUtils.trim(StringUtils.substringBefore(tail, endCharacter));
				if(StringUtils.isNotEmpty(skill)) {
					skillEvent = new PlayerSkillEvent(username, skill);
				}
			}

			event = new LevelUpEvent(username, level);
			if(skillEvent != null) {
				event.setSkill(skillEvent);
			}
		}

		return event;
	}

}

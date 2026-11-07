package fft_battleground.wordcloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Pure chat-to-words filter for the word cloud (R3a), unit-pinned by
 * WordCloudFilterTest. Exclusion matrix: system username, !-commands,
 * URLs, pure-numeric, nothing-valid-after-normalize. Domain vocabulary
 * (champion, gil, team names) is deliberately NOT stopped.
 */
public final class WordCloudFilter {

	public static final String SYSTEM_USERNAME = "fftbattleground";

	private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(https?://\\S+|www\\.\\S+)");
	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

	private static final int MIN_WORD_LENGTH = 2;
	private static final int MAX_WORD_LENGTH = 40;

	/** Basic English + chat filler; deliberately modest and code-visible. */
	private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
			"the", "a", "an", "and", "or", "but", "if", "then", "than", "so", "because", "as", "of",
			"at", "by", "for", "with", "about", "into", "to", "from", "in", "on", "off", "out", "over",
			"is", "are", "was", "were", "be", "been", "being", "am",
			"have", "has", "had", "do", "does", "did", "will", "would", "can", "could", "should",
			"i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them", "my", "your",
			"his", "its", "our", "their", "this", "that", "these", "those",
			"not", "no", "yes", "yeah", "nah", "just", "like", "get", "got", "go", "going", "gone",
			"up", "down", "im", "its", "ive", "dont", "cant", "wont", "ur", "u", "ur", "wat", "tho",
			"there", "here", "what", "which", "who", "whom", "when", "where", "why", "how", "all",
			"any", "some", "more", "most", "other", "such", "only", "own", "same", "too", "very",
			"s", "t", "re", "ve", "ll", "d", "m", "o", "y", "ain", "aren", "couldn", "didn", "doesn",
			"hasn", "haven", "isn", "mightn", "mustn", "needn", "shan", "shouldn", "wasn", "weren",
			"won", "wouldn", "ok", "okay", "pls", "plz", "please", "thanks", "thank", "ty", "lol"));

	private WordCloudFilter() {}

	/** Message-level exclusion matrix: false means the whole message is not chat vocabulary. */
	public static boolean shouldIngest(String username, String message) {
		if(StringUtils.isBlank(username) || StringUtils.isBlank(message)) {
			return false;
		}
		if(StringUtils.equalsIgnoreCase(StringUtils.trim(username), SYSTEM_USERNAME)) {
			return false; // system/track announcements
		}
		if(StringUtils.trim(message).startsWith("!")) {
			return false; // commands
		}
		if(URL_PATTERN.matcher(message).find()) {
			return false; // links
		}
		return true;
	}

	/**
	 * Normalize to countable words: lowercase, split on non-alphanumeric, drop
	 * short/pure-numeric/stop words. Empty result = emote/symbol-only message.
	 */
	public static List<String> normalize(String message) {
		List<String> words = new ArrayList<>();
		String lowered = StringUtils.lowerCase(message);
		for(String token : NON_ALPHANUMERIC.split(lowered)) {
			if(token.length() < MIN_WORD_LENGTH || token.length() > MAX_WORD_LENGTH) {
				continue;
			}
			if(StringUtils.isNumeric(token) || STOPWORDS.contains(token)) {
				continue;
			}
			words.add(token);
		}
		return words;
	}

}

package fft_battleground.repo.model;

import java.io.Serializable;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Composite PK for word_counts: (scope, word, date).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WordCountKey implements Serializable {

	private static final long serialVersionUID = 1L;

	/** 'global' or 'player:<lowername>' */
	private String scope;

	private String word;

	private LocalDate date;

	public static WordCountKey forScope(String scope, String word, LocalDate date) {
		return new WordCountKey(scope, word, date);
	}

}

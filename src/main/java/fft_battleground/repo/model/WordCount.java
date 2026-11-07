package fft_battleground.repo.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Daily-bucketed chat word counts (R3a), keyed (scope, word, date) with
 * scope = 'global' | 'player:<lowername>'. Written only through
 * WordCountRepository (the storage-abstraction hatch — Postgres today).
 */
@Entity
@Table(name = "word_counts", indexes = { @Index(columnList = "scope,date", name = "word_counts_scope_date_idx") })
@IdClass(WordCountKey.class)
@Data
@NoArgsConstructor
public class WordCount {

	@Id
	@Column(name = "scope", length = 120, nullable = false)
	private String scope;

	@Id
	@Column(name = "word", length = 40, nullable = false)
	private String word;

	@Id
	@Column(name = "date", nullable = false)
	private LocalDate date;

	@Column(name = "count", nullable = false)
	private Integer count;

	public WordCount(WordCountKey key, Integer count) {
		this.scope = key.getScope();
		this.word = key.getWord();
		this.date = key.getDate();
		this.count = count;
	}

}

package fft_battleground.repo.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.WordCount;
import fft_battleground.repo.model.WordCountKey;

@Repository
public interface WordCountJpaRepo extends JpaRepository<WordCount, WordCountKey> {

	//word -> summed count over the window; Object[]{String word, Long total}; Pageable caps the result
	@Query("SELECT wc.word, SUM(wc.count) FROM WordCount wc WHERE wc.scope = :scope AND wc.date >= :since "
			+ "GROUP BY wc.word ORDER BY SUM(wc.count) DESC")
	public List<Object[]> topWordsForScope(@Param("scope") String scope, @Param("since") LocalDate since,
			org.springframework.data.domain.Pageable pageable);

	@Modifying
	@Query("DELETE FROM WordCount wc WHERE wc.count <= 1 AND wc.date < :cutoff")
	public int pruneSingleOccurrenceBefore(@Param("cutoff") LocalDate cutoff);

	@Modifying
	@Query("DELETE FROM WordCount wc WHERE wc.word IN :words")
	public int purgeWords(@Param("words") java.util.Collection<String> words);

}

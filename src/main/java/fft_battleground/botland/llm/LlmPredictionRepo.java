package fft_battleground.botland.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmPredictionRepo extends JpaRepository<LlmPrediction, Long> {

}
